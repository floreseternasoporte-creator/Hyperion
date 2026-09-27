package com.drex.hyperion;

import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Motor DNS del túnel local: parsea paquetes IPv4/UDP del TUN,
 * bloquea dominios de la Blocklist (responde 0.0.0.0),
 * aplica CUARENTENA de red por UID (responde NXDOMAIN),
 * y reenvía el resto a 1.1.1.1 / 8.8.8.8 con caché local de TTL real.
 *
 * Cuarentena: antes de procesar, se resuelve el UID del paquete
 * (puerto origen UDP -> /proc/net/udp{,6}, buscando la línea cuya
 * dirección local sea 10.8.0.2 (0A080002) con ese puerto). Si el UID
 * está en cuarentena (ver HyperionVpnService.setUidQuarantined), se
 * responde NXDOMAIN y se cuenta. Si /proc/net no es legible o el UID
 * no se resuelve, se PERMITE (fail-open con log).
 */
public class DnsEngine {
    private static final String TAG = "HyperionDns";
    private static final String[] UPSTREAMS = {"1.1.1.1", "8.8.8.8"};
    private static final long MAX_CACHE_TTL_MS = 24L * 3600_000; // tope 24h
    /** Dirección TUN en hex tal como aparece en /proc/net/udp (little-endian). */
    private static final String TUN_IP_HEX = "0A080002"; // 10.8.0.2

    /** Contador propio de consultas denegadas por cuarentena (no toca VpnStats). */
    public static final AtomicLong quarantinedDrops = new AtomicLong();
    private static final AtomicBoolean procLogOnce = new AtomicBoolean(false);

    private final HyperionVpnService service;
    private final AtomicInteger ipId = new AtomicInteger(0);

    private static class CacheEntry { byte[] resp; long expiry; }
    private static final Map<String, CacheEntry> CACHE = new HashMap<>();
    private static final Object CACHE_LOCK = new Object();

    public DnsEngine(HyperionVpnService service) { this.service = service; }

    public static void clearCache() {
        synchronized (CACHE_LOCK) { CACHE.clear(); }
    }

    public static int cacheSize() {
        synchronized (CACHE_LOCK) { return CACHE.size(); }
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static long u32(byte[] b, int off) {
        return (((long) (b[off] & 0xFF)) << 24) | (((long) (b[off + 1] & 0xFF)) << 16)
                | (((long) (b[off + 2] & 0xFF)) << 8) | ((b[off + 3] & 0xFF));
    }

    private static void putU16(byte[] b, int off, int v) {
        b[off] = (byte) ((v >> 8) & 0xFF);
        b[off + 1] = (byte) (v & 0xFF);
    }

    private static int ipChecksum(byte[] b, int off, int len) {
        long sum = 0;
        for (int i = 0; i < len; i += 2) {
            sum += ((b[off + i] & 0xFF) << 8) | (b[off + i + 1] & 0xFF);
        }
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum) & 0xFFFF;
    }

    /** Punto de entrada: un paquete IPv4 crudo leído del TUN. */
    public void handlePacket(byte[] pkt, OutputStream tunOut, VpnStats stats) {
        try {
            if (pkt.length < 20) return;
            if (((pkt[0] >> 4) & 0xF) != 4) return;           // solo IPv4
            int ihl = (pkt[0] & 0xF) * 4;
            if (ihl < 20 || pkt.length < ihl + 8) return;
            if ((pkt[9] & 0xFF) != 17) { stats.dropped.incrementAndGet(); return; } // solo UDP
            int dstPort = u16(pkt, ihl + 2);
            if (dstPort != 53) { stats.dropped.incrementAndGet(); return; }         // solo DNS
            int udpLen = u16(pkt, ihl + 4);
            int dnsOff = ihl + 8;
            int dnsLen = udpLen - 8;
            if (dnsLen <= 12 || dnsOff + dnsLen > pkt.length) return;
            byte[] query = Arrays.copyOfRange(pkt, dnsOff, dnsOff + dnsLen);

            stats.queries.incrementAndGet();
            stats.bytesIn.addAndGet(pkt.length);

            // ---- Cuarentena de red por UID (antes de procesar) ----
            int srcPort = u16(pkt, ihl);
            int uid = resolveUid(srcPort);
            if (uid >= 0 && HyperionVpnService.isUidQuarantined(uid)) {
                byte[] nx = buildNxdomainResponse(query);
                byte[] ipPkt = buildIpResponse(pkt, ihl, nx);
                synchronized (tunOut) { tunOut.write(ipPkt); }
                stats.bytesOut.addAndGet(ipPkt.length);
                stats.blocked.incrementAndGet();
                quarantinedDrops.incrementAndGet();
                return;
            }

            ParsedQuery q = parseQuery(query);
            if (q == null) return;

            byte[] dnsResp;
            // Bloqueo vía AdBlocker (listas amplias: ads/trackers/malware/phishing/miners).
            // Fallback a Blocklist si el módulo escudo no estuviera disponible.
            String category = null;
            boolean adBlocked;
            try {
                com.drex.hyperion.blocker.AdBlocker ab =
                        com.drex.hyperion.blocker.AdBlocker.get(service);
                category = ab.categoryOf(q.name);
                adBlocked = category != null;
            } catch (Exception e) {
                adBlocked = Blocklist.isBlocked(q.name);
            }
            if (adBlocked) {
                dnsResp = buildBlockedResponse(query, q.questionLen);
                stats.blocked.incrementAndGet();
            } else {
                dnsResp = resolveWithCache(q, query, stats);
                if (dnsResp == null) return; // sin respuesta del upstream
            }
            // Evento para el escudo: contador en vivo, ahorro de datos, stats por app.
            HyperionVpnService.fireDnsEvent(q.name, uid, adBlocked, category);

            byte[] ipPkt = buildIpResponse(pkt, ihl, dnsResp);
            synchronized (tunOut) { tunOut.write(ipPkt); }
            stats.bytesOut.addAndGet(ipPkt.length);
        } catch (Exception ignored) {}
    }

    /**
     * Resuelve el UID dueño del socket UDP cuyo puerto local es srcPort.
     * Lee /proc/net/udp y /proc/net/udp6 buscando la dirección local
     * 0A080002 (10.8.0.2, la IP del TUN) con ese puerto. Columna uid = índice 9.
     * Devuelve -1 si no se puede resolver (fail-open: el paquete se permite).
     */
    private static int resolveUid(int srcPort) {
        String want = ":" + String.format(Locale.US, "%04X", srcPort & 0xFFFF);
        for (String path : new String[]{"/proc/net/udp", "/proc/net/udp6"}) {
            BufferedReader br = null;
            try {
                br = new BufferedReader(new FileReader(path));
                String line = br.readLine(); // cabecera
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    String[] f = line.split("\\s+");
                    if (f.length < 10) continue;
                    String local = f[1].toUpperCase(Locale.US);
                    // termina en 0A080002:PORT (udp6 trae prefijo de 96 bits)
                    if (local.endsWith(TUN_IP_HEX + want)) {
                        return Integer.parseInt(f[9]);
                    }
                }
            } catch (Exception e) {
                // /proc/net no legible: fail-open, un solo aviso en el log
                if (procLogOnce.compareAndSet(false, true)) {
                    Log.w(TAG, "no se pudo leer " + path + " (fail-open): " + e.getMessage());
                }
                return -1;
            } finally {
                try { if (br != null) br.close(); } catch (Exception ignored) {}
            }
        }
        return -1; // no se resolvió: fail-open
    }

    private static class ParsedQuery {
        String name; int qtype; int questionLen; // bytes desde el inicio de la pregunta
    }

    private ParsedQuery parseQuery(byte[] query) {
        if (u16(query, 4) < 1) return null; // QDCOUNT
        int off = 12;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (off >= query.length) return null;
            int len = query[off++] & 0xFF;
            if (len == 0) break;
            if ((len & 0xC0) != 0) return null; // compresión: no esperado en queries
            if (off + len > query.length) return null;
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(query, off, len, StandardCharsets.US_ASCII)
                    .toLowerCase(Locale.US));
            off += len;
        }
        if (off + 4 > query.length) return null;
        ParsedQuery q = new ParsedQuery();
        q.name = sb.toString();
        q.qtype = u16(query, off);
        q.questionLen = (off + 4) - 12;
        return q;
    }

    /** Respuesta A=0.0.0.0 para dominios bloqueados. */
    private byte[] buildBlockedResponse(byte[] query, int questionLen) {
        byte[] r = new byte[12 + questionLen + 16];
        r[0] = query[0]; r[1] = query[1];          // ID
        r[2] = (byte) 0x81; r[3] = (byte) 0x80;    // QR|RD|RA, RCODE=0
        putU16(r, 4, 1);                            // QDCOUNT
        putU16(r, 6, 1);                            // ANCOUNT
        putU16(r, 8, 0); putU16(r, 10, 0);
        System.arraycopy(query, 12, r, 12, questionLen);
        int a = 12 + questionLen;
        r[a++] = (byte) 0xC0; r[a++] = (byte) 0x0C; // puntero al QNAME
        putU16(r, a, 1); a += 2;                     // TYPE A
        putU16(r, a, 1); a += 2;                     // CLASS IN
        r[a++] = 0; r[a++] = 0; r[a++] = 0; r[a++] = 60; // TTL 60
        putU16(r, a, 4); a += 2;                     // RDLENGTH
        r[a++] = 0; r[a++] = 0; r[a++] = 0; r[a] = 0;    // 0.0.0.0
        return r;
    }

    /** Respuesta NXDOMAIN (RCODE=3) para apps en cuarentena: sin resolución de nombres. */
    private byte[] buildNxdomainResponse(byte[] query) {
        int qlen = query.length - 12;
        byte[] r = new byte[12 + qlen];
        r[0] = query[0]; r[1] = query[1];          // ID
        r[2] = (byte) 0x81; r[3] = (byte) 0x83;    // QR|RD|RA, RCODE=3 (NXDOMAIN)
        putU16(r, 4, 1);                            // QDCOUNT
        putU16(r, 6, 0); putU16(r, 8, 0); putU16(r, 10, 0);
        System.arraycopy(query, 12, r, 12, qlen);
        return r;
    }

    private byte[] resolveWithCache(ParsedQuery q, byte[] query, VpnStats stats) {
        String key = q.name + "/" + q.qtype;
        long now = System.currentTimeMillis();
        synchronized (CACHE_LOCK) {
            CacheEntry e = CACHE.get(key);
            if (e != null && e.expiry > now) {
                byte[] hit = e.resp.clone();
                hit[0] = query[0]; hit[1] = query[1]; // el ID debe coincidir con la query
                stats.cacheHits.incrementAndGet();
                return hit;
            } else if (e != null) {
                CACHE.remove(key); // expirada: no servir TTL vencido
            }
        }
        byte[] resp = forward(query);
        if (resp != null && resp.length >= 12 && (resp[3] & 0x0F) == 0) {
            // TTL real: mínimo TTL de las respuestas (answer section del upstream)
            long ttlSec = minAnswerTtl(resp);
            if (ttlSec > 0) {
                CacheEntry e = new CacheEntry();
                e.resp = resp;
                e.expiry = now + Math.min(ttlSec * 1000L, MAX_CACHE_TTL_MS);
                synchronized (CACHE_LOCK) {
                    if (CACHE.size() > 2000) CACHE.clear();
                    CACHE.put(key, e);
                }
            }
        }
        return resp;
    }

    /** Longitud en bytes del nombre DNS en off (soporta punteros de compresión). -1 si malformado. */
    private static int dnsNameLen(byte[] b, int off) {
        int start = off, jumps = 0;
        while (true) {
            if (off >= b.length) return -1;
            int len = b[off] & 0xFF;
            if ((len & 0xC0) == 0xC0) { // puntero: 2 bytes y termina
                if (off + 1 >= b.length) return -1;
                int ptr = ((len & 0x3F) << 8) | (b[off + 1] & 0xFF);
                if (ptr >= b.length || ++jumps > 8) return -1;
                return (off - start) + 2;
            }
            if (len == 0) return (off - start) + 1;
            off += 1 + len;
            if (off > b.length) return -1;
        }
    }

    /** Mínimo TTL (segundos) de la sección ANSWER. -1 si no hay respuestas parseables. */
    private static long minAnswerTtl(byte[] resp) {
        try {
            int qd = u16(resp, 4), an = u16(resp, 6);
            int off = 12;
            for (int i = 0; i < qd; i++) {
                int nl = dnsNameLen(resp, off);
                if (nl < 0) return -1;
                off += nl + 4; // nombre + QTYPE + QCLASS
                if (off > resp.length) return -1;
            }
            long min = -1;
            for (int i = 0; i < an; i++) {
                int nl = dnsNameLen(resp, off);
                if (nl < 0) return -1;
                off += nl;
                if (off + 10 > resp.length) return -1;
                long ttl = u32(resp, off + 4);
                int rdlen = u16(resp, off + 8);
                off += 10 + rdlen;
                if (off > resp.length) return -1;
                if (min < 0 || ttl < min) min = ttl;
            }
            return min;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Reenvía la query cruda al upstream (socket protegido: no pasa por el TUN).
     * Reconexión limpia: failover 1.1.1.1 -> 8.8.8.8, socket siempre cerrado.
     */
    private byte[] forward(byte[] query) {
        for (String upstream : UPSTREAMS) {
            DatagramSocket s = null;
            try {
                s = new DatagramSocket();
                service.protect(s);
                s.setSoTimeout(3000);
                InetAddress addr = InetAddress.getByName(upstream);
                s.send(new DatagramPacket(query, query.length, addr, 53));
                byte[] buf = new byte[4096];
                DatagramPacket rp = new DatagramPacket(buf, buf.length);
                s.receive(rp);
                return Arrays.copyOf(rp.getData(), rp.getLength());
            } catch (Exception ignored) {
                // probar siguiente upstream
            } finally {
                if (s != null) s.close();
            }
        }
        return null;
    }

    /** Construye el paquete IPv4+UDP de vuelta hacia el TUN. */
    private byte[] buildIpResponse(byte[] orig, int ihl, byte[] dnsResp) {
        byte[] p = new byte[20 + 8 + dnsResp.length];
        p[0] = 0x45; p[1] = 0;
        putU16(p, 2, p.length);
        putU16(p, 4, ipId.incrementAndGet() & 0xFFFF);
        p[6] = 0x40; p[7] = 0;   // DF
        p[8] = 64; p[9] = 17;     // TTL, UDP
        System.arraycopy(orig, 16, p, 12, 4); // src = dst original
        System.arraycopy(orig, 12, p, 16, 4); // dst = src original
        putU16(p, 10, ipChecksum(p, 0, 20));
        putU16(p, 20, 53);
        putU16(p, 22, u16(orig, ihl)); // puerto origen original
        putU16(p, 24, 8 + dnsResp.length);
        putU16(p, 26, 0); // checksum UDP 0 = deshabilitado (legal en IPv4)
        System.arraycopy(dnsResp, 0, p, 28, dnsResp.length);
        return p;
    }
}
