package com.drex.hyperion;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.Locale;
import java.util.Random;

/** Controlador de velocidad: ping + bajada + subida reales, y optimización. */
public class SpeedController {
    private final MainActivity activity;
    private final View root;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean testing;

    private SpeedGaugeView gauge;
    private TextView phase, pingV, downV, upV, optimizeText;
    private TextView boostDnsValue, boostBlockValue, boostCacheValue;
    private LinearLayout optimizeCard;
    private View btnMeasure, btnOptimize;
    private volatile BoostEngine boost;

    public SpeedController(MainActivity activity, View root) {
        this.activity = activity;
        this.root = root;
        gauge = root.findViewById(R.id.speed_gauge);
        phase = root.findViewById(R.id.speed_phase);
        pingV = root.findViewById(R.id.speed_ping);
        downV = root.findViewById(R.id.speed_down);
        upV = root.findViewById(R.id.speed_up);
        btnMeasure = root.findViewById(R.id.btn_measure);
        btnOptimize = root.findViewById(R.id.btn_optimize);
        optimizeCard = root.findViewById(R.id.optimize_card);
        optimizeText = root.findViewById(R.id.optimize_text);
        boostDnsValue = root.findViewById(R.id.boost_dns_value);
        boostBlockValue = root.findViewById(R.id.boost_block_value);
        boostCacheValue = root.findViewById(R.id.boost_cache_value);
        // Números reales desde el primer momento: tamaño de la lista de bloqueo.
        boostBlockValue.setText(Blocklist.size() + " dominios");

        btnMeasure.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startTest(); }
        });
        btnOptimize.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { optimize(); }
        });
        pressAnim(btnMeasure); pressAnim(btnOptimize);
    }

    private void pressAnim(View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View vv, android.view.MotionEvent e) {
                if (e.getAction() == android.view.MotionEvent.ACTION_DOWN)
                    vv.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start();
                else if (e.getAction() == android.view.MotionEvent.ACTION_UP
                        || e.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                    vv.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
                return false;
            }
        });
    }

    public void destroy() {
        testing = false;
        BoostEngine b = boost;
        if (b != null) b.cancel();
        handler.removeCallbacksAndMessages(null);
    }

    // ---------- medición ----------

    private void startTest() {
        if (testing) return;
        testing = true;
        gauge.reset();
        setPhase("Midiendo ping…");
        new Thread(new Runnable() {
            @Override public void run() {
                final double ping = measurePing(4);
                post(new Runnable() {
                    @Override public void run() {
                        pingV.setText(ping < 0 ? "—" : String.format(Locale.US, "%.0f", ping));
                        setPhase("Midiendo descarga…");
                    }
                });
                final double down = measureDownload();
                post(new Runnable() {
                    @Override public void run() {
                        downV.setText(down < 0 ? "—" : String.format(Locale.US, "%.1f", down));
                        gauge.setSpeed((float) Math.max(0, down));
                        setPhase("Midiendo subida…");
                    }
                });
                final double up = measureUpload();
                post(new Runnable() {
                    @Override public void run() {
                        upV.setText(up < 0 ? "—" : String.format(Locale.US, "%.1f", up));
                        setPhase("Medición completa");
                        testing = false;
                    }
                });
            }
        }).start();
    }

    /** RTT mediano vía TCP a speed.cloudflare.com:443 (ICMP requiere root).
     *  Mediana en vez de media: un solo handshake lento ya no sesga el resultado.
     *  OJO: nunca pinguear 1.1.1.1/8.8.8.8 con el túnel activo — el TUN reclama
     *  esas IPs y el paquete muere ahí (antes devolvía timeouts falsos). */
    private double measurePing(int samples) {
        String ip = null;
        try {
            ip = java.net.InetAddress.getByName("speed.cloudflare.com").getHostAddress();
        } catch (Exception ignored) {}
        if (ip == null) return -1;
        java.util.List<Long> rtts = new java.util.ArrayList<>();
        for (int i = 0; i < samples && testing; i++) {
            Socket s = new Socket();
            try {
                long t = System.nanoTime();
                s.connect(new InetSocketAddress(ip, 443), 2500);
                rtts.add(System.nanoTime() - t);
            } catch (Exception ignored) {
            } finally { try { s.close(); } catch (Exception ignored) {} }
        }
        if (rtts.isEmpty()) return -1;
        java.util.Collections.sort(rtts);
        return rtts.get(rtts.size() / 2) / 1e6;
    }

    private double measureDownload() {
        HttpURLConnection c = null;
        try {
            // nocache: evita que un proxy/caché intermedia infle la cifra con bytes viejos.
            URL url = new URL("https://speed.cloudflare.com/__down?bytes=25000000&nocache="
                    + System.nanoTime());
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(15000);
            c.connect();
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) return -1;
            InputStream in = c.getInputStream();
            byte[] buf = new byte[65536];
            long bytes = 0;
            long start = System.nanoTime();
            long lastUi = 0;
            int n;
            while (testing && (n = in.read(buf)) > 0) {
                bytes += n;
                long el = System.nanoTime() - start;
                if (el > 12_000_000_000L) break; // tope 12 s
                if (el - lastUi > 300_000_000L) {
                    lastUi = el;
                    final double mbps = bytes * 8.0 / (el / 1e9) / 1e6;
                    post(new Runnable() {
                        @Override public void run() { gauge.setSpeed((float) mbps); }
                    });
                }
            }
            in.close();
            double secs = (System.nanoTime() - start) / 1e9;
            return secs <= 0 ? -1 : bytes * 8.0 / secs / 1e6;
        } catch (Exception e) { return -1; }
        finally { if (c != null) c.disconnect(); }
    }

    private double measureUpload() {
        HttpURLConnection c = null;
        try {
            URL url = new URL("https://speed.cloudflare.com/__up");
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(15000);
            c.setDoOutput(true); c.setRequestMethod("POST");
            c.setChunkedStreamingMode(65536);
            c.connect();
            OutputStream out = c.getOutputStream();
            byte[] chunk = new byte[65536];
            new Random().nextBytes(chunk);
            long bytes = 0;
            long start = System.nanoTime();
            long lastUi = 0;
            while (testing && System.nanoTime() - start < 8_000_000_000L) {
                out.write(chunk);
                bytes += chunk.length;
                long el = System.nanoTime() - start;
                if (el - lastUi > 300_000_000L) {
                    lastUi = el;
                    final double mbps = bytes * 8.0 / (el / 1e9) / 1e6;
                    post(new Runnable() {
                        @Override public void run() { gauge.setSpeed((float) mbps); }
                    });
                }
            }
            out.flush(); out.close();
            int code = c.getResponseCode();
            double secs = (System.nanoTime() - start) / 1e9;
            return (code >= 200 && code < 300 && secs > 0) ? bytes * 8.0 / secs / 1e6 : -1;
        } catch (Exception e) { return -1; }
        finally { if (c != null) c.disconnect(); }
    }

    // ---------- optimización honesta (MEDIR → OPTIMIZAR → MEDIR) ----------

    private void optimize() {
        if (testing) return;
        testing = true;
        optimizeCard.setVisibility(View.GONE);
        boost = new BoostEngine();
        boost.run(new BoostEngine.Listener() {
            @Override public void onPhase(final String p) {
                post(new Runnable() {
                    @Override public void run() { setPhase(p); }
                });
            }
            @Override public void onVpnRequired() {
                // Ya estamos en hilo UI: pide el permiso + arranque del túnel.
                // BoostEngine espera hasta 12 s a que HyperionVpnService.running se active.
                activity.startVpn();
                Toast.makeText(activity,
                        "Activa el VPN para la optimización completa",
                        Toast.LENGTH_LONG).show();
            }
            @Override public void onDone(final BoostEngine.BoostReport r) {
                post(new Runnable() {
                    @Override public void run() { showBoostReport(r); }
                });
            }
        });
    }

    private void showBoostReport(BoostEngine.BoostReport r) {
        testing = false;
        setPhase("Optimización completa");
        optimizeText.setText(r.summary());
        optimizeCard.setVisibility(View.VISIBLE);
        optimizeCard.setAlpha(0f); optimizeCard.setTranslationY(24f);
        optimizeCard.animate().alpha(1f).translationY(0f)
                .setDuration(450).start();

        // Las tarjetas de técnicas muestran los números reales medidos.
        if (r.dnsAfterMs >= 0) {
            boostDnsValue.setText(String.format(Locale.US, "%.0f ms",
                    r.dnsAfterMs));
        }
        if (r.vpnOn) {
            boostBlockValue.setText(r.blockedDuring + " bloqueados");
        } else {
            boostBlockValue.setText(Blocklist.size() + " dominios");
        }
        boostCacheValue.setText("limpia");
        if (r.pingAfterMs >= 0) {
            pingV.setText(String.format(Locale.US, "%.0f", r.pingAfterMs));
        }
    }

    private void setPhase(String s) { phase.setText(s); }
    private void post(Runnable r) { handler.post(r); }
}
