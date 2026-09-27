package com.drex.hyperion;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cliente de la API pública de VPNGate (http://www.vpngate.net/api/iphone/).
 *
 * El CSV tiene: línea 1 "*vpn_servers", línea 2 "#HostName,IP,Score,Ping,
 * Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,...,OpenVPN_ConfigData_Base64"
 * y luego una fila por servidor. Se cachea en filesDir/vpngate.csv (12 h).
 *
 * Todo el trabajo de red es SÍNCRONO: llamar desde un hilo de fondo.
 */
public final class VpnGateClient {
    private VpnGateClient() {}

    public static final String API_URL = "http://www.vpngate.net/api/iphone/";
    private static final String CACHE = "vpngate.csv";
    /** Frescura de la caché antes de reintentar la red. */
    public static final long CACHE_TTL_MS = 12L * 3600 * 1000;

    /** Grupo de servidores por país para la UI. */
    public static class CountryGroup {
        public String iso = "";
        public String nameEs = "";
        public final List<VpnGateServer> servers = new ArrayList<>();
    }

    /**
     * Carga la lista de servidores. Si forceRefresh=false y hay caché fresca,
     * no toca la red.
     */
    public static List<VpnGateServer> load(Context ctx, boolean forceRefresh) {
        File cache = new File(ctx.getFilesDir(), CACHE);
        String csv = null;
        if (!forceRefresh && cache.exists()
                && System.currentTimeMillis() - cache.lastModified() < CACHE_TTL_MS) {
            csv = readFile(cache);
        }
        if (csv == null) {
            csv = download();
            if (csv != null) writeFile(cache, csv);
            else csv = readFile(cache); // red caída: usar caché vieja si existe
        }
        if (csv == null) return new ArrayList<>();
        return parse(csv);
    }

    /** Descarga el CSV. null si la red falla. */
    public static String download() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(API_URL).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "Hyperion/2.2 (Android)");
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            String csv = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            return csv.contains("*vpn_servers") ? csv : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Parsea el CSV a servidores (ignora filas sin config OpenVPN). */
    public static List<VpnGateServer> parse(String csv) {
        List<VpnGateServer> out = new ArrayList<>();
        String[] lines = csv.split("\n");
        if (lines.length < 3) return out;
        // lines[0] = *vpn_servers, lines[1] = #columnas
        for (int i = 2; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            List<String> f = splitCsv(line);
            if (f.size() < 15) continue;
            VpnGateServer s = new VpnGateServer();
            s.hostName = f.get(0);
            s.ip = f.get(1);
            s.score = num(f.get(2));
            s.pingMs = (int) num(f.get(3));
            s.speedBps = num(f.get(4));
            s.countryLong = f.get(5);
            s.countryShort = f.get(6);
            s.numSessions = (int) num(f.get(7));
            s.uptime = num(f.get(8));
            s.ovpnBase64 = f.get(14).trim();
            if (s.ovpnBase64.isEmpty() || s.ip.isEmpty()) continue;
            out.add(s);
        }
        // Mejor primero: más sesiones = servidor probado y con capacidad.
        Collections.sort(out, new Comparator<VpnGateServer>() {
            @Override public int compare(VpnGateServer a, VpnGateServer b) {
                return Integer.compare(b.numSessions, a.numSessions);
            }
        });
        return out;
    }

    /** Agrupa por país, ordenados por cantidad de servidores (desc). */
    public static List<CountryGroup> groupByCountry(List<VpnGateServer> servers) {
        Map<String, CountryGroup> map = new HashMap<>();
        for (VpnGateServer s : servers) {
            CountryGroup g = map.get(s.countryShort);
            if (g == null) {
                g = new CountryGroup();
                g.iso = s.countryShort;
                g.nameEs = countryNameEs(s.countryShort, s.countryLong);
                map.put(s.countryShort, g);
            }
            g.servers.add(s);
        }
        List<CountryGroup> groups = new ArrayList<>(map.values());
        Collections.sort(groups, new Comparator<CountryGroup>() {
            @Override public int compare(CountryGroup a, CountryGroup b) {
                return Integer.compare(b.servers.size(), a.servers.size());
            }
        });
        return groups;
    }

    // ---- CSV mínimo (campos entre comillas con comas internas) ----

    static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQ) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"'); i++;
                    } else inQ = false;
                } else cur.append(ch);
            } else {
                if (ch == '"') inQ = true;
                else if (ch == ',') { out.add(cur.toString()); cur.setLength(0); }
                else cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static long num(String s) {
        try { return Long.parseLong(s.trim()); }
        catch (Exception e) { return 0; }
    }

    private static String readFile(File f) {
        try {
            FileInputStream fis = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
            fis.close();
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeFile(File f, String s) {
        try {
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(s.getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Exception ignored) {}
    }

    // ---- nombres de países en español ----

    private static final Map<String, String> ES = new HashMap<>();
    static {
        ES.put("JP", "Japón"); ES.put("KR", "Corea del Sur"); ES.put("US", "Estados Unidos");
        ES.put("TH", "Tailandia"); ES.put("VN", "Vietnam"); ES.put("RU", "Rusia");
        ES.put("IN", "India"); ES.put("DE", "Alemania"); ES.put("NL", "Países Bajos");
        ES.put("GB", "Reino Unido"); ES.put("FR", "Francia"); ES.put("CA", "Canadá");
        ES.put("SG", "Singapur"); ES.put("BR", "Brasil"); ES.put("UA", "Ucrania");
        ES.put("PL", "Polonia"); ES.put("TW", "Taiwán"); ES.put("HK", "Hong Kong");
        ES.put("AU", "Australia"); ES.put("MX", "México"); ES.put("ES", "España");
        ES.put("IT", "Italia"); ES.put("SE", "Suecia"); ES.put("CH", "Suiza");
        ES.put("RO", "Rumania"); ES.put("BG", "Bulgaria"); ES.put("CZ", "Chequia");
        ES.put("AT", "Austria"); ES.put("BE", "Bélgica"); ES.put("IE", "Irlanda");
        ES.put("PT", "Portugal"); ES.put("GR", "Grecia"); ES.put("TR", "Turquía");
        ES.put("IL", "Israel"); ES.put("AE", "Emiratos Árabes"); ES.put("SA", "Arabia Saudita");
        ES.put("ZA", "Sudáfrica"); ES.put("EG", "Egipto"); ES.put("NG", "Nigeria");
        ES.put("AR", "Argentina"); ES.put("CL", "Chile"); ES.put("CO", "Colombia");
        ES.put("PE", "Perú"); ES.put("VE", "Venezuela"); ES.put("UY", "Uruguay");
        ES.put("EC", "Ecuador"); ES.put("CR", "Costa Rica"); ES.put("PA", "Panamá");
        ES.put("DO", "Rep. Dominicana"); ES.put("CU", "Cuba"); ES.put("PR", "Puerto Rico");
        ES.put("NZ", "Nueva Zelanda"); ES.put("PH", "Filipinas"); ES.put("MY", "Malasia");
        ES.put("ID", "Indonesia"); ES.put("CN", "China"); ES.put("MN", "Mongolia");
        ES.put("KZ", "Kazajistán"); ES.put("PK", "Pakistán"); ES.put("BD", "Bangladés");
        ES.put("LK", "Sri Lanka"); ES.put("NP", "Nepal"); ES.put("MM", "Birmania");
        ES.put("KH", "Camboya"); ES.put("LA", "Laos");
    }

    public static String countryNameEs(String iso, String fallback) {
        String n = ES.get(iso);
        if (n != null) return n;
        return (fallback == null || fallback.isEmpty()) ? iso : fallback;
    }
}
