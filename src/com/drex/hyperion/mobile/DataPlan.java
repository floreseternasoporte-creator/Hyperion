package com.drex.hyperion.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.TrafficStats;
import android.os.Build;

import com.drex.hyperion.R;

import java.util.Calendar;
import java.util.Locale;

/**
 * DataPlan — plan de datos móviles REAL del usuario, medido con TrafficStats.
 *
 * <p>El usuario registra los GB de su plan y el día de inicio de su ciclo de
 * facturación. El consumo se calcula como:
 * {@code acumulado + (contadorActual - baseline)}, donde la baseline se toma
 * al inicio de cada ciclo.</p>
 *
 * <p>Supervivencia a reinicios: los contadores de TrafficStats se reinician al
 * apagar el teléfono. Si el contador actual es menor que el último visto, hubo
 * un reboot: lo consumido antes del reboot se pliega en "acumulado" y la
 * baseline se reancla al contador nuevo, sin perder la cuenta.</p>
 *
 * <p>Cambio de ciclo: el día de inicio del ciclo se detecta por fecha; al
 * entrar a un ciclo nuevo todo se reinicia solo (baseline nueva, acumulado a
 * cero, alertas rearmadas).</p>
 *
 * <p>REGLA DE ORO: todo medido de verdad. Jamás se simula consumo.</p>
 */
public class DataPlan {
    private static final String PREFS = "hyperion_plan";
    private static final String CH_ID = "hyperion_data_alerts";
    private static final long GB = 1073741824L;
    private static final long DAY_MS = 86400000L;
    private static final int[] THRESHOLDS = {50, 80, 100};

    // ------------------------------------------------------------------
    // Configuración del plan
    // ------------------------------------------------------------------

    public static boolean hasPlan(Context c) {
        return prefs(c).contains("plan_gb");
    }

    public static float getPlanGb(Context c) {
        return prefs(c).getFloat("plan_gb", 0f);
    }

    public static int getPlanDay(Context c) {
        return prefs(c).getInt("plan_day", 1);
    }

    public static long getPlanBytes(Context c) {
        return (long) (getPlanGb(c) * GB);
    }

    /** Guarda el plan y reinicia la medición del ciclo actual. */
    public static void savePlan(Context c, float gb, int day) {
        SharedPreferences p = prefs(c);
        long total = mobileTotal();
        Cycle cy = cycleFor(day, System.currentTimeMillis());
        SharedPreferences.Editor e = p.edit();
        e.putFloat("plan_gb", gb);
        e.putInt("plan_day", day);
        e.putString("cycle_id", cy.id);
        e.putLong("base", Math.max(total, 0));
        e.putLong("accum", 0);
        e.putLong("last", Math.max(total, 0));
        for (int t : THRESHOLDS) e.remove("alert_" + t + "_" + cy.id);
        e.apply();
    }

    public static void clearPlan(Context c) {
        prefs(c).edit().clear().apply();
    }

    // ------------------------------------------------------------------
    // Ciclo de facturación
    // ------------------------------------------------------------------

    public static class Cycle {
        public String id;        // "yyyyMMdd" del inicio del ciclo
        public long startMs;     // 00:00 del primer día del ciclo
        public long nextStartMs; // 00:00 del primer día del ciclo siguiente
        public int totalDays;
        public int daysLeft;     // incluye hoy
        public int daysElapsed;  // días completos ya pasados
    }

    /** Calcula el ciclo vigente para el día de inicio dado. */
    public static Cycle cycleFor(int startDay, long nowMs) {
        Calendar now = Calendar.getInstance();
        now.setTimeInMillis(nowMs);
        int d = Math.max(1, Math.min(31, startDay));

        Calendar start = Calendar.getInstance();
        start.setTimeInMillis(nowMs);
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);

        int maxThis = start.getActualMaximum(Calendar.DAY_OF_MONTH);
        int dayThis = Math.min(d, maxThis);
        if (now.get(Calendar.DAY_OF_MONTH) < dayThis) {
            start.add(Calendar.MONTH, -1);
        }
        int maxStart = start.getActualMaximum(Calendar.DAY_OF_MONTH);
        start.set(Calendar.DAY_OF_MONTH, Math.min(d, maxStart));

        Calendar next = (Calendar) start.clone();
        next.add(Calendar.MONTH, 1);
        int maxNext = next.getActualMaximum(Calendar.DAY_OF_MONTH);
        next.set(Calendar.DAY_OF_MONTH, Math.min(d, maxNext));

        Cycle cy = new Cycle();
        cy.startMs = start.getTimeInMillis();
        cy.nextStartMs = next.getTimeInMillis();
        cy.id = String.format(Locale.US, "%04d%02d%02d",
                start.get(Calendar.YEAR),
                start.get(Calendar.MONTH) + 1,
                start.get(Calendar.DAY_OF_MONTH));
        cy.totalDays = (int) ((cy.nextStartMs - cy.startMs) / DAY_MS);
        long left = cy.nextStartMs - nowMs;
        cy.daysLeft = (int) ((left + DAY_MS - 1) / DAY_MS);
        if (cy.daysLeft < 1) cy.daysLeft = 1;
        cy.daysElapsed = cy.totalDays - cy.daysLeft;
        if (cy.daysElapsed < 0) cy.daysElapsed = 0;
        return cy;
    }

    // ------------------------------------------------------------------
    // Medición (TrafficStats + baseline + reboot)
    // ------------------------------------------------------------------

    private static long mobileTotal() {
        long rx = TrafficStats.getMobileRxBytes();
        long tx = TrafficStats.getMobileTxBytes();
        if (rx < 0 && tx < 0) return -1; // dispositivo sin soporte
        return Math.max(rx, 0) + Math.max(tx, 0);
    }

    /**
     * Actualiza la medición y devuelve los bytes usados en el ciclo actual.
     * Devuelve -1 si el dispositivo no reporta contadores móviles.
     * También dispara las alertas de umbral si corresponde.
     */
    public static long updateUsage(Context c) {
        if (!hasPlan(c)) return -1;
        long total = mobileTotal();
        if (total < 0) return -1;

        SharedPreferences p = prefs(c);
        Cycle cy = cycleFor(getPlanDay(c), System.currentTimeMillis());
        String storedId = p.getString("cycle_id", null);

        long base, accum, last;
        if (!cy.id.equals(storedId)) {
            // Cambio de ciclo: reinicio solo
            base = total;
            accum = 0;
            last = total;
        } else {
            base = p.getLong("base", total);
            accum = p.getLong("accum", 0);
            last = p.getLong("last", total);
            if (total < last) {
                // Reboot: el contador se reinició; plegar lo previo
                accum += Math.max(0, last - base);
                base = total;
            }
            last = total;
        }
        SharedPreferences.Editor e = p.edit();
        e.putString("cycle_id", cy.id);
        e.putLong("base", base);
        e.putLong("accum", accum);
        e.putLong("last", last);
        e.apply();

        long used = accum + Math.max(0, total - base);
        checkAlerts(c, cy, used);
        return used;
    }

    /**
     * Llamado al arrancar el teléfono: pliega el consumo previo al reboot en
     * el acumulado para no perder la cuenta aunque la app no esté abierta.
     */
    public static void onBoot(Context c) {
        if (!hasPlan(c)) return;
        SharedPreferences p = prefs(c);
        long base = p.getLong("base", -1);
        long last = p.getLong("last", -1);
        if (base < 0 || last < 0) return;
        long accum = p.getLong("accum", 0) + Math.max(0, last - base);
        p.edit().putLong("accum", accum).putLong("base", 0).putLong("last", 0).apply();
    }

    // ------------------------------------------------------------------
    // Alertas 50 / 80 / 100 % (una vez por ciclo y umbral)
    // ------------------------------------------------------------------

    private static void checkAlerts(Context c, Cycle cy, long used) {
        long planBytes = getPlanBytes(c);
        if (planBytes <= 0) return;
        SharedPreferences p = prefs(c);
        for (int t : THRESHOLDS) {
            String key = "alert_" + t + "_" + cy.id;
            if (p.getBoolean(key, false)) continue;
            if (used * 100 < planBytes * t) continue;
            p.edit().putBoolean(key, true).apply();
            notifyThreshold(c, t, used, planBytes);
        }
    }

    private static void notifyThreshold(Context c, int pct, long used, long planBytes) {
        NotificationManager nm =
                (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    CH_ID, "Alertas de datos", NotificationManager.IMPORTANCE_DEFAULT));
        }
        String text;
        if (pct >= 100) {
            text = "Plan agotado: usaste " + fmtGb(used) + ".";
        } else {
            long left = Math.max(0, planBytes - used);
            text = "Vas por el " + pct + "% · quedan " + fmtGb(left) + ".";
        }
        Notification n = new Notification.Builder(c, CH_ID)
                .setContentTitle("Hyperion · Datos móviles")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher_hyperion)
                .setAutoCancel(true)
                .build();
        nm.notify(9000 + pct, n);
    }

    // ------------------------------------------------------------------
    // Proyección honesta
    // ------------------------------------------------------------------

    /**
     * Proyección con el promedio diario del ciclo actual.
     * Devuelve null si aún no hay datos suficientes para proyectar.
     */
    public static String projection(Context c, long used) {
        if (!hasPlan(c)) return null;
        Cycle cy = cycleFor(getPlanDay(c), System.currentTimeMillis());
        if (cy.daysElapsed < 1) return null; // primer día: sin promedio aún
        long planBytes = getPlanBytes(c);
        double avg = (double) used / cy.daysElapsed;
        double projected = avg * cy.totalDays;
        if (projected <= planBytes) return "Ritmo: te alcanza";
        return "Proyección: faltan ~" + fmtGb((long) (projected - planBytes));
    }

    // ------------------------------------------------------------------
    // Formato (coma decimal, textos cortos)
    // ------------------------------------------------------------------

    private static final Locale ES = new Locale("es");

    /** "3,2 GB" · "850 MB" · "12 KB" */
    public static String fmtGb(long bytes) {
        if (bytes >= GB) return String.format(ES, "%.1f GB", bytes / (double) GB);
        if (bytes >= 1048576L) return String.format(ES, "%.0f MB", bytes / 1048576.0);
        if (bytes >= 1024L) return String.format(ES, "%.0f KB", bytes / 1024.0);
        return bytes + " B";
    }

    /** "8 GB" · "8,5 GB" */
    public static String fmtPlanGb(float gb) {
        if (gb == Math.floor(gb)) return String.format(ES, "%.0f GB", gb);
        return String.format(ES, "%.1f GB", gb);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
