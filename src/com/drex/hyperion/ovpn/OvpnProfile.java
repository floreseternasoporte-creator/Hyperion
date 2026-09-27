/*
 * Perfil mínimo de conexión OpenVPN para Hyperion.
 *
 * Reemplaza al VpnProfile de ics-openvpn (que exige SpongyCastle, KeyChain y
 * toda la infraestructura de perfiles de su app; nada de eso existe aquí).
 * El .ovpn crudo viene de VPNGate ya completo (certificados <ca>/<cert>/<key>
 * en línea); esta clase solo antepone el bloque de management que el binario
 * necesita para hablar con OpenVpnManagementThread, igual que hace
 * VpnProfile.getConfigFile() en ics-openvpn.
 *
 * La clase conserva los miembros que el hilo de management espera de un
 * perfil (mUsername, getPasswordAuth(), getName(), mConnections, ...).
 */
package com.drex.hyperion.ovpn;

import java.util.UUID;
import java.util.Vector;

public class OvpnProfile {
    /** Usuario/clave estándar de los servidores públicos de VPNGate. */
    public static final String VPNGATE_USER = "vpn";
    public static final String VPNGATE_PASSWORD = "vpn";

    /** Nombre legible ("Japón · public-vpn-192"). */
    public String mName;
    /** Texto crudo del .ovpn decodificado del CSV de VPNGate. */
    public String mOvpnText;
    /** Usuario para auth-user-pass (si el servidor lo pidiera). */
    public String mUsername = VPNGATE_USER;
    /** Conexiones (vacío: sin proxy en Hyperion). */
    public Vector<Connection> mConnections = new Vector<>();
    /** Servidor/puerto (para detección de proxy del sistema). */
    public String mServerName = "";
    public String mServerPort = "";

    private final String mUuid = UUID.randomUUID().toString();

    public OvpnProfile(String name, String ovpnText) {
        mName = name == null ? "" : name;
        mOvpnText = ovpnText == null ? "" : ovpnText;
        parseRemote();
    }

    public String getName() { return mName; }
    public String getUUIDString() { return mUuid; }

    /** Clave para el desafío 'Auth' del management (VPNGate: vpn/vpn). */
    public String getPasswordAuth() { return VPNGATE_PASSWORD; }
    /** Sin clave privada con contraseña en los perfiles VPNGate. */
    public String getPasswordPrivateKey() { return null; }

    /** Firma externa (PK_SIGN): no soportada; devuelve null como en ics-openvpn
     * cuando no hay proveedor (el hilo envía pk-sig vacío y detiene). */
    public String getSignedData(Object ctx, String b64data, OpenVPNManagement.SignaturePadding padding,
                                String saltlen, String hashalg, boolean needsDigest) {
        return null;
    }

    /**
     * Configuración final que se envía por stdin al binario openvpn
     * (equivalente a `openvpn --config stdin`).
     * Antepone el bloque de management igual que ics-openvpn.
     */
    public String buildConfigText(String cacheDirPath) {
        StringBuilder cfg = new StringBuilder();
        cfg.append("# Config generada por Hyperion (motor ics-openvpn)\n");
        cfg.append("management ").append(cacheDirPath).append("/mgmtsocket unix\n");
        cfg.append("management-client\n");
        cfg.append("management-query-passwords\n");
        cfg.append("management-hold\n");
        cfg.append("machine-readable-output\n");
        cfg.append("allow-recursive-routing\n");
        cfg.append("ifconfig-nowarn\n");
        cfg.append("\n");
        cfg.append(mOvpnText);
        if (!mOvpnText.endsWith("\n")) cfg.append("\n");
        return cfg.toString();
    }

    /** Escapa un valor para la línea de comandos/config de openvpn (de VpnProfile). */
    public static String openVpnEscape(String unescaped) {
        if (unescaped == null)
            return null;
        String escapedString = unescaped.replace("\\", "\\\\");
        escapedString = escapedString.replace("\"", "\\\"");
        escapedString = escapedString.replace("\n", "\\n");

        if (escapedString.equals(unescaped) && !escapedString.contains(" ")
                && !escapedString.contains("#") && !escapedString.contains(";")
                && !escapedString.equals("") && !escapedString.contains("'"))
            return unescaped;
        else
            return '"' + escapedString + '"';
    }

    /** Extrae el primer "remote HOST PORT" del .ovpn para la detección de proxy. */
    private void parseRemote() {
        for (String line : mOvpnText.split("\n")) {
            String t = line.trim();
            if (t.startsWith("remote ") && !t.startsWith("remote-random")) {
                String[] p = t.split("\\s+");
                if (p.length >= 3) {
                    mServerName = p[1];
                    mServerPort = p[2];
                    return;
                }
            }
        }
    }
}
