package com.drex.hyperion.blocker;

import android.content.Context;
import android.content.SharedPreferences;

import com.drex.hyperion.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * AdBlocker — motor de bloqueo DNS/red de Hyperion 2.0 (Track 5).
 *
 * <p>Carga tres listas de dominios (anuncios, rastreadores/telemetría,
 * amenazas) desde {@code res/raw} y las fusiona con las listas remotas
 * descargadas (si existen). La coincidencia es por sufijo: si la lista
 * contiene {@code ejemplo.com}, también bloquea {@code ads.ejemplo.com}.</p>
 *
 * <p>{@link #categoryOf} consulta además los toggles del usuario: si una
 * categoría está desactivada, sus dominios se consideran permitidos
 * (devuelve {@code null}).</p>
 *
 * <p><b>Protocolo de actualización remota</b> (el coordinador sube los
 * ficheros al repo; este código solo implementa el cliente):</p>
 * <ul>
 *   <li>{@code <UPDATE_BASE>version.txt} → entero plano, p. ej. {@code 2}</li>
 *   <li>{@code <UPDATE_BASE>ads-v2.txt}, {@code trackers-v2.txt},
 *       {@code threats-v2.txt} → mismo formato que los bundled
 *       ({@code threats} admite prefijos {@code miner:}, {@code phishing:},
 *       {@code malware:}; {@code #} inicia comentario)</li>
 * </ul>
 */
public final class AdBlocker {

    /** Base remota de las blocklists (la sube el coordinador al repo). */
    public static final String UPDATE_BASE =
            "https://raw.githubusercontent.com/floreseternasoporte-creator/Hyperion/main/blocklists/";

    public static final String CAT_ADS = "ads";
    public static final String CAT_TRACKER = "tracker";
    public static final String CAT_MALWARE = "malware";
    public static final String CAT_PHISHING = "phishing";
    public static final String CAT_MINER = "miner";

    private static final String PREFS = "hyperion_blocker";
    private static final String KEY_DB_VERSION = "db_version";
    private static final int BUNDLED_VERSION = 1;

    private static volatile AdBlocker instance;

    private final Context appCtx;
    private final SharedPreferences prefs;

    // Referencias volátiles: se reconstruyen y se intercambian de golpe
    // al recargar (update remota), sin bloquear el hot path de consultas.
    private volatile Set<String> ads = new HashSet<String>();
    private volatile Set<String> trackers = new HashSet<String>();
    private volatile Set<String> miners = new HashSet<String>();
    private volatile Set<String> phishing = new HashSet<String>();
    private volatile Set<String> malware = new HashSet<String>();

    private AdBlocker(Context ctx) {
        appCtx = ctx.getApplicationContext();
        prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        loadAll();
    }

    public static AdBlocker get(Context ctx) {
        AdBlocker a = instance;
        if (a == null) {
            synchronized (AdBlocker.class) {
                a = instance;
                if (a == null) {
                    a = new AdBlocker(ctx);
                    instance = a;
                }
            }
        }
        return a;
    }

    // ------------------------------------------------------------------
    // API pública (contrato con el worker de VPN y la UI)
    // ------------------------------------------------------------------

    /**
     * Categoría del dominio, o {@code null} si está permitido.
     * Rápido: HashSet + caminata de sufijos, sin allocations calientes
     * más allá de los pocos substring de la caminata.
     */
    public String categoryOf(String domain) {
        if (domain == null || domain.isEmpty()) return null;
        String d = domain.toLowerCase(Locale.US);
        int len = d.length();
        if (len > 0 && d.charAt(len - 1) == '.') d = d.substring(0, len - 1);

        // Caminata de sufijos: "a.b.ejemplo.com" -> "b.ejemplo.com" -> ...
        String cur = d;
        while (true) {
            if (ads.contains(cur)) return isEnabled(CAT_ADS) ? CAT_ADS : null;
            if (trackers.contains(cur)) return isEnabled(CAT_TRACKER) ? CAT_TRACKER : null;
            if (miners.contains(cur)) return isEnabled(CAT_MINER) ? CAT_MINER : null;
            if (phishing.contains(cur)) return isEnabled(CAT_PHISHING) ? CAT_PHISHING : null;
            if (malware.contains(cur)) return isEnabled(CAT_MALWARE) ? CAT_MALWARE : null;
            int dot = cur.indexOf('.');
            if (dot < 0) return null;
            cur = cur.substring(dot + 1);
        }
    }

    /** true si la categoría está activada por el usuario (por defecto todas). */
    public boolean isEnabled(String category) {
        if (category == null) return false;
        return prefs.getBoolean("cat_" + category, true);
    }

    /** Activa/desactiva una categoría; persiste en SharedPreferences. */
    public void setEnabled(String category, boolean on) {
        if (category == null) return;
        prefs.edit().putBoolean("cat_" + category, on).apply();
    }

    /** Nº de dominios por lista: {ads, trackers, threats(miner+phishing+malware)}. */
    public int[] getListCounts() {
        return new int[]{ads.size(), trackers.size(),
                miners.size() + phishing.size() + malware.size()};
    }

    /** Versión de las listas, p. ej. "v1" (bundled) o "v2" (remota). */
    public String getDbVersion() {
        return "v" + prefs.getInt(KEY_DB_VERSION, BUNDLED_VERSION);
    }

    /** Callback de la actualización remota (se invoca en hilo de fondo). */
    public interface UpdateListener {
        void onResult(boolean ok, String message);
    }

    /**
     * Descarga las listas remotas si hay una versión más nueva, las fusiona
     * (unión) con las bundled y recarga. Siempre corre en su propio hilo;
     * es seguro llamarlo desde el hilo de UI (p. ej. botón "Actualizar listas").
     */
    public void updateFromNetwork(final UpdateListener cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                UpdateListener l = cb;
                try {
                    int remote = fetchVersion();
                    int local = prefs.getInt(KEY_DB_VERSION, BUNDLED_VERSION);
                    if (remote <= local) {
                        if (l != null) l.onResult(true,
                                "Ya tienes las listas más recientes (" + getDbVersion() + ")");
                        return;
                    }
                    File dir = blocklistDir();
                    downloadTo(UPDATE_BASE + "ads-v" + remote + ".txt", new File(dir, "ads.txt"));
                    downloadTo(UPDATE_BASE + "trackers-v" + remote + ".txt", new File(dir, "trackers.txt"));
                    downloadTo(UPDATE_BASE + "threats-v" + remote + ".txt", new File(dir, "threats.txt"));
                    prefs.edit().putInt(KEY_DB_VERSION, remote).apply();
                    loadAll();
                    int[] c = getListCounts();
                    if (l != null) l.onResult(true, "Listas actualizadas a v" + remote
                            + " · " + (c[0] + c[1] + c[2]) + " dominios");
                } catch (Exception e) {
                    if (l != null) l.onResult(false,
                            "No se pudo actualizar: " + e.getMessage());
                }
            }
        }, "hyperion-blocklist-update").start();
    }

    /** Conveniencia sin callback. */
    public void updateFromNetwork() {
        updateFromNetwork(null);
    }

    // ------------------------------------------------------------------
    // Carga
    // ------------------------------------------------------------------

    private synchronized void loadAll() {
        Set<String> a = new HashSet<String>();
        Set<String> t = new HashSet<String>();
        Set<String> mn = new HashSet<String>();
        Set<String> ph = new HashSet<String>();
        Set<String> mw = new HashSet<String>();

        loadRaw(R.raw.ads, a, null);
        loadRaw(R.raw.trackers, t, null);
        loadRaw(R.raw.threats, null, new ThreatSink(mn, ph, mw));

        // Fusión con las remotas descargadas (unión; la remota nunca
        // elimina dominios de la bundled, solo agrega).
        File dir = blocklistDir();
        loadFile(new File(dir, "ads.txt"), a, null);
        loadFile(new File(dir, "trackers.txt"), t, null);
        loadFile(new File(dir, "threats.txt"), null, new ThreatSink(mn, ph, mw));

        ads = a;
        trackers = t;
        miners = mn;
        phishing = ph;
        malware = mw;
    }

    private static final class ThreatSink {
        final Set<String> miners, phishing, malware;
        ThreatSink(Set<String> mn, Set<String> ph, Set<String> mw) {
            miners = mn; phishing = ph; malware = mw;
        }
        void add(String line) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String prefix = line.substring(0, colon);
                String dom = line.substring(colon + 1);
                if (dom.isEmpty()) return;
                if (prefix.equals("miner")) { miners.add(dom); return; }
                if (prefix.equals("phishing")) { phishing.add(dom); return; }
                if (prefix.equals("malware")) { malware.add(dom); return; }
            }
            malware.add(line); // sin prefijo = malware
        }
    }

    private void loadRaw(int resId, Set<String> plain, ThreatSink threats) {
        InputStream in = null;
        try {
            in = appCtx.getResources().openRawResource(resId);
            parseLines(in, plain, threats);
        } catch (Exception ignored) {
        } finally {
            closeQuietly(in);
        }
    }

    private void loadFile(File f, Set<String> plain, ThreatSink threats) {
        if (!f.exists()) return;
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            parseLines(in, plain, threats);
        } catch (Exception ignored) {
        } finally {
            closeQuietly(in);
        }
    }

    private static void parseLines(InputStream in, Set<String> plain, ThreatSink threats)
            throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        String line;
        while ((line = br.readLine()) != null) {
            line = line.trim().toLowerCase(Locale.US);
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            if (threats != null) threats.add(line);
            else if (plain != null) plain.add(line);
        }
    }

    // ------------------------------------------------------------------
    // Red
    // ------------------------------------------------------------------

    private int fetchVersion() throws Exception {
        String s = downloadText(UPDATE_BASE + "version.txt").trim();
        return Integer.parseInt(s);
    }

    private String downloadText(String url) throws Exception {
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "Hyperion-Shield/2.0");
            if (c.getResponseCode() != 200) {
                throw new Exception("HTTP " + c.getResponseCode());
            }
            in = c.getInputStream();
            StringBuilder sb = new StringBuilder(64);
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            closeQuietly(in);
            if (c != null) c.disconnect();
        }
    }

    private void downloadTo(String url, File dest) throws Exception {
        HttpURLConnection c = null;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "Hyperion-Shield/2.0");
            if (c.getResponseCode() != 200) {
                throw new Exception("HTTP " + c.getResponseCode() + " en " + url);
            }
            in = c.getInputStream();
            out = new FileOutputStream(dest);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            closeQuietly(in);
            closeQuietly(out);
            if (c != null) c.disconnect();
        }
    }

    private File blocklistDir() {
        File d = new File(appCtx.getFilesDir(), "blocklists");
        d.mkdirs();
        return d;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (Exception ignored) {}
    }
}
