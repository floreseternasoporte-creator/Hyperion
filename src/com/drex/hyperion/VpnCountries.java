package com.drex.hyperion;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Catálogo honesto de ubicaciones VPN.
 *
 * El túnel real de Hyperion 2.0 es LOCAL (VpnService en este teléfono):
 * no hay servidor remoto desplegado, así que NINGÚN país se muestra
 * como "conectado". Los países de res/raw/servers.json existen con
 * status "soon" y la UI los muestra deshabilitados con la etiqueta
 * "Próximamente". Regla de oro: jamás fingir una conexión a un país.
 */
public final class VpnCountries {
    private VpnCountries() {}

    public static final String STATUS_SOON = "soon";
    public static final String STATUS_ACTIVE = "active"; // reservado: solo con túnel real

    public static class Country {
        public String country = "";
        public String code = "";
        public String city = "";
        public String status = STATUS_SOON;
        public String note = "";
    }

    /** Lee res/raw/servers.json. Si falla el parseo, devuelve lista vacía (fail-safe). */
    public static List<Country> load(Context ctx) {
        List<Country> out = new ArrayList<>();
        try {
            InputStream in = ctx.getResources().openRawResource(R.raw.servers);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            String json = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            for (String obj : splitObjects(json)) {
                Country c = new Country();
                c.country = field(obj, "country");
                c.code = field(obj, "code");
                c.city = field(obj, "city");
                c.status = field(obj, "status");
                if (c.status.isEmpty()) c.status = STATUS_SOON;
                c.note = field(obj, "note");
                if (!c.country.isEmpty()) out.add(c);
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Mensaje honesto para el diálogo de un país "Próximamente". */
    public static String soonMessage(Country c) {
        String note = c.note.isEmpty()
                ? "Requiere servidor propio; sin fingir conexiones." : c.note;
        return "Aún no hay servidores en " + c.country + ".\n\n" + note
                + "\n\nEsta app nunca finge una conexión: ningún país se muestra "
                + "como conectado sin un túnel real.";
    }

    // ---- mini-parser JSON suficiente para [{"k":"v",...}] ----

    private static List<String> splitObjects(String json) {
        List<String> objs = new ArrayList<>();
        int depth = 0, start = -1;
        boolean inStr = false, esc = false;
        for (int i = 0; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (ch == '\\') esc = true;
                else if (ch == '"') inStr = false;
                continue;
            }
            if (ch == '"') inStr = true;
            else if (ch == '{') { if (depth == 0) start = i; depth++; }
            else if (ch == '}') { depth--; if (depth == 0 && start >= 0) { objs.add(json.substring(start, i + 1)); start = -1; } }
        }
        return objs;
    }

    private static String field(String obj, String key) {
        String needle = "\"" + key + "\"";
        int k = obj.indexOf(needle);
        if (k < 0) return "";
        int colon = obj.indexOf(':', k + needle.length());
        if (colon < 0) return "";
        int q1 = obj.indexOf('"', colon + 1);
        if (q1 < 0) return "";
        StringBuilder sb = new StringBuilder();
        boolean esc = false;
        for (int i = q1 + 1; i < obj.length(); i++) {
            char ch = obj.charAt(i);
            if (esc) { sb.append(ch); esc = false; }
            else if (ch == '\\') esc = true;
            else if (ch == '"') break;
            else sb.append(ch);
        }
        return sb.toString();
    }
}
