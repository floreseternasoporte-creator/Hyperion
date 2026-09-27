/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see
 * res/raw/gpl_ics_openvpn.txt (copia de doc/LICENSE.txt de ics-openvpn).
 *
 * Vendorizado de ics-openvpn (github.com/schwabe/ics-openvpn, v0.7.65) y
 * adaptado al paquete com.drex.hyperion.ovpn. Cambio respecto al original:
 * se eliminó sendAccMessage (mensajes ACC personalizados, no los envía
 * ningún servidor VPNGate; su clase AccMessage vivía en código Kotlin).
 */
package com.drex.hyperion.ovpn;

public interface OpenVPNManagement {
    interface PausedStateCallback {
        boolean shouldBeRunning();
    }

    enum pauseReason {
        noNetwork,
        userPause,
        screenOff,
    }

    enum SignaturePadding {
        RSA_PKCS1_PSS_PADDING,
        RSA_PKCS1_PADDING,
        NO_PADDING
    }

    int mBytecountInterval = 2;

    void reconnect();

    void pause(pauseReason reason);

    void resume();

    /**
     * @param replaceConnection True if the VPN is connected by a new connection.
     * @return true if there was a process that has been send a stop signal
     */
    boolean stopVPN(boolean replaceConnection);

    /*
     * Rebind the interface
     */
    void networkChange(boolean sameNetwork);

    void setPauseCallback(PausedStateCallback callback);

    /**
     * Send the response to a challenge response
     * @param response  Base64 encoded response
     */
    void sendCRResponse(String response);
}
