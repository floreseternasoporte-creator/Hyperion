package com.drex.hyperion.battery;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.drex.hyperion.MainActivity;
import com.drex.hyperion.R;
import com.drex.hyperion.ui.Cine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BatteryController — pestaña "Batería" de Hyperion 2.1.
 *
 * <p>Todo real con APIs de Android: BatteryManager (nivel, temperatura,
 * voltaje, salud, tecnología, estado), estimación de autonomía por ritmo
 * de descarga medido, análisis de consumo con UsageStatsManager (permiso
 * especial por ajustes), cierre de procesos en segundo plano con
 * ActivityManager.killBackgroundProcesses, y protección de carga con
 * avisos al 80%/100% vía BroadcastReceiver dinámico + notificación.</p>
 */
public class BatteryController {
    private static final String PREFS = "hyperion_battery";
    private static final String CH_ID = "hyperion_charge_guard";

    private final MainActivity activity;
    private final View root;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;

    private BatteryGaugeView gauge;
    private TextView gaugePct, state, eta;
    private TextView statLevel, statTemp, statVolt, statHealth, statTech, statState;
    private LinearLayout permCard, appsBox;
    private TextView appsEmpty, scanStatus;
    private Button scanBtn, permBtn, optimizeBtn;
    private TextView optimizeStatus;
    private Switch guard80, guard100;

    private boolean alerted80, alerted100;
    private String lastAppsSig = "";
    private final List<String> lastDetected = new ArrayList<>();

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                onBatteryIntent(intent);
            }
        }
    };

    public BatteryController(MainActivity activity, LayoutInflater inflater, View root) {
        this.activity = activity;
        this.root = root;
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        gauge = new BatteryGaugeView(activity);
        FrameLayout box = root.findViewById(R.id.battery_gauge_box);
        box.addView(gauge, 0);
        gaugePct = root.findViewById(R.id.battery_gauge_pct);
        state = root.findViewById(R.id.battery_state);
        eta = root.findViewById(R.id.battery_eta);
        statLevel = root.findViewById(R.id.battery_stat_level);
        statTemp = root.findViewById(R.id.battery_stat_temp);
        statVolt = root.findViewById(R.id.battery_stat_volt);
        statHealth = root.findViewById(R.id.battery_stat_health);
        statTech = root.findViewById(R.id.battery_stat_tech);
        statState = root.findViewById(R.id.battery_stat_state);
        permCard = root.findViewById(R.id.battery_perm_card);
        appsBox = root.findViewById(R.id.battery_apps);
        appsEmpty = root.findViewById(R.id.battery_apps_empty);
        scanStatus = root.findViewById(R.id.battery_scan_status);
        scanBtn = root.findViewById(R.id.battery_scan);
        permBtn = root.findViewById(R.id.battery_perm_btn);
        optimizeBtn = root.findViewById(R.id.battery_optimize);
        optimizeStatus = root.findViewById(R.id.battery_optimize_status);
        guard80 = root.findViewById(R.id.battery_guard_80);
        guard100 = root.findViewById(R.id.battery_guard_100);

        guard80.setChecked(prefs.getBoolean("guard80", true));
        guard100.setChecked(prefs.getBoolean("guard100", true));
        guard80.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.edit().putBoolean("guard80", on).apply();
            }
        });
        guard100.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.edit().putBoolean("guard100", on).apply();
            }
        });

        permBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    activity.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                } catch (Exception ignored) {}
            }
        });
        scanBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Cine.launchSequence(scanBtn, new Runnable() {
                    @Override public void run() { runAnalysis(); }
                });
            }
        });
        optimizeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Cine.launchSequence(optimizeBtn, new Runnable() {
                    @Override public void run() { runOptimize(); }
                });
            }
        });

        int[] ids = {R.id.battery_header, R.id.battery_gauge_card, R.id.battery_stats_card,
                R.id.battery_scan_card, R.id.battery_opt_card, R.id.battery_guard_card,
                R.id.battery_honest_card};
        for (int i = 0; i < ids.length; i++) Cine.fadeSlideIn(root.findViewById(ids[i]), i * 70L);

        // Receiver dinámico (ACTION_BATTERY_CHANGED solo funciona así)
        IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent sticky = activity.registerReceiver(batteryReceiver, f);
        if (sticky != null) onBatteryIntent(sticky);

        poll.run();
    }

    // ------------------------------------------------------------------
    // Lectura de batería
    // ------------------------------------------------------------------

    private void onBatteryIntent(Intent i) {
        BatteryManager bm = (BatteryManager) activity.getSystemService(Context.BATTERY_SERVICE);
        int level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        if (level < 0) {
            int lv = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            level = lv >= 0 ? Math.round(lv * 100f / sc) : 0;
        }
        int tempT = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
        int voltMv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
        int health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN);
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        String tech = i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);

        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;

        gauge.setLevelAnimated(level, charging);
        gaugePct.setText(level + "%");
        state.setText(charging ? "Cargando" : statusName(status));
        state.setTextColor(charging ? 0xFF3DFF9C : 0xFF8A90B8);

        statLevel.setText(level + "%");
        statTemp.setText(tempT >= 0 ? String.format(Locale.US, "%.1f °C", tempT / 10f) : "–");
        statVolt.setText(voltMv > 0 ? String.format(Locale.US, "%.2f V", voltMv / 1000f) : "–");
        statHealth.setText(healthName(health));
        statTech.setText(tech != null ? tech : "–");
        statState.setText(statusName(status));

        recordSample(level, charging);
        updateEta(level, charging);
        checkChargeGuard(level, charging);
    }

    private static String statusName(int s) {
        switch (s) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return "Cargando";
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return "Descargando";
            case BatteryManager.BATTERY_STATUS_FULL: return "Carga completa";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "Sin cargar";
            default: return "Desconocido";
        }
    }

    private static String healthName(int h) {
        switch (h) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return "Buena";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "Sobrecalentada";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "Agotada";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "Sobrevoltaje";
            case BatteryManager.BATTERY_HEALTH_COLD: return "Fría";
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE: return "Fallo";
            default: return "Desconocida";
        }
    }

    // ------------------------------------------------------------------
    // Estimación de autonomía (ritmo de descarga medido, honesto)
    // ------------------------------------------------------------------

    private void recordSample(int level, boolean charging) {
        if (charging) return; // solo medimos descargando
        long now = System.currentTimeMillis();
        long lastTs = prefs.getLong("sample_ts", 0);
        int lastLv = prefs.getInt("sample_lv", -1);
        if (lastLv >= 0 && now - lastTs > 20 * 60 * 1000L && level != lastLv) {
            // guarda par (antiguo -> nuevo) para ritmo
            prefs.edit()
                    .putLong("rate_t0", lastTs).putInt("rate_l0", lastLv)
                    .putLong("rate_t1", now).putInt("rate_l1", level)
                    .apply();
        }
        if (now - lastTs > 10 * 60 * 1000L || lastLv < 0) {
            prefs.edit().putLong("sample_ts", now).putInt("sample_lv", level).apply();
        }
    }

    private void updateEta(int level, boolean charging) {
        long t0 = prefs.getLong("rate_t0", 0);
        long t1 = prefs.getLong("rate_t1", 0);
        int l0 = prefs.getInt("rate_l0", -1);
        int l1 = prefs.getInt("rate_l1", -1);
        if (t1 > t0 && l0 > l1 && (t1 - t0) >= 30 * 60 * 1000L) {
            double hours = (t1 - t0) / 3600000.0;
            double drainPerHour = (l0 - l1) / hours; // %/h
            if (drainPerHour > 0.2) {
                double hLeft = level / drainPerHour;
                int hh = (int) hLeft, mm = (int) Math.round((hLeft - hh) * 60);
                eta.setText(String.format(Locale.US,
                        "≈ %d h %02d min restantes (estimado)", hh, mm));
                return;
            }
        }
        eta.setText(charging ? "Cargando…"
                : "Midiendo tu ritmo de descarga…");
    }

    // ------------------------------------------------------------------
    // Protección de carga: avisos 80% / 100%
    // ------------------------------------------------------------------

    private void checkChargeGuard(int level, boolean charging) {
        if (!charging) {
            if (level < 75) alerted80 = false;
            if (level < 95) alerted100 = false;
            return;
        }
        if (prefs.getBoolean("guard80", true) && !alerted80 && level >= 80 && level < 100) {
            alerted80 = true;
            notifyCharge("Batería al 80%",
                    "Nivel óptimo alcanzado. Desconecta el cargador para alargar la vida útil.");
        }
        if (prefs.getBoolean("guard100", true) && !alerted100 && level >= 100) {
            alerted100 = true;
            notifyCharge("Carga completa (100%)",
                    "Desconecta el cargador para cuidar tu batería.");
        }
    }

    private void notifyCharge(String title, String text) {
        NotificationManager nm =
                (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH_ID,
                    "Protección de carga", NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch);
        }
        Notification n;
        if (Build.VERSION.SDK_INT >= 26) {
            n = new Notification.Builder(activity, CH_ID)
                    .setContentTitle("Hyperion · " + title)
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_launcher_hyperion)
                    .setAutoCancel(true)
                    .build();
        } else {
            n = new Notification.Builder(activity)
                    .setContentTitle("Hyperion · " + title)
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_launcher_hyperion)
                    .setAutoCancel(true)
                    .build();
        }
        nm.notify(title.hashCode(), n);
    }

    // ------------------------------------------------------------------
    // Análisis de consumo (UsageStats)
    // ------------------------------------------------------------------

    private boolean hasUsageAccess() {
        AppOpsManager am = (AppOpsManager) activity.getSystemService(Context.APP_OPS_SERVICE);
        int mode = am.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), activity.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private static class DrainApp {
        String pkg, label;
        long fgMin;
        int bucket = -1;
        String impact;
    }

    private void runAnalysis() {
        if (!hasUsageAccess()) {
            scanStatus.setText("Concede el acceso para analizar.");
            scanStatus.setTextColor(0xFFFFB020);
            return;
        }
        scanStatus.setText("Analizando últimas 24 h…");
        scanStatus.setTextColor(0xFF8A90B8);
        new Thread(new Runnable() {
            @Override public void run() {
                final List<DrainApp> result = analyze();
                handler.post(new Runnable() {
                    @Override public void run() { showAnalysis(result); }
                });
            }
        }).start();
    }

    private List<DrainApp> analyze() {
        List<DrainApp> out = new ArrayList<>();
        try {
            UsageStatsManager usm =
                    (UsageStatsManager) activity.getSystemService(Context.USAGE_STATS_SERVICE);
            long end = System.currentTimeMillis();
            long start = end - 24L * 3600 * 1000;
            List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end);
            if (stats == null) return out;
            Map<String, Long> fg = new HashMap<>();
            for (UsageStats s : stats) {
                long t = fg.containsKey(s.getPackageName()) ? fg.get(s.getPackageName()) : 0L;
                fg.put(s.getPackageName(), t + s.getTotalTimeInForeground());
            }
            PackageManager pm = activity.getPackageManager();
            String self = activity.getPackageName();
            for (Map.Entry<String, Long> e : fg.entrySet()) {
                if (e.getKey().equals(self) || e.getValue() <= 0) continue;
                DrainApp d = new DrainApp();
                d.pkg = e.getKey();
                d.fgMin = e.getValue() / 60000;
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(e.getKey(), 0);
                    d.label = String.valueOf(pm.getApplicationLabel(ai));
                } catch (Exception ex) {
                    d.label = e.getKey();
                }
                if (Build.VERSION.SDK_INT >= 28) {
                    try { d.bucket = usm.getAppStandbyBucket(); } catch (Exception ignored) {}
                }
                // impacto: minutos en primer plano + bucket
                boolean hot = d.fgMin >= 45 || (d.bucket == 10 && d.fgMin >= 20);
                boolean warm = d.fgMin >= 15;
                d.impact = hot ? "ALTO" : (warm ? "MEDIO" : "BAJO");
                out.add(d);
            }
            Collections.sort(out, new Comparator<DrainApp>() {
                @Override public int compare(DrainApp a, DrainApp b) {
                    return Long.compare(b.fgMin, a.fgMin);
                }
            });
        } catch (Exception ignored) {}
        return out.size() > 8 ? out.subList(0, 8) : out;
    }

    private void showAnalysis(List<DrainApp> apps) {
        lastDetected.clear();
        appsBox.removeAllViews();
        StringBuilder sig = new StringBuilder();
        if (apps.isEmpty()) {
            appsEmpty.setVisibility(View.VISIBLE);
            appsEmpty.setText("Sin datos de uso en las últimas 24 h.");
            scanStatus.setText("Análisis completo: nada relevante.");
            scanStatus.setTextColor(0xFF3DFF9C);
            return;
        }
        appsEmpty.setVisibility(View.GONE);
        LayoutInflater inf = LayoutInflater.from(activity);
        long max = 1;
        for (DrainApp d : apps) max = Math.max(max, d.fgMin);
        for (DrainApp d : apps) {
            lastDetected.add(d.pkg);
            sig.append(d.pkg).append(':').append(d.fgMin).append(';');
            View v = inf.inflate(R.layout.item_drain_app, appsBox, false);
            TextView name = v.findViewById(R.id.app_name);
            TextView sub = v.findViewById(R.id.app_sub);
            TextView impact = v.findViewById(R.id.app_impact);
            View bar = v.findViewById(R.id.drain_bar);
            name.setText(d.label);
            sub.setText(d.fgMin + " min en pantalla · " + d.pkg
                    + (d.bucket >= 0 ? " · bucket " + d.bucket : ""));
            impact.setText(d.impact);
            impact.setTextColor("ALTO".equals(d.impact) ? 0xFFFF4D5E
                    : ("MEDIO".equals(d.impact) ? 0xFFFFB020 : 0xFF3DFF9C));
            float frac = Math.max(0.04f, Math.min(1f, d.fgMin / (float) max));
            LinearLayout.LayoutParams bp =
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, frac);
            bar.setLayoutParams(bp);
            View spacer = ((LinearLayout) bar.getParent()).getChildAt(1);
            spacer.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f - frac));
            appsBox.addView(v);
            Cine.fadeSlideIn(v, 0);
        }
        lastAppsSig = sig.toString();
        scanStatus.setText(apps.size() + " apps analizadas. Pulsa «Optimizar ahora».");
        scanStatus.setTextColor(0xFF3DFF9C);
    }

    // ------------------------------------------------------------------
    // Optimizar: cierra procesos en segundo plano + guía al sistema
    // ------------------------------------------------------------------

    private void runOptimize() {
        if (lastDetected.isEmpty()) {
            optimizeStatus.setText("Primero pulsa «Analizar consumo».");
            optimizeStatus.setTextColor(0xFFFFB020);
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                final int[] closed = {0};
                try {
                    ActivityManager am =
                            (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
                    for (String pkg : lastDetected) {
                        try {
                            am.killBackgroundProcesses(pkg);
                            closed[0]++;
                        } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}
                handler.post(new Runnable() {
                    @Override public void run() {
                        optimizeStatus.setText("Procesos cerrados en " + closed[0]
                                + " apps. Abre los ajustes del sistema para restringir su batería en segundo plano.");
                        optimizeStatus.setTextColor(0xFF3DFF9C);
                        try {
                            activity.startActivity(new Intent(
                                    Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                        } catch (Exception ignored) {}
                    }
                });
            }
        }).start();
    }

    // ------------------------------------------------------------------

    public void setVisible(boolean v) {
        if (v) {
            permCard.setVisibility(hasUsageAccess() ? View.GONE : View.VISIBLE);
            Intent sticky = activity.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (sticky != null) onBatteryIntent(sticky);
        }
    }

    public void destroy() {
        handler.removeCallbacks(poll);
        try { activity.unregisterReceiver(batteryReceiver); } catch (Exception ignored) {}
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            Intent sticky = activity.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (sticky != null) onBatteryIntent(sticky);
            handler.postDelayed(this, 10000);
        }
    };
}
