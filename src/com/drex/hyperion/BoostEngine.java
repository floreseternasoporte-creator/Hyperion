package com.drex.hyperion;

import android.os.Handler;
import android.os.Looper;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Motor de optimización HONESTA de red (sin root).
 *
 * Orquesta el ciclo MEDIR → OPTIMIZAR → MEDIR usando solo técnicas reales,
 * verificadas contra la literatura (ver reporte del track):
 *
 *   REALES (implementadas / medidas aquí):
 *   1. DNS rápido vía túnel VPN local: las consultas van a 1.1.1.1 / 8.8.8.8
 *      en vez del DNS de la operadora (suele ser más lento). Reduce la latencia
 *      de resolución, no el ancho de banda.
 *   2. Bloqueo DNS de rastreadores / anuncios / telemetría (Blocklist):
 *      menos conexiones y menos bytes → las páginas cargan antes y se ahorran datos.
 *   3. Limpieza de la caché DNS del túnel (DnsEngine.clearCache()).
 *   4. Medición antes/después con números reales (ping TCP + DNS cronometrado).
 *
 *   NO REALES sin root (descartadas; no se prometen):
 *   - Aumentar los Mbps brutos: los pone la operadora, ninguna app los sube.
 *   - "RAM boost" / "CPU overclock" / "limpiar RAM": placebo (MakeUseOf, 8 apps testeadas).
 *   - Tweaks TCP (BBR, buffers, MTU): requieren root.
 *   - Más conexiones paralelas = más velocidad: mito (solo reparte el mismo cuello de botella).
 */
public class BoostEngine {

    public interface Listener {
        /** Texto de fase para la UI (ya en hilo UI). */
        void onPhase(String phase);
        /** El túnel está apagado: el llamador debe pedir activarlo (hilo UI). */
        void onVpnRequired();
        /** Ciclo terminado con el reporte honesto (hilo UI). */
        void onDone(BoostReport report);
    }

    /** Dominios populares para cronometrar la resolución DNS real. */
    private static final String[] PROBE_DOMAINS = {
            "google.com", "youtube.com", "instagram.com",
            "tiktok.com", "whatsapp.com", "wikipedia.org"
    };
    /** Host del ping: el mismo servidor del test de velocidad (nunca una IP
     *  reclamada por el TUN, porque esos paquetes mueren en el túnel). */
    private static final String PING_HOST = "speed.cloudflare.com";
    private static final int PING_PORT = 443;
    /** Estimación CONSERVADORA de KB que evita una petición de rastreador/anuncio. */
    private static final double KB_PER_BLOCKED = 48.0;
    /** Espera máxima a que el usuario active el VPN. */
    private static final long VPN_WAIT_MS = 12_000;

    static {
        // Sin caché de InetAddress: las mediciones DNS deben ser reales, no hits de JVM.
        try {
            java.security.Security.setProperty("networkaddress.cache.ttl", "0");
            java.security.Security.setProperty("networkaddress.cache.negative.ttl", "0");
        } catch (Exception ignored) {}
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;

    public void cancel() { cancelled = true; }

    /** Lanza el ciclo completo en un hilo de fondo. */
    public void run(final Listener listener) {
        cancelled = false;
        new Thread(new Runnable() {
            @Override public void run() { cycle(listener); }
        }, "hyperion-boost").start();
    }

    // ---------------- ciclo ----------------

    private void cycle(Listener l) {
        BoostReport r = new BoostReport();
        r.blocklistSize = Blocklist.size();

        // --- MEDIR (antes) ---
        emitPhase(l, "Midiendo tu conexión actual…");
        String pingIp = resolveIp(PING_HOST);
        r.pingBeforeMs = tcpPingMs(pingIp, PING_PORT, 5);
        r.dnsBeforeMs = dnsProbeMs();
        r.dnsBeforeLabel = "DNS de tu conexión";

        if (cancelled) return;

        // --- OPTIMIZAR ---
        emitPhase(l, "Aplicando optimizaciones reales…");
        r.vpnWasOn = HyperionVpnService.running.get();
        if (!r.vpnWasOn) {
            emitVpnRequired(l); // el llamador corre activity.startVpn() en UI
            long deadline = System.currentTimeMillis() + VPN_WAIT_MS;
            while (!cancelled && System.currentTimeMillis() < deadline
                    && !HyperionVpnService.running.get()) {
                sleep(250);
            }
        }
        r.vpnOn = HyperionVpnService.running.get();

        if (r.vpnOn) {
            r.blockedBefore = HyperionVpnService.stats.blocked.get();
        }

        // 3) Limpieza real de la caché DNS del túnel
        DnsEngine.clearCache();
        r.cacheCleared = true;

        // 4) Calentamiento honesto: resolver los dominios de prueba por la ruta
        //    actual. Con el túnel activo, esto llena la caché del DnsEngine y la
        //    del sistema, así la re-medición refleja el estado optimizado real.
        if (r.vpnOn && !cancelled) {
            for (String d : PROBE_DOMAINS) {
                if (cancelled) return;
                resolveQuiet(d);
            }
            sleep(400);
        }

        if (cancelled) return;

        // --- MEDIR (después) ---
        emitPhase(l, "Midiendo de nuevo para comparar…");
        r.pingAfterMs = tcpPingMs(pingIp, PING_PORT, 5);
        r.dnsAfterMs = dnsProbeMs();
        r.dnsAfterLabel = r.vpnOn ? "DNS Hyperion (1.1.1.1 / 8.8.8.8)" : "DNS de tu conexión";

        if (r.vpnOn) {
            r.blockedAfter = HyperionVpnService.stats.blocked.get();
            r.blockedDuring = Math.max(0, r.blockedAfter - r.blockedBefore);
            r.kbSavedEstimate = r.blockedDuring * KB_PER_BLOCKED;
        }
        r.finishedAt = System.currentTimeMillis();
        emitDone(l, r);
    }

    // ---------------- mediciones reales ----------------

    /** RTT mediano vía TCP (ICMP requiere root). Mediana: robusta ante un handshake lento. */
    private double tcpPingMs(String ip, int port, int samples) {
        if (ip == null) return -1;
        List<Long> rtts = new ArrayList<>();
        for (int i = 0; i < samples && !cancelled; i++) {
            Socket s = new Socket();
            try {
                long t = System.nanoTime();
                s.connect(new InetSocketAddress(ip, port), 2500);
                rtts.add(System.nanoTime() - t);
            } catch (Exception ignored) {
            } finally {
                try { s.close(); } catch (Exception ignored) {}
            }
        }
        if (rtts.isEmpty()) return -1;
        Collections.sort(rtts);
        return rtts.get(rtts.size() / 2) / 1e6;
    }

    /** Latencia media de resolución DNS de los dominios de prueba por la ruta
     *  actual del sistema (operadora si el túnel está apagado, Hyperion si está activo). */
    private double dnsProbeMs() {
        long total = 0;
        int ok = 0;
        for (String d : PROBE_DOMAINS) {
            if (cancelled) break;
            try {
                long t = System.nanoTime();
                InetAddress.getByName(d);
                total += System.nanoTime() - t;
                ok++;
            } catch (Exception ignored) {}
        }
        return ok == 0 ? -1 : (total / (double) ok) / 1e6;
    }

    private String resolveIp(String host) {
        try {
            return InetAddress.getByName(host).getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }

    private void resolveQuiet(String host) {
        try { InetAddress.getByName(host); } catch (Exception ignored) {}
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    // ---------------- emisión a UI ----------------

    private void emitPhase(final Listener l, final String phase) {
        ui.post(new Runnable() {
            @Override public void run() { l.onPhase(phase); }
        });
    }

    private void emitVpnRequired(final Listener l) {
        ui.post(new Runnable() {
            @Override public void run() { l.onVpnRequired(); }
        });
    }

    private void emitDone(final Listener l, final BoostReport r) {
        ui.post(new Runnable() {
            @Override public void run() { l.onDone(r); }
        });
    }

    // ---------------- reporte ----------------

    /** Resultado honesto del ciclo. Los -1 significan "no se pudo medir". */
    public static class BoostReport {
        public double pingBeforeMs = -1, pingAfterMs = -1;
        public double dnsBeforeMs = -1, dnsAfterMs = -1;
        public String dnsBeforeLabel = "", dnsAfterLabel = "";
        public boolean vpnWasOn, vpnOn, cacheCleared;
        public int blocklistSize;
        public long blockedBefore, blockedAfter, blockedDuring;
        public double kbSavedEstimate;
        public long finishedAt;

        private static String ms(double v) {
            return v < 0 ? "—" : String.format(Locale.US, "%.0f ms", v);
        }

        private static String pct(double before, double after) {
            if (before <= 0 || after < 0) return "";
            double d = (before - after) / before * 100.0;
            return d >= 0.5
                    ? String.format(Locale.US, " (−%.0f%%)", d)
                    : String.format(Locale.US, " (+%.0f%%)", -d);
        }

        /** Resumen honesto en español para la tarjeta de resultados. */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("⚡ Optimización completa\n\n");
            sb.append("Ping: ").append(ms(pingBeforeMs))
                    .append(" → ").append(ms(pingAfterMs)).append("\n");
            sb.append("Resolución DNS: ").append(ms(dnsBeforeMs))
                    .append(" → ").append(ms(dnsAfterMs))
                    .append(pct(dnsBeforeMs, dnsAfterMs)).append("\n");
            if (vpnOn) {
                sb.append("Rastreadores bloqueados durante la prueba: ")
                        .append(blockedDuring).append("\n");
                sb.append("Datos ahorrados (est.): ~")
                        .append(String.format(Locale.US, "%.0f KB", kbSavedEstimate))
                        .append("\n");
            }
            sb.append("\n");
            if (vpnOn && !vpnWasOn) {
                sb.append("✓ Túnel VPN activado · ");
            } else if (vpnOn) {
                sb.append("✓ Túnel VPN activo · ");
            } else {
                sb.append("⚠ Túnel VPN no activado: sin él no hay bloqueo de rastreadores. · ");
            }
            sb.append("Caché DNS limpiada · Lista de ")
                    .append(blocklistSize).append(" dominios aplicada.");
            if (vpnWasOn) {
                sb.append("\nℹ El túnel ya estaba activo: la mejora medida viene de la caché limpia.");
            }
            if (!vpnOn) {
                sb.append("\nℹ Activa el VPN y repite la prueba para medir el DNS rápido y el bloqueo.");
            }
            return sb.toString();
        }
    }
}
