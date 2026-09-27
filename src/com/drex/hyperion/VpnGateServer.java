package com.drex.hyperion;

/** Un servidor VPN público de VPNGate (una fila del CSV de /api/iphone/). */
public class VpnGateServer {
    public String hostName = "";
    public String ip = "";
    public long score;
    public int pingMs = -1;
    /** Velocidad anunciada en bits por segundo. */
    public long speedBps;
    public String countryLong = "";
    public String countryShort = "";
    public int numSessions;
    public long uptime;
    /** Configuración .ovpn completa en Base64 (columna OpenVPN_ConfigData_Base64). */
    public String ovpnBase64 = "";

    /** Etiqueta legible para la UI/notificación: "Japón · 219.100.37.209". */
    public String displayName() {
        String cc = VpnGateClient.countryNameEs(countryShort, countryLong);
        return cc + " · " + (ip.isEmpty() ? hostName : ip);
    }

    public String speedText() {
        if (speedBps <= 0) return "—";
        double mbps = speedBps / 1e6;
        if (mbps >= 1000) return String.format(java.util.Locale.US, "%.1f Gbps", mbps / 1000);
        if (mbps >= 1) return String.format(java.util.Locale.US, "%.0f Mbps", mbps);
        return String.format(java.util.Locale.US, "%.0f Kbps", speedBps / 1e3);
    }

    public String pingText() {
        return pingMs < 0 ? "—" : pingMs + " ms";
    }

    /** Clave para la lista de "no disponibles" de la sesión. */
    public String deadKey() { return hostName + "|" + ip; }
}
