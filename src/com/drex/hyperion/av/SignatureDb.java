package com.drex.hyperion.av;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Base de datos de firmas SHA-256 de malware.
 *
 * Fuentes:
 *  - Semilla incluida en res/raw/signatures.json (EICAR + FluBot + Joker, hashes
 *    verificados contra reportes públicos; ver signatures.json para las fuentes).
 *  - Actualizador remoto: descarga
 *    https://raw.githubusercontent.com/floreseternasoporte-creator/Hyperion/main/signatures.json
 *    y fusiona los hashes nuevos. La versión y la fecha se guardan en
 *    SharedPreferences y se muestran en la UI ("Base v3 · 2026-09-27").
 */
public class SignatureDb {
    public static final String REMOTE_URL =
            "https://raw.githubusercontent.com/floreseternasoporte-creator/Hyperion/main/signatures.json";
    private static final String PREFS = "hyperion_sigdb";
    private static final String KEY_VERSION = "version";
    private static final String KEY_UPDATED = "updated";
    private static final String KEY_EXTRA = "extra_hashes";

    private final Context ctx;
    private final Set<String> hashes = new HashSet<>();
    private int version;
    private String updated;

    public SignatureDb(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        load();
    }

    private void load() {
        hashes.clear();
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        version = p.getInt(KEY_VERSION, 0);
        updated = p.getString(KEY_UPDATED, null);
        // 1) semilla empaquetada
        String seed = readRawResource();
        if (seed != null) parseInto(seed, hashes, true);
        // 2) hashes aprendidos por actualización remota
        Set<String> extra = p.getStringSet(KEY_EXTRA, null);
        if (extra != null) {
            for (String h : extra) hashes.add(norm(h));
        }
    }

    private String readRawResource() {
        try {
            int id = ctx.getResources().getIdentifier(
                    "signatures", "raw", ctx.getPackageName());
            if (id == 0) return null;
            InputStream in = ctx.getResources().openRawResource(id);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    /** Parsea el JSON y mete los hashes en dst. Si takeMeta, actualiza version/fecha. */
    private void parseInto(String json, Set<String> dst, boolean takeMeta) {
        try {
            JSONObject o = new JSONObject(json);
            if (takeMeta) {
                version = o.optInt("version", version);
                String u = o.optString("updated", null);
                if (u != null) updated = u;
            }
            JSONArray arr = o.optJSONArray("sha256");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) dst.add(norm(arr.getString(i)));
            }
            JSONArray samples = o.optJSONArray("samples");
            if (samples != null) {
                for (int i = 0; i < samples.length(); i++) {
                    String h = samples.getJSONObject(i).optString("sha256", null);
                    if (h != null) dst.add(norm(h));
                }
            }
        } catch (Exception ignored) { }
    }

    private static String norm(String h) {
        return h == null ? "" : h.trim().toLowerCase(java.util.Locale.US);
    }

    public boolean matches(String sha256) {
        return sha256 != null && hashes.contains(norm(sha256));
    }

    public int size() { return hashes.size(); }
    public int version() { return version; }
    public String updated() { return updated == null ? "—" : updated; }

    /** Texto para la UI: "Base v3 · 2026-09-27 · 3 firmas". */
    public String dbLabel() {
        return "Base v" + version + " · " + updated()
                + " · " + size() + " firmas";
    }

    /** Devuelve la familia asociada al hash si la semilla la documenta (o null). */
    public String familyOf(String sha256) {
        try {
            String seed = readRawResource();
            if (seed == null) return null;
            JSONArray samples = new JSONObject(seed).optJSONArray("samples");
            if (samples == null) return null;
            String want = norm(sha256);
            for (int i = 0; i < samples.length(); i++) {
                JSONObject s = samples.getJSONObject(i);
                if (want.equals(norm(s.optString("sha256", "")))) {
                    return s.optString("family", null);
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    public interface UpdateListener {
        void onDone(boolean ok, String message);
    }

    /**
     * Descarga la DB remota en un hilo de fondo y fusiona hashes nuevos.
     * Solo actualiza si la versión remota es mayor que la local.
     */
    public void updateFromNetwork(final UpdateListener listener) {
        new Thread(new Runnable() {
            @Override public void run() {
                String msg;
                boolean ok = false;
                try {
                    HttpURLConnection c = (HttpURLConnection)
                            new URL(REMOTE_URL).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(20000);
                    c.setRequestProperty("User-Agent", "HyperionAV/1.0");
                    InputStream in = c.getInputStream();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                    in.close();
                    String json = bos.toString("UTF-8");
                    JSONObject o = new JSONObject(json);
                    int remoteVersion = o.optInt("version", 0);
                    if (remoteVersion > version) {
                        Set<String> merged = new HashSet<>(hashes);
                        parseInto(json, merged, false);
                        Set<String> newOnes = new HashSet<>(merged);
                        newOnes.removeAll(hashes);
                        SharedPreferences p = ctx.getSharedPreferences(
                                PREFS, Context.MODE_PRIVATE);
                        Set<String> extra = new HashSet<>(
                                p.getStringSet(KEY_EXTRA, new HashSet<String>()));
                        extra.addAll(newOnes);
                        p.edit()
                                .putInt(KEY_VERSION, remoteVersion)
                                .putString(KEY_UPDATED, o.optString("updated", updated()))
                                .putStringSet(KEY_EXTRA, extra)
                                .apply();
                        load();
                        msg = "Base actualizada a v" + remoteVersion
                                + " (+" + newOnes.size() + " firmas)";
                        ok = true;
                    } else {
                        msg = "La base ya está al día (" + dbLabel() + ")";
                        ok = true;
                    }
                } catch (Exception e) {
                    msg = "No se pudo actualizar: sin conexión o servidor inaccesible";
                }
                final boolean fok = ok;
                final String fmsg = msg;
                // el listener decide en qué hilo corre; se llama desde el hilo de fondo
                try { listener.onDone(fok, fmsg); } catch (Exception ignored) { }
            }
        }).start();
    }

    /** Nombres de familias conocidas (para depuración). */
    public Set<String> knownFamilies() {
        Set<String> f = new HashSet<>();
        try {
            String seed = readRawResource();
            if (seed == null) return f;
            JSONArray samples = new JSONObject(seed).optJSONArray("samples");
            if (samples == null) return f;
            for (int i = 0; i < samples.length(); i++) {
                f.add(samples.getJSONObject(i).optString("family", "?"));
            }
        } catch (Exception ignored) { }
        return f;
    }
}
