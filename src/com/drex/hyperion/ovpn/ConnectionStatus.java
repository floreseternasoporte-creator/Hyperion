/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see
 * res/raw/gpl_ics_openvpn.txt (copia de doc/LICENSE.txt de ics-openvpn).
 *
 * Vendorizado de ics-openvpn (github.com/schwabe/ics-openvpn, v0.7.65) y
 * adaptado al paquete com.drex.hyperion.ovpn. Cambios respecto al original:
 * se eliminó la implementación Parcelable/aidl (no usamos binder IPC; el
 * estado fluye por listeners en el mismo proceso vía VpnStatus).
 */
package com.drex.hyperion.ovpn;

/** Niveles de estado de la conexión OpenVPN (constantes, sin Parcelable). */
public class ConnectionStatus {
    public static final int LEVEL_CONNECTED = 0;
    public static final int LEVEL_CONNECTING_NO_SERVER_REPLY_YET = 1;
    public static final int LEVEL_CONNECTING_SERVER_REPLIED = 2;
    public static final int LEVEL_NOTCONNECTED = 3;
    public static final int LEVEL_AUTH_FAILED = 4;
    public static final int LEVEL_NONETWORK = 5;
    public static final int LEVEL_VPNPAUSED = 6;
    public static final int LEVEL_WAITING_FOR_USER_INPUT = 7;
    public static final int UNKNOWN_LEVEL = 8;

    public static String name(int level) {
        switch (level) {
            case LEVEL_CONNECTED: return "CONNECTED";
            case LEVEL_CONNECTING_NO_SERVER_REPLY_YET: return "CONNECTING";
            case LEVEL_CONNECTING_SERVER_REPLIED: return "AUTH";
            case LEVEL_NOTCONNECTED: return "NOTCONNECTED";
            case LEVEL_AUTH_FAILED: return "AUTH_FAILED";
            case LEVEL_NONETWORK: return "NONETWORK";
            case LEVEL_VPNPAUSED: return "PAUSED";
            case LEVEL_WAITING_FOR_USER_INPUT: return "NEED_INPUT";
            default: return "UNKNOWN";
        }
    }
}
