package com.drex.hyperion;

import android.Manifest;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.drex.hyperion.av.CertCheck;
import com.drex.hyperion.av.DexInspector;
import com.drex.hyperion.av.HashUtil;
import com.drex.hyperion.av.PermissionHeuristics;
import com.drex.hyperion.av.Quarantine;
import com.drex.hyperion.av.SignatureDb;

import java.io.File;
import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Controlador del Centinela: antivirus con base de firmas actualizable,
 * inspección real del dex, revisión de certificados y heurística de permisos.
 *
 * El escaneo es visible por fases: firmas → código → certificados → archivos,
 * mostrando en tiempo real qué app se está analizando.
 */
public class SecurityController {
    // Cadena de prueba estándar EICAR (detección real por contenido)
    private static final String EICAR =
            "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

    private final MainActivity activity;
    private final View root;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean scanning;
    private final SignatureDb sigdb;

    private RadarView radar;
    private TextView status, progress, current, summary, btnScan,
            sigdbLabel, btnSigdbUpdate;
    private LinearLayout threatsBox, historyBox;

    private static class Threat {
        String name, detail, pkg, path;
        int level; // 2 peligro, 1 advertencia
        int score;
        boolean isFile;
    }

    /** Acumulador de veredicto por app a lo largo de las fases. */
    private static class AppVerdict {
        final PackageInfo pi;
        int score = 0;
        final List<String> reasons = new ArrayList<>();
        AppVerdict(PackageInfo pi) { this.pi = pi; }
        void add(int s, String reason) {
            score += s;
            if (reason != null && !reason.isEmpty()) reasons.add(reason);
        }
    }

    public SecurityController(MainActivity activity, View root) {
        this.activity = activity;
        this.root = root;
        sigdb = new SignatureDb(activity);
        radar = root.findViewById(R.id.sec_radar);
        status = root.findViewById(R.id.sec_status);
        progress = root.findViewById(R.id.sec_progress);
        current = root.findViewById(R.id.sec_current);
        summary = root.findViewById(R.id.sec_summary);
        btnScan = root.findViewById(R.id.btn_scan);
        sigdbLabel = root.findViewById(R.id.sec_sigdb);
        btnSigdbUpdate = root.findViewById(R.id.btn_sigdb_update);
        threatsBox = root.findViewById(R.id.sec_threats);
        historyBox = root.findViewById(R.id.sec_history);
        btnScan.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startScan(); }
        });
        btnSigdbUpdate.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { updateDb(true); }
        });
        refreshSigdbLabel();
        refreshHistory();
    }

    public void destroy() { scanning = false; handler.removeCallbacksAndMessages(null); }

    private void refreshSigdbLabel() {
        sigdbLabel.setText("\uD83D\uDEE1 " + sigdb.dbLabel());
    }

    private boolean hasNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    activity.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    /** Botón "Actualizar base": descarga la DB remota y fusiona firmas nuevas. */
    private void updateDb(boolean manual) {
        if (!hasNetwork()) {
            Toast.makeText(activity, "Sin conexión: no se pudo actualizar la base",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (manual) {
            btnSigdbUpdate.setText("Actualizando…");
            btnSigdbUpdate.setAlpha(0.5f);
        }
        sigdb.updateFromNetwork(new SignatureDb.UpdateListener() {
            @Override public void onDone(final boolean ok, final String message) {
                handler.post(new Runnable() {
                    @Override public void run() {
                        refreshSigdbLabel();
                        btnSigdbUpdate.setText("Actualizar base");
                        btnSigdbUpdate.setAlpha(1f);
                        if (manual || !ok) {
                            Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        });
    }

    private void startScan() {
        if (scanning) return;
        // permiso de almacenamiento para escanear Descargas
        if (Build.VERSION.SDK_INT < 33 && activity.checkSelfPermission(
                Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 200);
            Toast.makeText(activity,
                    "Concede el permiso para escanear tus archivos", Toast.LENGTH_LONG).show();
            return;
        }
        scanning = true;
        threatsBox.removeAllViews();
        summary.setVisibility(View.GONE);
        radar.setScanning(true);
        status.setText("Escaneando…");
        status.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
        status.setTextColor(0xFF9AA3FF);
        btnScan.setAlpha(0.4f);

        // auto-actualización silenciosa de la base si hay red
        if (hasNetwork()) updateDb(false);

        new Thread(new Runnable() {
            @Override public void run() {
                List<Threat> found = runScan();
                final List<Threat> result = found;
                handler.post(new Runnable() {
                    @Override public void run() { finishScan(result); }
                });
            }
        }).start();
    }

    /** Escaneo por fases visibles. Devuelve las amenazas encontradas. */
    private List<Threat> runScan() {
        List<Threat> found = new ArrayList<>();
        PackageManager pm = activity.getPackageManager();
        List<PackageInfo> pkgs;
        try {
            pkgs = pm.getInstalledPackages(
                    PackageManager.GET_PERMISSIONS | PackageManager.GET_SIGNATURES);
        } catch (Exception e) {
            return found;
        }
        final int total = pkgs.size();
        Map<String, AppVerdict> verdicts = new LinkedHashMap<>();
        for (PackageInfo pi : pkgs) verdicts.put(pi.packageName, new AppVerdict(pi));

        // FASE 1 — firmas: SHA-256 del APK contra la base
        int done = 0;
        for (AppVerdict v : verdicts.values()) {
            if (!scanning) return found;
            done++;
            postPhase("firmas", v.pi.packageName, done, total);
            String sha = HashUtil.sha256OfFile(
                    new File(v.pi.applicationInfo.sourceDir));
            if (sigdb.matches(sha)) {
                v.score = 100;
                String fam = sigdb.familyOf(sha);
                v.reasons.add(0, "Firma SHA-256 coincide con malware conocido"
                        + (fam != null ? " (" + fam + ")" : ""));
                radarPing(0.2f, 0xFFFF4D5E);
            }
        }

        // FASE 2 — código: inspección real del classes.dex
        done = 0;
        for (AppVerdict v : verdicts.values()) {
            if (!scanning) return found;
            done++;
            postPhase("código", v.pi.packageName, done, total);
            if (v.score >= 100) continue; // ya condenado por firma
            try {
                File apk = new File(v.pi.applicationInfo.sourceDir);
                if (apk.length() > 150L * 1024 * 1024) continue; // límite honesto
                List<DexInspector.Finding> findings = DexInspector.inspect(apk.getAbsolutePath());
                for (DexInspector.Finding f : findings) {
                    v.add(f.weight, "Código: " + f.label);
                }
            } catch (Exception ignored) { }
        }

        // FASE 3 — certificados + permisos
        done = 0;
        for (AppVerdict v : verdicts.values()) {
            if (!scanning) return found;
            done++;
            postPhase("certificados", v.pi.packageName, done, total);
            if (v.score >= 100) continue;
            CertCheck.Result cert = CertCheck.check(activity, v.pi, sigdb);
            for (String r : cert.reasons) v.add(0, r);
            v.score += cert.score;
            StringBuilder why = new StringBuilder();
            int permScore = PermissionHeuristics.score(v.pi, why);
            if (permScore > 0) {
                v.score += permScore;
                v.reasons.add("Permisos: " + why);
            }
        }

        // construir amenazas de apps
        int dangers = 0, warns = 0;
        for (AppVerdict v : verdicts.values()) {
            int score = Math.min(100, v.score);
            if (score < 30) continue;
            boolean system = (v.pi.applicationInfo.flags
                    & ApplicationInfo.FLAG_SYSTEM) != 0;
            Threat t = new Threat();
            try {
                t.name = String.valueOf(pm.getApplicationLabel(v.pi.applicationInfo));
            } catch (Exception e) { t.name = v.pi.packageName; }
            t.pkg = v.pi.packageName;
            t.score = score;
            t.detail = "Riesgo " + score + "/100 · " + join(v.reasons)
                    + (system ? " · app del sistema" : "");
            t.level = score >= 70 ? 2 : 1;
            t.isFile = false;
            found.add(t);
            if (t.level == 2) { dangers++; radarPing(0.2f + 0.6f * dangers / 10f, 0xFFFF4D5E); }
            else { warns++; radarPing(0.3f + 0.5f * warns / 20f, 0xFFFFB020); }
        }
        final int nApps = total;
        scanDownloads(found);

        // guardar nº de apps para el resumen (vía campo en Threat? no: usamos cierre)
        appsScanned = nApps;
        return found;
    }

    private int appsScanned = 0;

    private void postPhase(final String phase, final String pkg,
                           final int done, final int total) {
        handler.post(new Runnable() {
            @Override public void run() {
                current.setText("▸ " + phase + " · Analizando: " + pkg + "…");
                progress.setText(done + "/" + total);
            }
        });
    }

    private void radarPing(final float d, final int color) {
        handler.post(new Runnable() {
            @Override public void run() { radar.ping(d, color); }
        });
    }

    /** FASE 4 — archivos: carpeta Descargas (EICAR, firmas, doble extensión). */
    private void scanDownloads(List<Threat> out) {
        postPhaseRaw("archivos", "tu carpeta Descargas…", 0, 0);
        File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File[] files = dl != null ? dl.listFiles() : null;
        if (files == null) return;
        int i = 0;
        for (File f : files) {
            if (!scanning) break;
            i++;
            if (!f.isFile()) continue;
            String name = f.getName();
            if (i % 3 == 0) postPhaseRaw("archivos", name, i, files.length);
            if (containsEicar(f)) {
                Threat t = new Threat();
                t.name = name; t.path = f.getAbsolutePath();
                t.score = 100;
                t.detail = "Contiene la cadena de prueba EICAR (fichero de prueba de antivirus)";
                t.level = 2; t.isFile = true;
                out.add(t); radarPing(0.5f, 0xFFFF4D5E);
                continue;
            }
            String sha = HashUtil.sha256OfFile(f);
            if (sigdb.matches(sha)) {
                Threat t = new Threat();
                t.name = name; t.path = f.getAbsolutePath();
                t.score = 100;
                String fam = sigdb.familyOf(sha);
                t.detail = "Firma SHA-256 coincide con malware conocido"
                        + (fam != null ? " (" + fam + ")" : "");
                t.level = 2; t.isFile = true;
                out.add(t); radarPing(0.5f, 0xFFFF4D5E);
                continue;
            }
            String low = name.toLowerCase(Locale.US);
            if (low.matches(".*\\.(pdf|jpg|png|mp3|doc|zip)\\.exe$")) {
                Threat t = new Threat();
                t.name = name; t.path = f.getAbsolutePath();
                t.score = 85;
                t.detail = "Doble extensión (.exe oculto): técnica típica de malware";
                t.level = 2; t.isFile = true;
                out.add(t); radarPing(0.6f, 0xFFFF4D5E);
            } else if (low.endsWith(".apk")) {
                Threat t = new Threat();
                t.name = name; t.path = f.getAbsolutePath();
                t.score = 30;
                t.detail = "APK descargado: verifica que venga de una fuente confiable";
                t.level = 1; t.isFile = true;
                out.add(t); radarPing(0.7f, 0xFFFFB020);
            }
        }
    }

    private void postPhaseRaw(final String phase, final String what,
                              final int done, final int total) {
        handler.post(new Runnable() {
            @Override public void run() {
                current.setText("▸ " + phase + " · Analizando: " + what);
                progress.setText(total > 0 ? done + "/" + total : "");
            }
        });
    }

    private boolean containsEicar(File f) {
        if (f.length() > 50 * 1024 * 1024) return false;
        try {
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[4096];
            int n = in.read(buf);
            in.close();
            if (n <= 0) return false;
            String head = new String(buf, 0, n, "ISO-8859-1");
            return head.contains(EICAR);
        } catch (Exception e) { return false; }
    }

    private void finishScan(List<Threat> threats) {
        scanning = false;
        radar.setScanning(false);
        progress.setText("");
        current.setText("");
        btnScan.setAlpha(1f);
        int dangers = 0, warns = 0;
        LayoutInflater inf = LayoutInflater.from(activity);
        for (Threat t : threats) {
            if (t.level == 2) dangers++; else warns++;
            View v = inf.inflate(R.layout.item_threat, threatsBox, false);
            TextView badge = v.findViewById(R.id.threat_badge);
            TextView name = v.findViewById(R.id.threat_name);
            TextView detail = v.findViewById(R.id.threat_detail);
            TextView pkgv = v.findViewById(R.id.threat_pkg);
            TextView action = v.findViewById(R.id.threat_action);
            TextView quar = v.findViewById(R.id.threat_quarantine);
            name.setText(t.name);
            detail.setText(t.detail);
            pkgv.setText(t.isFile ? t.path : t.pkg);
            if (t.level == 2) {
                badge.setText("PELIGRO");
                badge.setBackgroundColor(0xFFFF4D5E);
            } else {
                badge.setText("AVISO");
                badge.setBackgroundColor(0xFFFFB020);
            }
            final Threat ft = t;
            final View card = v;
            if (ft.isFile) {
                action.setText("Borrar archivo");
                quar.setVisibility(View.GONE);
                action.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View vv) {
                        Quarantine.confirmAndDeleteFile(activity, new File(ft.path),
                                new Runnable() {
                                    @Override public void run() {
                                        threatsBox.removeView(card);
                                    }
                                });
                    }
                });
            } else {
                action.setText("Desinstalar");
                refreshQuarButton(quar, ft.pkg);
                action.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View vv) {
                        Quarantine.confirmAndUninstall(activity, ft.pkg, ft.name);
                    }
                });
                quar.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View vv) {
                        boolean on = !Quarantine.isNetworkQuarantined(activity, ft.pkg);
                        boolean bridged = Quarantine.setNetworkQuarantined(
                                activity, ft.pkg, on);
                        refreshQuarButton((TextView) vv, ft.pkg);
                        Toast.makeText(activity,
                                on ? (bridged ? "Sin internet para esta app (cuarentena activa)"
                                        : "Cuarentena guardada: el módulo VPN la aplicará")
                                        : "Cuarentena de red retirada",
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
            threatsBox.addView(v);
        }
        if (dangers == 0 && warns == 0) {
            status.setText("Teléfono limpio");
            status.setCompoundDrawablesWithIntrinsicBounds(
                    R.drawable.ic_check_circle, 0, 0, 0);
            status.setCompoundDrawablePadding(10);
            status.setTextColor(0xFF3DFF9C);
        } else if (dangers == 0) {
            status.setText(warns + " avisos");
            status.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
            status.setTextColor(0xFFFFB020);
        } else {
            status.setText(dangers + " amenazas");
            status.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
            status.setTextColor(0xFFFF4D5E);
        }
        summary.setText("Analizadas " + appsScanned + " apps y tu carpeta Descargas.\n"
                + dangers + " peligros · " + warns + " avisos.\n"
                + sigdb.dbLabel());
        summary.setVisibility(View.VISIBLE);
        summary.setAlpha(0f);
        summary.animate().alpha(1f).setDuration(500).start();
        HistoryStore.addScan(activity, appsScanned, dangers, warns);
        refreshHistory();
    }

    private void refreshQuarButton(TextView quar, String pkg) {
        boolean q = Quarantine.isNetworkQuarantined(activity, pkg);
        quar.setText(q ? "Quitar cuarentena" : "Cuarentena de red");
        quar.setAlpha(q ? 1f : 0.85f);
    }

    private void refreshHistory() {
        historyBox.removeAllViews();
        List<HistoryStore.ScanRecord> recs = HistoryStore.getScans(activity, 5);
        LayoutInflater inf = LayoutInflater.from(activity);
        SimpleDateFormat df = new SimpleDateFormat("d MMM HH:mm", Locale.getDefault());
        if (recs.isEmpty()) {
            TextView t = new TextView(activity);
            t.setText("Aún no hay escaneos registrados.");
            t.setTextColor(0xFF8A90B8); t.setTextSize(13);
            historyBox.addView(t);
            return;
        }
        for (HistoryStore.ScanRecord r : recs) {
            View v = inf.inflate(R.layout.item_history, historyBox, false);
            ((TextView) v.findViewById(R.id.hist_title))
                    .setText(df.format(new Date(r.date)) + " · " + r.apps + " apps");
            boolean clean = r.threats == 0 && r.warnings == 0;
            String sub = clean ? "Limpio"
                    : r.threats + " peligros · " + r.warnings + " avisos";
            TextView subTv = (TextView) v.findViewById(R.id.hist_sub);
            subTv.setText(sub);
            subTv.setCompoundDrawablesWithIntrinsicBounds(
                    clean ? R.drawable.ic_check_circle : 0, 0, 0, 0);
            subTv.setCompoundDrawablePadding(6);
            v.findViewById(R.id.hist_dot).setBackgroundColor(
                    (r.threats == 0 && r.warnings == 0) ? 0xFF3DFF9C
                            : (r.threats > 0 ? 0xFFFF4D5E : 0xFFFFB020));
            historyBox.addView(v);
        }
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append("; ");
            sb.append(l.get(i));
        }
        return sb.toString();
    }
}
