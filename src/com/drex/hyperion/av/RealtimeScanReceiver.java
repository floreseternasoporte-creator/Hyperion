package com.drex.hyperion.av;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * Protección en tiempo real: el sistema avisa cuando se instala un paquete
 * (ACTION_PACKAGE_ADDED con scheme "package" está exento del veto a
 * broadcasts implícitos, por lo que este receiver declarado en el manifest
 * sigue funcionando en Android 8+).
 *
 * Al instalarse una app se hace un escaneo rápido real (firmas SHA-256 +
 * inspección del dex + certificado) y, si el score es alto, se publica una
 * notificación de "Amenaza detectada".
 *
 * Declaración en AndroidManifest.xml (aditiva):
 *   <receiver android:name=".av.RealtimeScanReceiver" android:exported="true">
 *       <intent-filter>
 *           <action android:name="android.intent.action.PACKAGE_ADDED" />
 *           <data android:scheme="package" />
 *       </intent-filter>
 *   </receiver>
 */
public class RealtimeScanReceiver extends BroadcastReceiver {

    private static final String CHANNEL = "hyperion_realtime";
    private static final int THREAT_SCORE = 70;

    @Override
    public void onReceive(final Context context, Intent intent) {
        if (intent == null) return;
        if (!Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) return;
        // Ignorar actualizaciones de una app ya instalada: solo instalaciones nuevas
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
        if (intent.getData() == null) return;
        final String pkg = intent.getData().getSchemeSpecificPart();
        if (pkg == null || pkg.equals(context.getPackageName())) return;

        final Context appCtx = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                quickScan(appCtx, pkg);
            }
        }).start();
    }

    private void quickScan(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            @SuppressWarnings("deprecation")
            PackageInfo pi = pm.getPackageInfo(pkg,
                    PackageManager.GET_PERMISSIONS | PackageManager.GET_SIGNATURES);
            if (pi == null || pi.applicationInfo == null) return;

            SignatureDb db = new SignatureDb(ctx);
            int score = 0;
            StringBuilder why = new StringBuilder();

            // 1) firma SHA-256 del APK
            String apkSha = HashUtil.sha256OfFile(
                    new java.io.File(pi.applicationInfo.sourceDir));
            if (db.matches(apkSha)) {
                score = 100;
                String fam = db.familyOf(apkSha);
                why.append("Firma en la base de malware")
                   .append(fam != null ? " (" + fam + ")" : "");
            } else {
                // 2) inspección real del dex (solo APKs razonables)
                java.util.List<DexInspector.Finding> findings =
                        DexInspector.inspect(pi.applicationInfo.sourceDir);
                int dexScore = DexInspector.totalWeight(findings);
                if (dexScore > 0) {
                    score += dexScore;
                    why.append("APIs sospechosas en el código (")
                       .append(findings.size()).append(" hallazgos)");
                }
                // 3) certificado
                CertCheck.Result cert = CertCheck.check(ctx, pi, db);
                if (cert.score > 0) {
                    score += cert.score;
                    if (why.length() > 0) why.append("; ");
                    why.append(join(cert.reasons));
                }
                // 4) heurística de permisos (reutiliza el motor del escáner)
                int permScore = PermissionHeuristics.score(pi, why);
                score += permScore;
            }

            if (score >= THREAT_SCORE) {
                String label;
                try {
                    label = String.valueOf(
                            pm.getApplicationLabel(pi.applicationInfo));
                } catch (Exception e) { label = pkg; }
                notifyThreat(ctx, label, pkg, score, why.toString());
            }
        } catch (Exception ignored) {
            // un fallo en el escaneo en tiempo real nunca debe tumbar el receiver
        }
    }

    private static String join(java.util.List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append("; ");
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    private void notifyThreat(Context ctx, String label, String pkg,
                              int score, String why) {
        NotificationManager nm = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "Protección en tiempo real",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Avisos cuando se instala una app peligrosa");
            nm.createNotificationChannel(ch);
        }
        String text = label + " (" + pkg + ") · riesgo " + score
                + "/100" + (why.isEmpty() ? "" : " · " + why);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CHANNEL)
                : new Notification.Builder(ctx);
        b.setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Amenaza detectada")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true);
        nm.notify(pkg.hashCode(), b.build());
    }
}
