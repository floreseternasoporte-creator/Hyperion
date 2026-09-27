package com.drex.hyperion;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Controlador de la pantalla VPN. */
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
        // Banner grande de desconexión: imposible no verlo
        disconnectBanner.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { disconnect(); }
        });
        // Tarjeta local: toca para conectar cuando está inactiva
        root.findViewById(R.id.vpn_local_card).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!HyperionVpnService.running.get()) connect();
            }
        });
        renderCountries();
        refreshHistory();
        poll.run();
    }

    private void connect() {
        button.playLaunch(new Runnable() {
            @Override public void run() { activity.startVpn(); }
        });
    }

    private void disconnect() {
        activity.stopVpn();
        button.setConnected(false);
    }

    private void onButtonTap() {
        if (HyperionVpnService.running.get()) {
            disconnect();
        } else {
            // secuencia de lanzamiento y luego conexión
            connect();
        }
    }

    /** Lista honesta de países: todos "Próximamente" y deshabilitados. */
    private void renderCountries() {
        final Context ctx = activity;
        countriesBox.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(ctx);
        List<VpnCountries.Country> list = VpnCountries.load(ctx);
        for (final VpnCountries.Country c : list) {
            View v = inf.inflate(R.layout.item_country, countriesBox, false);
            TextView name = v.findViewById(R.id.country_name);
            TextView sub = v.findViewById(R.id.country_sub);
            name.setText(c.country);
            sub.setText(c.city + " · " + c.code);
            // Deshabilitado de verdad: al tocarlo, diálogo honesto
            v.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View vw) { showSoonDialog(c); }
            });
            countriesBox.addView(v);
        }
    }

    private void showSoonDialog(VpnCountries.Country c) {
        new AlertDialog.Builder(activity)
                .setTitle(c.country + " · Próximamente")
                .setMessage(VpnCountries.soonMessage(c))
                .setPositiveButton("Entendido", null)
                .show();
    }

    public void setVisible(boolean v) {
        visible = v;
        if (v) refreshHistory();
    }

    public void destroy() { handler.removeCallbacks(poll); }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (HyperionVpnService.running.get()) {
                if (!button.isConnected()) {
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
            } else {
                if (button.isConnected()) button.setConnected(false);
                status.setText("Desconectado");
                status.setTextColor(0xFFEDEFFF);
                hint.setText("Toca para activar la protección");
                pulse.setActive(false);
                disconnectBanner.setVisibility(View.GONE);
                localBadge.setText("INACTIVO");
                localBadge.setTextColor(0xFF8A90B8);
                localDot.setBackgroundResource(R.drawable.dot_idle);
            }
            handler.postDelayed(this, 1000);
        }
    };

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
