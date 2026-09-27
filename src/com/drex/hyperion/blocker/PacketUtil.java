package com.drex.hyperion.blocker;

/**
 * PacketUtil — utilidades PURAS de paquetes IP/TCP/DNS para Hyperion 2.2.
 *
 * <p>Sin dependencias Android (testeable con javac en cualquier máquina).
 * Contiene:</p>
 * <ul>
 *   <li>Lista de IPs de proveedores DoH/DoT conocidos ({@link #DOH_IPS}) y de
 *       sus dominios bootstrap ({@link #DOH_DOMAINS}). Los dominios también
 *       viven en {@code res/raw/doh.txt} (los carga {@link AdBlocker}) y en
 *       {@link com.drex.hyperion.Blocklist#DOH_DOMAINS} (fallback sin Android).
 *       La FUENTE DE VERDAD en código es este array; el test unitario
 *       {@code tools/DnsBlockTest.java} verifica que las tres copias coinciden.</li>
 *   <li>{@link #buildTcpRst(byte[])}: fabrica un RST+ACK inmediato para un SYN
 *       TCP dirigido a una IP DoH/DoT en puerto 443/853. Sin esto, esos paquetes
 *       se descartaban en silencio y el navegador tardaba minutos en hacer
 *       fallback al DNS del sistema (o nunca, si su DoH no tiene fallback).</li>
 *   <li>Constructores de respuestas DNS de bloqueo (A=0.0.0.0, AAAA=NODATA,
 *       NXDOMAIN para bootstrap DoH).</li>
 * </ul>
 */
public final class PacketUtil {
    private PacketUtil() {}

    // ------------------------------------------------------------------
    // Proveedores DoH/DoT conocidos — IPs (network byte order, como int)
    // ------------------------------------------------------------------
    // Google 8.8.8.8/8.8.4.4 · Cloudflare 1.1.1.1/1.0.0.1 · Quad9 9.9.9.9/
    // 149.112.112.112 · OpenDNS 208.67.222.222/208.67.220.220 ·
    // AdGuard 94.140.14.14/94.140.15.15 · CleanBrowsing 185.228.168.168/
    // 185.228.169.168
    public static final int[] DOH_IPS = {
            0x08080808, 0x08080404,             // Google
            0x01010101, 0x01000001,             // Cloudflare
            0x09090909, 0x95707070,             // Quad9 (9.9.9.9, 149.112.112.112)
            0xD043DEDE, 0xD043DCDC,             // OpenDNS
            0x5E8C0E0E, 0x5E8C0F0F,             // AdGuard
            0xB9E4A8A8, 0xB9E4A9A8,             // CleanBrowsing
    };

    public static boolean isDohIp(int ipNetworkOrder) {
        for (int ip : DOH_IPS) if (ip == ipNetworkOrder) return true;
        return false;
    }

    // ------------------------------------------------------------------
    // Proveedores DoH/DoT conocidos — dominios bootstrap
    // ------------------------------------------------------------------
    // FUENTE DE VERDAD en código (duplicada en res/raw/doh.txt y verificada
    // por el test). Coincidencia por sufijo: "dns.google" también bloquea
    // "www.dns.google", pero NO "dnsgoogle.com".
    public static final String[] DOH_DOMAINS = {
            "dns.google",
            "dns.google.com",
            "cloudflare-dns.com",
            "1dot1dot1dot1.cloudflare-dns.com",
            "dns.quad9.net",
            "dns10.quad9.net",
            "doh.opendns.com",
            "doh.umbrella.com",
            "dns.adguard-dns.com",
            "dns.adguard.com",
            "doh.cleanbrowsing.com",
            "doh.controld.com",
            "dns.nextdns.io",
    };

    /** true si el dominio (o su padre) es un bootstrap DoH/DoT conocido. */
    public static boolean isDohDomain(String domain) {
        if (domain == null) return false;
        String d = domain.toLowerCase(java.util.Locale.US);
        if (d.endsWith(".")) d = d.substring(0, d.length() - 1);
        for (String base : DOH_DOMAINS) {
            if (d.equals(base) || d.endsWith("." + base)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Primitivas de bytes
    // ------------------------------------------------------------------
    public static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    public static long u32(byte[] b, int off) {
        return (((long) (b[off] & 0xFF)) << 24) | (((long) (b[off + 1] & 0xFF)) << 16)
                | (((long) (b[off + 2] & 0xFF)) << 8) | ((b[off + 3] & 0xFF));
    }

    public static void putU16(byte[] b, int off, int v) {
        b[off] = (byte) ((v >> 8) & 0xFF);
        b[off + 1] = (byte) (v & 0xFF);
    }

    public static void putU32(byte[] b, int off, long v) {
        b[off] = (byte) ((v >> 24) & 0xFF);
        b[off + 1] = (byte) ((v >> 16) & 0xFF);
        b[off + 2] = (byte) ((v >> 8) & 0xFF);
        b[off + 3] = (byte) (v & 0xFF);
    }

    public static int ipChecksum(byte[] b, int off, int len) {
        long sum = 0;
        for (int i = 0; i < len; i += 2) {
            sum += ((b[off + i] & 0xFF) << 8) | (b[off + i + 1] & 0xFF);
        }
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum) & 0xFFFF;
    }

    /** Checksum TCP (20 bytes) con pseudo-header IPv4. p = IP(20)+TCP(20). */
    static int tcpChecksum(byte[] p) {
        long sum = 0;
        sum += u16(p, 12); sum += u16(p, 14);   // IP origen
        sum += u16(p, 16); sum += u16(p, 18);   // IP destino
        sum += (p[9] & 0xFF);                    // protocolo (6)
        sum += 20;                               // longitud TCP
        for (int i = 20; i < 40; i += 2) sum += u16(p, i);
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum) & 0xFFFF;
    }

    // ------------------------------------------------------------------
    // RST inmediato para SYN a DoH/DoT
    // ------------------------------------------------------------------
    /**
     * Si {@code pkt} es un SYN TCP IPv4 dirigido a una IP de {@link #DOH_IPS}
     * en puerto 443 (DoH) u 853 (DoT), devuelve un paquete IPv4+TCP RST+ACK
     * listo para escribir al TUN (40 bytes, checksums correctos).
     * En cualquier otro caso devuelve {@code null} (el llamador lo descarta).
     *
     * <p>El RST convierte un stall silencioso de minutos en un "connection
     * refused" instantáneo: el navegador/app hace fallback inmediato al DNS
     * del sistema (nuestro filtro) en vez de colgarse.</p>
     */
    public static byte[] buildTcpRst(byte[] pkt) {
        if (pkt == null || pkt.length < 20) return null;
        if (((pkt[0] >> 4) & 0xF) != 4) return null;          // solo IPv4
        int ihl = (pkt[0] & 0xF) * 4;
        if (ihl < 20 || pkt.length < ihl + 20) return null;
        if ((pkt[9] & 0xFF) != 6) return null;                // solo TCP
        int srcPort = u16(pkt, ihl);
        int dstPort = u16(pkt, ihl + 2);
        if (dstPort != 443 && dstPort != 853) return null;    // DoH / DoT
        if (!isDohIp((int) u32(pkt, 16))) return null;
        int flags = pkt[ihl + 13] & 0xFF;
        if ((flags & 0x02) == 0) return null;                // solo SYN
        long seq = u32(pkt, ihl + 4);

        byte[] r = new byte[40];
        r[0] = 0x45; r[1] = 0;
        putU16(r, 2, 40);
        putU16(r, 4, 0x5A5A);
        r[6] = 0x40; r[7] = 0;                               // DF
        r[8] = 64; r[9] = 6;                                 // TTL, TCP
        System.arraycopy(pkt, 16, r, 12, 4);                 // src = dst orig
        System.arraycopy(pkt, 12, r, 16, 4);                 // dst = src orig
        putU16(r, 10, 0);
        putU16(r, 10, ipChecksum(r, 0, 20));
        // TCP: puertos invertidos, SEQ=0, ACK=seq+1, RST|ACK
        putU16(r, 20, dstPort);
        putU16(r, 22, srcPort);
        putU32(r, 24, 0);
        putU32(r, 28, seq + 1);
        r[32] = 0x50;                                        // data offset 5
        r[33] = 0x14;                                        // RST|ACK
        putU16(r, 34, 0x4000);                               // window
        putU16(r, 36, 0);
        putU16(r, 38, 0);
        putU16(r, 36, tcpChecksum(r));
        return r;
    }

    // ------------------------------------------------------------------
    // Respuestas DNS de bloqueo
    // ------------------------------------------------------------------
    /**
     * Respuesta de bloqueo honesta según qtype:
     * <ul>
     *   <li>A (1) → un registro A con 0.0.0.0 (sinkhole clásico).</li>
     *   <li>AAAA (28) y resto → NODATA (NOERROR sin respuestas). Responder un
     *       registro A a una consulta AAAA es anómalo y algunos resolvers lo
     *       descartan como malformado.</li>
     * </ul>
     */
    public static byte[] buildBlockedResponse(byte[] query, int qtype, int questionLen) {
        if (qtype == 1) {
            byte[] r = new byte[12 + questionLen + 16];
            r[0] = query[0]; r[1] = query[1];                // ID
            r[2] = (byte) 0x81; r[3] = (byte) 0x80;          // QR|RD|RA, RCODE=0
            putU16(r, 4, 1);                                 // QDCOUNT
            putU16(r, 6, 1);                                 // ANCOUNT
            putU16(r, 8, 0); putU16(r, 10, 0);
            System.arraycopy(query, 12, r, 12, questionLen);
            int a = 12 + questionLen;
            r[a++] = (byte) 0xC0; r[a++] = (byte) 0x0C;      // puntero al QNAME
            putU16(r, a, 1); a += 2;                          // TYPE A
            putU16(r, a, 1); a += 2;                          // CLASS IN
            r[a++] = 0; r[a++] = 0; r[a++] = 0; r[a++] = 60;  // TTL 60
            putU16(r, a, 4); a += 2;                          // RDLENGTH
            r[a++] = 0; r[a++] = 0; r[a++] = 0; r[a] = 0;     // 0.0.0.0
            return r;
        }
        byte[] r = new byte[12 + questionLen];
        r[0] = query[0]; r[1] = query[1];
        r[2] = (byte) 0x81; r[3] = (byte) 0x80;              // NOERROR, sin respuestas
        putU16(r, 4, 1);
        putU16(r, 6, 0); putU16(r, 8, 0); putU16(r, 10, 0);
        System.arraycopy(query, 12, r, 12, questionLen);
        return r;
    }

    /** NXDOMAIN (RCODE=3): se usa para bootstrap DoH/DoT y cuarentena. */
    public static byte[] buildNxdomainResponse(byte[] query) {
        int qlen = query.length - 12;
        byte[] r = new byte[12 + qlen];
        r[0] = query[0]; r[1] = query[1];                    // ID
        r[2] = (byte) 0x81; r[3] = (byte) 0x83;              // QR|RD|RA, RCODE=3
        putU16(r, 4, 1);                                     // QDCOUNT
        putU16(r, 6, 0); putU16(r, 8, 0); putU16(r, 10, 0);
        System.arraycopy(query, 12, r, 12, qlen);
        return r;
    }
}
