package com.drex.hyperion.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.telephony.TelephonyManager;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.drex.hyperion.MainActivity;
import com.drex.hyperion.R;
import com.drex.hyperion.ui.Cine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * MobileController — pestaña "Móvil" de Hyperion 2.2: centro de comando de
 * datos. Plan real del usuario (GB + día de ciclo), medición con TrafficStats
 * y baseline por ciclo (sobrevive reinicios), alertas al 50/80/100 %,
 * proyección honesta del ritmo, ranking de apps por UID, operadora actual y
 * la futura Hyperion Mobile solo como lista de espera.
 *
 * <p>REGLA DE ORO: todo medido de verdad con TrafficStats; jamás se simula
 * consumo ni una operadora activa.</p>
 */
public class MobileController {
    private static final String PREFS = "hyperion_mobile";

    private final MainActivity activity;
    private final View root;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;

    // Plan
    private TextView planUsed, planLeft, planPace;
    private ProgressBar planBar;
    private LinearLayout planConfig;
    private EditText planGbIn, planDayIn;
    private Button planSave, planEdit;

    // Apps / operadora / lista de espera
    private LinearLayout appsBox;
    private TextView appsEmpty;
    private TextView opName, opNet, opSim;
    private LinearLayout waitForm;
    private EditText nameIn, emailIn;
    private Button joinBtn, joinToggle;
    private TextView joinStatus;

    public MobileController(MainActivity activity, LayoutInflater inflater, View root) {
        this.activity = activity;
        this.root = root;
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        planUsed = root.findViewById(R.id.mobile_plan_used);
        planLeft = root.findViewById(R.id.mobile_plan_left);
        planPace = root.findViewById(R.id.mobile_plan_pace);
        planBar = root.findViewById(R.id.mobile_plan_bar);
        planConfig = root.findViewById(R.id.mobile_plan_config);
        planGbIn = root.findViewById(R.id.mobile_plan_gb);
        planDayIn = root.findViewById(R.id.mobile_plan_day);
        planSave = root.findViewById(R.id.mobile_plan_save);
        planEdit = root.findViewById(R.id.mobile_plan_edit);

        appsBox = root.findViewById(R.id.mobile_apps);
        appsEmpty = root.findViewById(R.id.mobile_apps_empty);
        opName = root.findViewById(R.id.mobile_op_name);
        opNet = root.findViewById(R.id.mobile_op_net);
        opSim = root.findViewById(R.id.mobile_op_sim);
        waitForm = root.findViewById(R.id.mobile_wait_form);
        nameIn = root.findViewById(R.id.mobile_name);
        emailIn = root.findViewById(R.id.mobile_email);
        joinBtn = root.findViewById(R.id.mobile_join);
        joinToggle = root.findViewById(R.id.mobile_join_toggle);
        joinStatus = root.findViewById(R.id.mobile_join_status);

        planSave.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Cine.launchSequence(planSave, new Runnable() {
                    @Override public void run() { savePlan(); }
                });
            }
        });
        planEdit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleConfig(); }
        });
        joinToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                waitForm.setVisibility(waitForm.getVisibility() == View.VISIBLE
                        ? View.GONE : View.VISIBLE);
            }
        });
        joinBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Cine.launchSequence(joinBtn, new Runnable() {
                    @Override public void run() { joinWaitlist(); }
                });
            }
        });

        int[] ids = {R.id.mobile_header, R.id.mobile_plan_card, R.id.mobile_data_card,
                R.id.mobile_op_card, R.id.mobile_wait_card};
        for (int i = 0; i < ids.length; i++) Cine.fadeSlideIn(root.findViewById(ids[i]), i * 70L);

        refreshOperator();
        refreshWaitlist();
        renderPlan();
        poll.run();
    }

    // ------------------------------------------------------------------
    // Plan de datos
    // ------------------------------------------------------------------

    private void toggleConfig() {
        boolean show = planConfig.getVisibility() != View.VISIBLE;
        planConfig.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show && DataPlan.hasPlan(activity)) {
            planGbIn.setText(String.valueOf(DataPlan.getPlanGb(activity)));
            planDayIn.setText(String.valueOf(DataPlan.getPlanDay(activity)));
        }
    }

    private void savePlan() {
        float gb;
        int day;
        try {
            gb = Float.parseFloat(planGbIn.getText().toString().trim().replace(',', '.'));
        } catch (Exception e) {
            planPace.setText("GB inválidos");
            return;
        }
        try {
            day = Integer.parseInt(planDayIn.getText().toString().trim());
        } catch (Exception e) {
            planPace.setText("Día inválido");
            return;
        }
        if (gb < 0.1f || gb > 999f) {
            planPace.setText("GB inválidos");
            return;
        }
        if (day < 1) day = 1;
        if (day > 31) day = 31;
        DataPlan.savePlan(activity, gb, day);
        planConfig.setVisibility(View.GONE);
        planEdit.setVisibility(View.VISIBLE);
        renderPlan();
    }

    private void renderPlan() {
        if (!DataPlan.hasPlan(activity)) {
            planUsed.setText("Sin plan configurado");
            planLeft.setText("");
            planPace.setText("");
            planBar.setProgress(0);
            planConfig.setVisibility(View.VISIBLE);
            planEdit.setVisibility(View.GONE);
            return;
        }
        long used = DataPlan.updateUsage(activity);
        long planBytes = DataPlan.getPlanBytes(activity);
        DataPlan.Cycle cy = DataPlan.cycleFor(DataPlan.getPlanDay(activity),
                System.currentTimeMillis());

        if (used < 0) {
            planUsed.setText("Medición no disponible");
            planLeft.setText("");
            planPace.setText("");
            planBar.setProgress(0);
            return;
        }

        int pct = planBytes > 0 ? (int) (used * 100 / planBytes) : 0;
        planUsed.setText("Usado " + DataPlan.fmtGb(used) + " · " + pct + "%");
        planBar.setProgress(Math.min(1000, pct * 10));

        String planTxt = "Plan " + DataPlan.fmtPlanGb(DataPlan.getPlanGb(activity));
        long left = planBytes - used;
        String leftTxt = left >= 0
                ? "quedan " + DataPlan.fmtGb(left)
                : "excedido por " + DataPlan.fmtGb(-left);
        String daysTxt = cy.daysLeft == 1 ? "1 día" : cy.daysLeft + " días";
        planLeft.setText(planTxt + " · " + leftTxt + " · " + daysTxt);

        String proj = DataPlan.projection(activity, used);
        planPace.setText(proj != null ? proj : "Proyección: muy pronto");
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
            opSim.setText(sim != null && !sim.isEmpty() ? "SIM: " + sim : "");
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
                if (t == 20) return "5G"; // NETWORK_TYPE_NR, API 29
                return t == TelephonyManager.NETWORK_TYPE_UNKNOWN ? "Sin servicio" : "–";
        }
    }

    // ------------------------------------------------------------------
    // Ranking de apps por datos móviles (TrafficStats por UID)
    // ------------------------------------------------------------------

    private static class AppTraffic {
        String label, pkg;
        long bytes;
    }

    private void refreshTraffic() {
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
                long rxB = android.net.TrafficStats.getUidRxBytes(ai.uid);
                long txB = android.net.TrafficStats.getUidTxBytes(ai.uid);
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
            ((TextView) v.findViewById(R.id.app_bytes)).setText(DataPlan.fmtGb(a.bytes));
            appsBox.addView(v);
            Cine.fadeSlideIn(v, 0);
        }
    }

    // ------------------------------------------------------------------
    // Lista de espera (local, honesta: futura operadora, nada lanzado)
    // ------------------------------------------------------------------

    private void joinWaitlist() {
        String name = nameIn.getText().toString().trim();
        String email = emailIn.getText().toString().trim();
        if (name.isEmpty() || email.isEmpty() || !email.contains("@")) {
            joinStatus.setText("Nombre y correo válido.");
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
            joinStatus.setText("En la lista ✓ " + n);
            joinStatus.setTextColor(0xFF3DFF9C);
            joinToggle.setEnabled(false);
            joinToggle.setText("Ya estás en la lista");
            waitForm.setVisibility(View.GONE);
        }
    }

    // ------------------------------------------------------------------

    public void setVisible(boolean v) {
        if (v) {
            refreshOperator();
            renderPlan();
            refreshTraffic();
        }
    }

    public void destroy() {
        handler.removeCallbacks(poll);
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            renderPlan();
            refreshTraffic();
            handler.postDelayed(this, 5000);
        }
    };
}
