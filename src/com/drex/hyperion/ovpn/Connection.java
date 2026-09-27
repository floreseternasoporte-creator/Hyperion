/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see
 * res/raw/gpl_ics_openvpn.txt (copia de doc/LICENSE.txt de ics-openvpn).
 *
 * Vendorizado de ics-openvpn (github.com/schwabe/ics-openvpn, v0.7.65) y
 * adaptado al paquete com.drex.hyperion.ovpn. Versión mínima: solo el tipo
 * de proxy y los campos que lee el hilo de management (Hyperion no configura
 * proxies; VPNGate se conecta directo).
 */
package com.drex.hyperion.ovpn;

import java.io.Serializable;

public class Connection implements Serializable, Cloneable {
    private static final long serialVersionUID = 92031902903829089L;

    public boolean mEnabled = true;
    public ProxyType mProxyType = ProxyType.NONE;
    public String mProxyName = "";
    public String mProxyPort = "";
    public boolean mUseProxyAuth;
    public String mProxyAuthUser = null;
    public String mProxyAuthPassword = null;

    public enum ProxyType {
        NONE,
        HTTP,
        SOCKS5,
        ORBOT
    }

    @Override
    public Connection clone() {
        try {
            return (Connection) super.clone();
        } catch (CloneNotSupportedException e) {
            return new Connection();
        }
    }
}
