package com.drex.hyperion;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.drex.hyperion.ovpn.ConnectionStatus;
import com.drex.hyperion.ovpn.OvpnConnector;
import com.drex.hyperion.ovpn.OvpnVpnService;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Controlador de la pantalla VPN: modo local + túneles OpenVPN reales (VPNGate). */
public class VpnController {
    private final MainActivity activity;
    private final View root;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean visible;

    private LaunchButton button;
    private PulseRingView pulse;
    private ParticleBurstView burst;
    private TextView status, hint, time, queries, blocked, data;
    private LinearLayout historyBox;
    private Button disconnectBanner;
    private View localDot;
    private TextView localBadge;
    private LinearLayout countriesBox;

    private List<VpnGateClient.CountryGroup> groups;
    private boolean loadingServers;
    private String remoteErrorShownFor = "";
    /**
     * Barrido Hyperion 2.2: stopService() es asíncrono — entre el tap de
     * DESCONECTAR y onDestroy(), el poll re-encendía el botón (parpadeo
     * "Protegido" fantasma). Este flag lo inhibe hasta que se confirma la caída.
     */
    private volatile boolean disconnecting;

    public VpnController(MainActivity activity, LayoutInflater inflater, View root) {
        this.activity = activity;
        this.root = root;
        button = root.findViewById(R.id.vpn_button);
        pulse = root.findViewById(R.id.vpn_pulse);
        burst = root.findViewById(R.id.vpn_burst);
        status = root.findViewById(R.id.vpn_status);
        hint = root.findViewById(R.id.vpn_hint);
        time = root.findViewById(R.id.vpn_time);
        queries = root.findViewById(R.id.vpn_queries);
        blocked = root.findViewById(R.id.vpn_blocked);
        data = root.findViewById(R.id.vpn_data);
        historyBox = root.findViewById(R.id.vpn_history);
        disconnectBanner = root.findViewById(R.id.vpn_disconnect);
        localDot = root.findViewById(R.id.vpn_local_dot);
        localBadge = root.findViewById(R.id.vpn_local_badge);
        countriesBox = root.findViewById(R.id.vpn_countries);

        button.setListener(new LaunchButton.Listener() {
            @Override public void onTap() { onButtonTap(); }
        });
        // Banner grande de desconexión: detiene el modo que esté activo
        disconnectBanner.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { disconnect(); }
        });
        // Tarjeta local: toca para conectar cuando está inactiva
        root.findViewById(R.id.vpn_local_card).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!HyperionVpnService.running.get() && !OvpnConnector.isRunning()) connect();
            }
        });
        root.findViewById(R.id.vpn_refresh).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { loadServers(true); }
        });
        // "Acerca de": muestra el texto completo de la licencia GPL de ics-openvpn
        root.findViewById(R.id.vpn_attribution).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showLicense(); }
        });
        loadServers(false);
        refreshHistory();
        poll.run();
    }

    private void connect() {
        button.playLaunch(new Runnable() {
            @Override public void run() { activity.startVpn(); }
        });
    }

    private void disconnect() {
        disconnecting = true;
        activity.stopVpn(); // detiene el modo activo (local o remoto)
        button.setConnected(false);
    }

    /** El usuario canceló el diálogo de permiso VPN: soltar el botón
     *  (si no, LaunchButton.launching quedaba true para siempre y el botón
     *  moría: cada tap se tragaba en onTouchEvent). */
    public void cancelLaunch() {
        try { button.setConnected(false); } catch (Exception ignored) {}
    }

    private void onButtonTap() {
        if (HyperionVpnService.running.get() || OvpnConnector.isRunning()) {
            disconnect();
        } else {
            connect();
        }
    }

    // ---------- servidores VPNGate ----------

    private void loadServers(final boolean force) {
        if (loadingServers) return;
        loadingServers = true;
        final Context ctx = activity;
        countriesBox.removeAllViews();
        TextView t = new TextView(ctx);
        t.setText(force ? "Actualizando lista de servidores…" : "Cargando servidores VPNGate…");
        t.setTextColor(0xFF8A90B8); t.setTextSize(13);
        t.setPadding(0, 8, 0, 8);
        countriesBox.addView(t);
        new Thread(new Runnable() {
            @Override public void run() {
                final List<VpnGateServer> servers = VpnGateClient.load(ctx, force);
                final List<VpnGateClient.CountryGroup> g =
                        VpnGateClient.groupByCountry(servers);
                handler.post(new Runnable() {
                    @Override public void run() {
                        loadingServers = false;
                        groups = g;
                        renderGroups();
                    }
                });
            }
        }, "VpnGateLoad").start();
    }

    /** Pinta los grupos desde la caché en memoria (sin red). */
    private void repaintGroups() {
        if (!loadingServers && groups != null) renderGroups();
    }

    private void renderGroups() {
        final Context ctx = activity;
        countriesBox.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(ctx);
        boolean empty = groups == null || groups.isEmpty();
        if (empty) {
            TextView t = new TextView(ctx);
            t.setText("Sin conexión: no se pudo descargar la lista de servidores. Toca «↻ Actualizar» para reintentar.");
            t.setTextColor(0xFFFF9D5C); t.setTextSize(13);
            t.setPadding(0, 8, 0, 8);
            countriesBox.addView(t);
            return;
        }
        for (final VpnGateClient.CountryGroup g : groups) {
            View v = inf.inflate(R.layout.item_country_group, countriesBox, false);
            ImageView flag = v.findViewById(R.id.group_flag);
            TextView iso = v.findViewById(R.id.group_iso);
            TextView name = v.findViewById(R.id.group_name);
            TextView sub = v.findViewById(R.id.group_sub);
            final TextView chevron = v.findViewById(R.id.group_chevron);
            final LinearLayout serverBox = v.findViewById(R.id.group_servers);

            flag.setImageResource(FlagDrawables.forIso(g.iso));
            if (!FlagDrawables.hasFlag(g.iso)) {
                iso.setText(g.iso);
                iso.setVisibility(View.VISIBLE);
            }
            name.setText(g.nameEs);
            int n = g.servers.size();
            sub.setText(n + (n == 1 ? " servidor" : " servidores") + " · toca para ver");

            // Filas de servidores (se crean al expandir)
            for (final VpnGateServer s : g.servers) {
                View sv = inf.inflate(R.layout.item_vpngate_server, serverBox, false);
                TextView host = sv.findViewById(R.id.server_host);
                TextView stats = sv.findViewById(R.id.server_stats);
                final TextView badge = sv.findViewById(R.id.server_badge);
                final View dot = sv.findViewById(R.id.server_dot);
                host.setText(s.hostName + " · " + s.ip);
                stats.setText(s.pingText() + " · " + s.numSessions + " sesiones · " + s.speedText());
                paintServerRow(s, badge, dot);
                sv.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View vw) { onServerTap(s); }
                });
                serverBox.addView(sv);
            }

            v.findViewById(R.id.group_header).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View vw) {
                    boolean open = serverBox.getVisibility() == View.VISIBLE;
                    serverBox.setVisibility(open ? View.GONE : View.VISIBLE);
                    chevron.setText(open ? "▾" : "▴");
                }
            });
            countriesBox.addView(v);
        }
    }

    private void paintServerRow(VpnGateServer s, TextView badge, View dot) {
        boolean dead = OvpnVpnService.deadServers.contains(s.displayName());
        boolean current = OvpnConnector.isRunning()
                && OvpnVpnService.currentLabel.equals(s.displayName());
        if (current) {
            badge.setText("CONECTADO");
            badge.setTextColor(0xFF3DFF9C);
            dot.setBackgroundResource(R.drawable.dot_active);
        } else if (dead) {
            badge.setText("No disponible");
            badge.setTextColor(0xFFFF9D5C);
            dot.setBackgroundResource(R.drawable.dot_idle);
        } else {
            badge.setText("Conectar ›");
            badge.setTextColor(0xFF4FD8FF);
            dot.setBackgroundResource(R.drawable.dot_idle);
        }
    }

    private void onServerTap(final VpnGateServer s) {
        if (OvpnConnector.isRunning()) {
            if (OvpnVpnService.currentLabel.equals(s.displayName())) {
                disconnect(); // tocar el servidor activo = desconectar
            } else {
                new AlertDialog.Builder(activity)
                        .setTitle("Cambiar de servidor")
                        .setMessage("Ya hay un túnel activo (" + OvpnVpnService.currentLabel
                                + "). ¿Desconectarlo y conectar a " + s.displayName() + "?")
                        .setPositiveButton("Cambiar", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                disconnect();
                                handler.postDelayed(new Runnable() {
                                    @Override public void run() { activity.startRemoteVpn(s); }
                                }, 600);
                            }
                        })
                        .setNegativeButton("Cancelar", null)
                        .show();
            }
            return;
        }
        if (OvpnVpnService.deadServers.contains(s.displayName())) {
            new AlertDialog.Builder(activity)
                    .setTitle("Servidor no disponible")
                    .setMessage(s.displayName() + " no respondió en esta sesión.\n\n"
                            + "Los servidores VPNGate son voluntarios y cambian: prueba con otro de la lista.")
                    .setPositiveButton("Entendido", null)
                    .show();
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle("Conectar a " + s.displayName())
                .setMessage(s.hostName + "\nIP " + s.ip + " · " + s.pingText()
                        + "\n" + s.numSessions + " sesiones activas · " + s.speedText()
                        + "\n\nSe abrirá un túnel OpenVPN real. Todo tu tráfico pasará por este servidor gratuito (VPNGate).")
                .setPositiveButton("Conectar", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        remoteErrorShownFor = "";
                        activity.startRemoteVpn(s);
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void showLicense() {
        String text = "No se pudo leer la licencia.";
        try {
            InputStream in = activity.getResources().openRawResource(R.raw.gpl_ics_openvpn);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTextColor(0xFFEDEFFF);
        tv.setPadding(24, 16, 24, 16);
        android.widget.ScrollView sv = new android.widget.ScrollView(activity);
        sv.addView(tv);
        new AlertDialog.Builder(activity)
                .setTitle("Licencia del motor OpenVPN (ics-openvpn)")
                .setView(sv)
                .setPositiveButton("Cerrar", null)
                .show();
    }

    // ---------- estado ----------

    public void setVisible(boolean v) {
        visible = v;
        if (v) refreshHistory();
    }

    public void destroy() { handler.removeCallbacks(poll); }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            boolean local = HyperionVpnService.running.get();
            boolean remote = OvpnConnector.isRunning();

            if (local) {
                if (!button.isConnected() && !disconnecting) {
                    button.setConnected(true);
                    burst.burst(60);
                }
                status.setText("Protegido");
                status.setTextColor(0xFF3DFF9C);
                hint.setText("Escudo activo · Hyperion Local · toca DESCONECTAR para detener");
                pulse.setActive(true);
                disconnectBanner.setVisibility(View.VISIBLE);
                localBadge.setText("ACTIVO");
                localBadge.setTextColor(0xFF3DFF9C);
                localDot.setBackgroundResource(R.drawable.dot_active);
                long secs = (System.currentTimeMillis() - HyperionVpnService.connectedSince) / 1000;
                time.setText(fmtTime(secs));
                queries.setText(String.valueOf(HyperionVpnService.stats.queries.get()));
                blocked.setText(String.valueOf(HyperionVpnService.stats.blocked.get()));
                long bytes = HyperionVpnService.stats.bytesIn.get()
                        + HyperionVpnService.stats.bytesOut.get();
                data.setText(fmtBytes(bytes));
            } else if (remote) {
                if (!button.isConnected() && !disconnecting) {
                    button.setConnected(true);
                    burst.burst(60);
                }
                remoteErrorShownFor = "";
                int level = OvpnVpnService.lastLevel;
                if (level == ConnectionStatus.LEVEL_CONNECTED) {
                    status.setText("Conectado");
                    status.setTextColor(0xFF3DFF9C);
                } else {
                    status.setText("Conectando…");
                    status.setTextColor(0xFFFFD35C);
                }
                hint.setText("Túnel OpenVPN · " + OvpnVpnService.currentLabel
                        + " · toca DESCONECTAR para detener");
                pulse.setActive(true);
                disconnectBanner.setVisibility(View.VISIBLE);
                localBadge.setText("INACTIVO");
                localBadge.setTextColor(0xFF8A90B8);
                localDot.setBackgroundResource(R.drawable.dot_idle);
                long secs = (System.currentTimeMillis() - OvpnVpnService.connectedSince) / 1000;
                time.setText(fmtTime(secs));
                queries.setText("—");
                blocked.setText("—");
                data.setText("—");
            } else {
                disconnecting = false; // el servicio confirmó la caída
                if (button.isConnected()) button.setConnected(false);
                status.setText("Desconectado");
                status.setTextColor(0xFFEDEFFF);
                hint.setText("Toca para activar la protección");
                pulse.setActive(false);
                disconnectBanner.setVisibility(View.GONE);
                localBadge.setText("INACTIVO");
                localBadge.setTextColor(0xFF8A90B8);
                localDot.setBackgroundResource(R.drawable.dot_idle);
                maybeShowRemoteError();
            }
            handler.postDelayed(this, 1000);
        }
    };

    /** Si el túnel remoto murió con error, avisar una vez y sugerir otro servidor. */
    private void maybeShowRemoteError() {
        String err = OvpnVpnService.lastError;
        String label = OvpnVpnService.currentLabel;
        if (err == null || err.isEmpty() || label.isEmpty()) return;
        if (label.equals(remoteErrorShownFor)) return;
        remoteErrorShownFor = label;
        // Limpiar para no repetir en el próximo poll si el usuario no hace nada
        OvpnVpnService.lastError = "";
        new AlertDialog.Builder(activity)
                .setTitle("No se pudo conectar")
                .setMessage(label + "\n\n" + err)
                .setPositiveButton("Elegir otro servidor", null)
                .show();
        // Refresca las insignias: el servidor fallido queda como "No disponible"
        repaintGroups();
    }

    private void refreshHistory() {
        final Context ctx = activity;
        historyBox.removeAllViews();
        List<HistoryStore.VpnSession> sessions =
                HistoryStore.getVpnSessions(ctx, 5);
        LayoutInflater inf = LayoutInflater.from(ctx);
        SimpleDateFormat df = new SimpleDateFormat("d MMM HH:mm", Locale.getDefault());
        if (sessions.isEmpty()) {
            TextView t = new TextView(ctx);
            t.setText("Aún no hay sesiones registradas.");
            t.setTextColor(0xFF8A90B8); t.setTextSize(13);
            historyBox.addView(t);
            return;
        }
        for (HistoryStore.VpnSession s : sessions) {
            View v = inf.inflate(R.layout.item_history, historyBox, false);
            TextView title = v.findViewById(R.id.hist_title);
            TextView sub = v.findViewById(R.id.hist_sub);
            long mins = Math.max(1, (s.end - s.start) / 60000);
            title.setText(df.format(new Date(s.start)) + " · " + mins + " min");
            sub.setText(s.queries + " consultas DNS · " + s.blocked + " bloqueadas");
            historyBox.addView(v);
        }
    }

    private static String fmtTime(long secs) {
        long h = secs / 3600, m = (secs % 3600) / 60, s = secs % 60;
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s);
    }

    private static String fmtBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.1f KB", b / 1024f);
        return String.format(Locale.US, "%.1f MB", b / 1048576f);
    }
}
