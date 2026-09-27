package com.drex.hyperion;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Historial persistente: sesiones VPN y escaneos del Centinela. */
public final class HistoryStore {
    private HistoryStore() {}

    public static class VpnSession {
        public long start, end, queries, blocked;
    }

    public static class ScanRecord {
        public long date; public int apps, threats, warnings;
    }

    private static final String FILE = "hyperion_history.json";
    private static final Object LOCK = new Object();

    private static JSONObject load(Context ctx) {
        synchronized (LOCK) {
            try {
                File f = new File(ctx.getFilesDir(), FILE);
                if (!f.exists()) return new JSONObject();
                FileInputStream in = new FileInputStream(f);
                byte[] buf = new byte[(int) f.length()];
                int r = 0, n;
                while ((n = in.read(buf, r, buf.length - r)) > 0) r += n;
                in.close();
                return new JSONObject(new String(buf, 0, r, "UTF-8"));
            } catch (Exception e) {
                return new JSONObject();
            }
        }
    }

    private static void save(Context ctx, JSONObject o) {
        synchronized (LOCK) {
            try {
                FileOutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), FILE));
                out.write(o.toString().getBytes("UTF-8"));
                out.close();
            } catch (Exception ignored) {}
        }
    }

    public static void addVpnSession(Context ctx, long start, long end, long queries, long blocked) {
        try {
            JSONObject root = load(ctx);
            JSONArray arr = root.optJSONArray("vpn");
            if (arr == null) arr = new JSONArray();
            JSONObject s = new JSONObject();
            s.put("start", start); s.put("end", end);
            s.put("queries", queries); s.put("blocked", blocked);
            arr.put(s);
            while (arr.length() > 30) arr.remove(0);
            root.put("vpn", arr);
            save(ctx, root);
        } catch (Exception ignored) {}
    }

    public static List<VpnSession> getVpnSessions(Context ctx, int max) {
        List<VpnSession> out = new ArrayList<>();
        try {
            JSONArray arr = load(ctx).optJSONArray("vpn");
            if (arr == null) return out;
            for (int i = arr.length() - 1; i >= 0 && out.size() < max; i--) {
                JSONObject s = arr.getJSONObject(i);
                VpnSession v = new VpnSession();
                v.start = s.getLong("start"); v.end = s.getLong("end");
                v.queries = s.getLong("queries"); v.blocked = s.getLong("blocked");
                out.add(v);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static void addScan(Context ctx, int apps, int threats, int warnings) {
        try {
            JSONObject root = load(ctx);
            JSONArray arr = root.optJSONArray("scans");
            if (arr == null) arr = new JSONArray();
            JSONObject s = new JSONObject();
            s.put("date", System.currentTimeMillis());
            s.put("apps", apps); s.put("threats", threats); s.put("warnings", warnings);
            arr.put(s);
            while (arr.length() > 30) arr.remove(0);
            root.put("scans", arr);
            save(ctx, root);
        } catch (Exception ignored) {}
    }

    public static List<ScanRecord> getScans(Context ctx, int max) {
        List<ScanRecord> out = new ArrayList<>();
        try {
            JSONArray arr = load(ctx).optJSONArray("scans");
            if (arr == null) return out;
            for (int i = arr.length() - 1; i >= 0 && out.size() < max; i--) {
                JSONObject s = arr.getJSONObject(i);
                ScanRecord r = new ScanRecord();
                r.date = s.getLong("date"); r.apps = s.getInt("apps");
                r.threats = s.getInt("threats"); r.warnings = s.getInt("warnings");
                out.add(r);
            }
        } catch (Exception ignored) {}
        return out;
    }
}
