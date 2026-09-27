package com.drex.hyperion.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.TrafficStats;
import android.os.Handler;
import android.os.Looper;
import android.telephony.TelephonyManager;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.drex.hyperion.MainActivity;
import com.drex.hyperion.R;
import com.drex.hyperion.ui.Cine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * MobileController — sección "Móvil" de Hyperion 2.1 (futura operadora Hyperion).
 *
 * <p>Todo real: consumo de datos móviles con TrafficStats (totales del
 * dispositivo + top apps por UID), operadora actual con TelephonyManager
 * (nombre de red, SIM, tecnología) y lista de espera local en
 * SharedPreferences.</p>
 *
 * <p>REGLA DE ORO: jamás promete "internet gratis". Habla de la futura
 * operadora Hyperion (MVNO) y de ahorro, nunca de datos regalados.</p>
 */
public class MobileController {
    private static final String PREFS = "hyperion_mobile";

    private final MainActivity activity;
    private final View root;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;

    private TextView rx, tx, opName, opNet, opSim;
    private LinearLayout appsBox;
    private TextView appsEmpty;
    private EditText nameIn, emailIn;
    private Button joinBtn;
    private TextView joinStatus;

    public MobileController(MainActivity activity, LayoutInflater inflater, View root) {
        this.activity = activity;
        this.root = root;
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        rx = root.findViewById(R.id.mobile_rx);
        tx = root.findViewById(R.id.mobile_tx);
        opName = root.findViewById(R.id.mobile_op_name);
        opNet = root.findViewById(R.id.mobile_op_net);
        opSim = root.findViewById(R.id.mobile_op_sim);
        appsBox = root.findViewById(R.id.mobile_apps);
        appsEmpty = root.findViewById(R.id.mobile_apps_empty);
        nameIn = root.findViewById(R.id.mobile_name);
        emailIn = root.findViewById(R.id.mobile_email);
        joinBtn = root.findViewById(R.id.mobile_join);
        joinStatus = root.findViewById(R.id.mobile_join_status);

        joinBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Cine.launchSequence(joinBtn, new Runnable() {
                    @Override public void run() { joinWaitlist(); }
                });
            }
        });

        int[] ids = {R.id.mobile_header, R.id.mobile_honest_card, R.id.mobile_data_card,
                R.id.mobile_op_card, R.id.mobile_wait_card, R.id.mobile_note_card};
        for (int i = 0; i < ids.length; i++) Cine.fadeSlideIn(root.findViewById(ids[i]), i * 70L);

        refreshOperator();
        refreshWaitlist();
        poll.run();
    }

    // ------------------------------------------------------------------
    // Operadora actual (TelephonyManager — sin permisos peligrosos)
    // ------------------------------------------------------------------

    private void refreshOperator() {
        try {
            TelephonyManager tm =
                    (TelephonyManager) activity.getSystemService(Context.TELEPHONY_SERVICE);
            String net = tm.getNetworkOperatorName();
            opName.setText(net != null && !net.isEmpty() ? net : "Sin servicio");
            opNet.setText(netTypeName(tm.getDataNetworkType()));
            String sim = tm.getSimOperatorName();
            opSim.setText(sim != null && !sim.isEmpty()
                    ? "SIM: " + sim : "Sin información de SIM");
        } catch (Exception e) {
            opName.setText("No disponible");
            opNet.setText("–");
            opSim.setText("");
        }
    }

    private static String netTypeName(int t) {
        switch (t) {
            case TelephonyManager.NETWORK_TYPE_GPRS:
            case TelephonyManager.NETWORK_TYPE_EDGE:
            case TelephonyManager.NETWORK_TYPE_CDMA:
            case TelephonyManager.NETWORK_TYPE_1xRTT:
            case TelephonyManager.NETWORK_TYPE_IDEN: return "2G";
            case TelephonyManager.NETWORK_TYPE_UMTS:
            case TelephonyManager.NETWORK_TYPE_EVDO_0:
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_HSPA:
            case TelephonyManager.NETWORK_TYPE_EVDO_B:
            case TelephonyManager.NETWORK_TYPE_EHRPD:
            case TelephonyManager.NETWORK_TYPE_HSPAP: return "3G";
            case TelephonyManager.NETWORK_TYPE_LTE: return "4G";
            default:
                // 5G (NR) existe desde API 29
                if (t == 20) return "5G";
                return t == TelephonyManager.NETWORK_TYPE_UNKNOWN ? "Sin servicio" : "–";
        }
    }

    // ------------------------------------------------------------------
    // Consumo de datos móviles (TrafficStats)
    // ------------------------------------------------------------------

    private static class AppTraffic {
        String label, pkg;
        long bytes;
    }

    private void refreshTraffic() {
        long rxB = TrafficStats.getMobileRxBytes();
        long txB = TrafficStats.getMobileTxBytes();
        rx.setText(rxB >= 0 ? fmtBytes(rxB) : "–");
        tx.setText(txB >= 0 ? fmtBytes(txB) : "–");

        new Thread(new Runnable() {
            @Override public void run() {
                final List<AppTraffic> top = topAppsByMobile();
                handler.post(new Runnable() {
                    @Override public void run() { showTopApps(top); }
                });
            }
        }).start();
    }

    private List<AppTraffic> topAppsByMobile() {
        List<AppTraffic> out = new ArrayList<>();
        try {
            PackageManager pm = activity.getPackageManager();
            String self = activity.getPackageName();
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                if (ai.packageName.equals(self)) continue;
                long rxB = TrafficStats.getUidRxBytes(ai.uid);
                long txB = TrafficStats.getUidTxBytes(ai.uid);
                if (rxB < 0 || txB < 0) continue;
                long total = rxB + txB;
                if (total <= 0) continue;
                AppTraffic a = new AppTraffic();
                a.pkg = ai.packageName;
                a.bytes = total;
                try {
                    a.label = String.valueOf(pm.getApplicationLabel(ai));
                } catch (Exception e) {
                    a.label = ai.packageName;
                }
                out.add(a);
            }
            Collections.sort(out, new Comparator<AppTraffic>() {
                @Override public int compare(AppTraffic a, AppTraffic b) {
                    return Long.compare(b.bytes, a.bytes);
                }
            });
        } catch (Exception ignored) {}
        return out.size() > 5 ? out.subList(0, 5) : out;
    }

    private void showTopApps(List<AppTraffic> top) {
        appsBox.removeAllViews();
        if (top.isEmpty()) {
            appsEmpty.setVisibility(View.VISIBLE);
            return;
        }
        appsEmpty.setVisibility(View.GONE);
        LayoutInflater inf = LayoutInflater.from(activity);
        for (AppTraffic a : top) {
            View v = inf.inflate(R.layout.item_blocked_app, appsBox, false);
            ((TextView) v.findViewById(R.id.app_name)).setText(a.label);
            ((TextView) v.findViewById(R.id.app_sub)).setText(a.pkg);
            ((TextView) v.findViewById(R.id.app_bytes)).setText(fmtBytes(a.bytes));
            appsBox.addView(v);
            Cine.fadeSlideIn(v, 0);
        }
    }

    private static String fmtBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.1f KB", b / 1024f);
        if (b < 1024 * 1024 * 1024) return String.format(Locale.US, "%.1f MB", b / 1048576f);
        return String.format(Locale.US, "%.2f GB", b / 1073741824f);
    }

    // ------------------------------------------------------------------
    // Lista de espera (local, honesta)
    // ------------------------------------------------------------------

    private void joinWaitlist() {
        String name = nameIn.getText().toString().trim();
        String email = emailIn.getText().toString().trim();
        if (name.isEmpty() || email.isEmpty() || !email.contains("@")) {
            joinStatus.setText("Escribe tu nombre y un correo válido.");
            joinStatus.setTextColor(0xFFFFB020);
            return;
        }
        prefs.edit().putString("wait_name", name)
                .putString("wait_email", email)
                .putBoolean("wait_joined", true).apply();
        refreshWaitlist();
    }

    private void refreshWaitlist() {
        if (prefs.getBoolean("wait_joined", false)) {
            String n = prefs.getString("wait_name", "");
            joinStatus.setText("¡Listo, " + n + "! Te avisaremos cuando Hyperion Mobile llegue a tu zona.");
            joinStatus.setTextColor(0xFF3DFF9C);
            joinBtn.setEnabled(false);
            joinBtn.setText("Ya estás en la lista");
        }
    }

    // ------------------------------------------------------------------

    public void setVisible(boolean v) {
        if (v) {
            refreshOperator();
            refreshTraffic();
        }
    }

    public void destroy() {
        handler.removeCallbacks(poll);
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            refreshTraffic();
            handler.postDelayed(this, 5000);
        }
    };
}
