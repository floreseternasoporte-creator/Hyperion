package com.drex.hyperion.blocker;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import com.drex.hyperion.HyperionVpnService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DataSaver — "Ahorro de datos" honesto de Hyperion 2.0 (Track 5).
 *
 * <p><b>Hyperion NO regala datos móviles</b> (eso lo controla la operadora y
 * ninguna app puede hacerlo). Lo que sí hace es <b>recuperar</b> los datos
 * que los anuncios, rastreadores y telemetría te robaban: cada petición
 * bloqueada por el escudo evita una descarga, y aquí se acumula.</p>
 *
 * <p><b>Cómo se estima el ahorro (honestidad):</b> medir los bytes exactos
 * ahorrados exigiría inspeccionar el cuerpo de cada respuesta bloqueada,
 * pero el escudo bloquea a nivel DNS —la descarga nunca ocurre— así que el
 * tamaño real es incognoscible. En su lugar se usa una estimación fija y
 * documentada por categoría (ver constantes EST_*_BYTES). La UI DEBE decir
 * "estimado" junto a cada cifra. Son valores conservadores basados en el
 * peso típico de un creative publicitario (~120 KB), un beacon de
 * analítica (~40 KB), una carga maliciosa típica (~60 KB) y un minero
 * (~80 KB de scripts).</p>
 *
 * <p>Se registra como {@link DnsEventListener} en
 * {@code HyperionVpnService.setDnsEventListener(...)} (lo implementa el
 * worker de VPN).</p>
 */
public final class DataSaver {

    // Estimación por petición bloqueada, por categoría. La UI debe
    // etiquetar las cifras como ESTIMADAS.
    public static final long EST_ADS_BYTES = 120L * 1024;
    public static final long EST_TRACKER_BYTES = 40L * 1024;
    public static final long EST_THREAT_BYTES = 60L * 1024; // malware/phishing
    public static final long EST_MINER_BYTES = 80L * 1024;

    /** Meta visual de la UI: 1 GB. */
    public static final long GOAL_BYTES = 1024L * 1024 * 1024;

    private static final String PREFS = "hyperion_datasaver";
    private static final String K_BYTES = "total_bytes";
    private static final String K_BLOCKED = "total_blocked";
    private static final String K_FIRST = "first_seen";
    private static final String K_APPS = "apps_v1";
    private static final int MAX_APPS_STORED = 60;
    private static final int PERSIST_EVERY_N_EVENTS = 25;

    private static volatile DataSaver instance;

    private final Context appCtx;
    private final SharedPreferences prefs;
    private final AtomicLong savedBytes = new AtomicLong();
    private final AtomicLong blockedCount = new AtomicLong();
    private final AtomicLong catAds = new AtomicLong();
    private final AtomicLong catTracker = new AtomicLong();
    private final AtomicLong catThreat = new AtomicLong();
    private final AtomicLong catMiner = new AtomicLong();
    private final Map<String, long[]> perApp = new HashMap<String, long[]>(); // pkg -> {blocked, bytes}
    private final Object appLock = new Object();
    private int eventsSincePersist;
    private volatile boolean listenerActive;

    /** Estadística por app para la UI. */
    public static final class AppStat {
        public final String packageName;
        public final String label;
        public final long blocked;
        public final long bytes;
        AppStat(String pkg, String label, long blocked, long bytes) {
            this.packageName = pkg; this.label = label;
            this.blocked = blocked; this.bytes = bytes;
        }
    }

    private DataSaver(Context ctx) {
        appCtx = ctx.getApplicationContext();
        prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        savedBytes.set(prefs.getLong(K_BYTES, 0));
        blockedCount.set(prefs.getLong(K_BLOCKED, 0));
        catAds.set(prefs.getLong("cat_ads_n", 0));
        catTracker.set(prefs.getLong("cat_tracker_n", 0));
        catThreat.set(prefs.getLong("cat_threat_n", 0));
        catMiner.set(prefs.getLong("cat_miner_n", 0));
        if (prefs.getLong(K_FIRST, 0) == 0) {
            prefs.edit().putLong(K_FIRST, System.currentTimeMillis()).apply();
        }
        loadApps();
        registerListener();
    }

    public static DataSaver get(Context ctx) {
        DataSaver d = instance;
        if (d == null) {
            synchronized (DataSaver.class) {
                d = instance;
                if (d == null) {
                    d = new DataSaver(ctx);
                    instance = d;
                }
            }
        }
        return d;
    }

    /** true si el registro en el servicio VPN tuvo éxito. */
    public boolean isListenerActive() {
        return listenerActive;
    }

    // ------------------------------------------------------------------
    // Eventos (los invoca el worker de VPN en sus hilos)
    // ------------------------------------------------------------------

    private void registerListener() {
        try {
            HyperionVpnService.setDnsEventListener(new DnsEventListener() {
                @Override public void onDnsEvent(String domain, int uid,
                                                 boolean blocked, String category) {
                    if (blocked) onBlocked(domain, uid, category);
                }
            });
            listenerActive = true;
        } catch (Throwable t) {
            // El worker de VPN aún no expone setDnsEventListener: se reintentará
            // en el próximo get(). Sin esto, no hay conteo (nada fake).
            listenerActive = false;
        }
    }

    /** Reintenta el registro si falló (llamar al entrar a la pantalla Escudo). */
    public void ensureRegistered() {
        if (!listenerActive) registerListener();
    }

    private void onBlocked(String domain, int uid, String category) {
        long est = estimateFor(category);
        blockedCount.incrementAndGet();
        savedBytes.addAndGet(est);
        if (AdBlocker.CAT_ADS.equals(category)) catAds.incrementAndGet();
        else if (AdBlocker.CAT_TRACKER.equals(category)) catTracker.incrementAndGet();
        else if (AdBlocker.CAT_MINER.equals(category)) catMiner.incrementAndGet();
        else catThreat.incrementAndGet(); // malware / phishing / desconocido

        String pkg = packageForUid(uid);
        if (pkg != null) {
            synchronized (appLock) {
                long[] e = perApp.get(pkg);
                if (e == null) {
                    e = new long[2];
                    perApp.put(pkg, e);
                }
                e[0]++;
                e[1] += est;
                if (perApp.size() > MAX_APPS_STORED * 2) pruneApps();
            }
        }
        if (++eventsSincePersist >= PERSIST_EVERY_N_EVENTS) {
            eventsSincePersist = 0;
            persist();
        }
    }

    private static long estimateFor(String category) {
        if (AdBlocker.CAT_ADS.equals(category)) return EST_ADS_BYTES;
        if (AdBlocker.CAT_TRACKER.equals(category)) return EST_TRACKER_BYTES;
        if (AdBlocker.CAT_MINER.equals(category)) return EST_MINER_BYTES;
        return EST_THREAT_BYTES; // malware, phishing o null
    }

    private String packageForUid(int uid) {
        try {
            return appCtx.getPackageManager().getNameForUid(uid);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // API para la UI
    // ------------------------------------------------------------------

    /** Bytes ahorrados (estimados) acumulados. */
    public long getSavedBytes() {
        return savedBytes.get();
    }

    /** Nº total de peticiones bloqueadas. */
    public long getBlockedCount() {
        return blockedCount.get();
    }

    /** Bloqueos por categoría: {ads, tracker, threat, miner}. */
    public long[] getBlockedByCategory() {
        return new long[]{catAds.get(), catTracker.get(), catThreat.get(), catMiner.get()};
    }

    /**
     * Top N apps por bytes ahorrados (las que "más consumían").
     * La etiqueta se resuelve aquí; si falla, se usa el nombre del paquete.
     */
    public List<AppStat> getTopApps(int n) {
        List<AppStat> out = new ArrayList<AppStat>();
        PackageManager pm = appCtx.getPackageManager();
        synchronized (appLock) {
            for (Map.Entry<String, long[]> e : perApp.entrySet()) {
                String pkg = e.getKey();
                String label = pkg;
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    CharSequence l = pm.getApplicationLabel(ai);
                    if (l != null) label = l.toString();
                } catch (Exception ignored) {}
                out.add(new AppStat(pkg, label, e.getValue()[0], e.getValue()[1]));
            }
        }
        Collections.sort(out, new Comparator<AppStat>() {
            @Override public int compare(AppStat a, AppStat b) {
                return Long.compare(b.bytes, a.bytes);
            }
        });
        if (out.size() > n) return out.subList(0, n);
        return out;
    }

    /**
     * Promedio diario de bytes ahorrados desde la primera medición.
     * 0 si aún no hay datos.
     */
    public double getDailyAverage() {
        long first = prefs.getLong(K_FIRST, 0);
        if (first == 0) return 0;
        long days = (System.currentTimeMillis() - first) / 86400000L + 1;
        if (days < 1) days = 1;
        return (double) savedBytes.get() / (double) days;
    }

    /**
     * Días estimados para alcanzar la meta con el ritmo actual.
     * @return 0 si la meta ya se alcanzó; -1 si aún no hay datos
     *         ("midiendo tu ritmo").
     */
    public long projectDaysToGoal(long goalBytes) {
        long remaining = goalBytes - savedBytes.get();
        if (remaining <= 0) return 0;
        double avg = getDailyAverage();
        if (avg <= 0) return -1;
        return (long) Math.ceil(remaining / avg);
    }

    /** Guarda el estado ahora (llamar al pausar/destruir la pantalla). */
    public void flush() {
        persist();
    }

    // ------------------------------------------------------------------
    // Persistencia
    // ------------------------------------------------------------------

    private void persist() {
        SharedPreferences.Editor e = prefs.edit();
        e.putLong(K_BYTES, savedBytes.get());
        e.putLong(K_BLOCKED, blockedCount.get());
        e.putLong("cat_ads_n", catAds.get());
        e.putLong("cat_tracker_n", catTracker.get());
        e.putLong("cat_threat_n", catThreat.get());
        e.putLong("cat_miner_n", catMiner.get());
        synchronized (appLock) {
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (Map.Entry<String, long[]> en : perApp.entrySet()) {
                if (n++ >= MAX_APPS_STORED) break;
                if (sb.length() > 0) sb.append(';');
                sb.append(en.getKey()).append('|')
                  .append(en.getValue()[0]).append('|').append(en.getValue()[1]);
            }
            e.putString(K_APPS, sb.toString());
        }
        e.apply();
    }

    private void loadApps() {
        String s = prefs.getString(K_APPS, "");
        if (s.isEmpty()) return;
        synchronized (appLock) {
            for (String part : s.split(";")) {
                String[] f = part.split("\\|");
                if (f.length != 3) continue;
                try {
                    perApp.put(f[0],
                            new long[]{Long.parseLong(f[1]), Long.parseLong(f[2])});
                } catch (Exception ignored) {}
            }
        }
    }

    private void pruneApps() {
        // Mantiene solo las 60 apps con más bytes ahorrados.
        List<Map.Entry<String, long[]>> list =
                new ArrayList<Map.Entry<String, long[]>>(perApp.entrySet());
        Collections.sort(list, new Comparator<Map.Entry<String, long[]>>() {
            @Override public int compare(Map.Entry<String, long[]> a,
                                         Map.Entry<String, long[]> b) {
                return Long.compare(b.getValue()[1], a.getValue()[1]);
            }
        });
        perApp.clear();
        int n = 0;
        for (Map.Entry<String, long[]> e : list) {
            if (n++ >= MAX_APPS_STORED) break;
            perApp.put(e.getKey(), e.getValue());
        }
    }
}
