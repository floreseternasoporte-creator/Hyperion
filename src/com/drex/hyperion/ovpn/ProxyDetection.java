/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see
 * res/raw/gpl_ics_openvpn.txt (copia de doc/LICENSE.txt de ics-openvpn).
 *
 * Vendorizado de ics-openvpn (github.com/schwabe/ics-openvpn, v0.7.65) y
 * adaptado al paquete com.drex.hyperion.ovpn (VpnProfile -> OvpnProfile).
 */
package com.drex.hyperion.ovpn;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URL;
import java.util.List;

public class ProxyDetection {
    static SocketAddress detectProxy(OvpnProfile vp) {
        try {
            URL url = new URL(String.format("https://%s:%s", vp.mServerName, vp.mServerPort));
            System.setProperty("java.net.useSystemProxies", "true");
            List<Proxy> proxylist = ProxySelector.getDefault().select(url.toURI());
            if (proxylist != null) {
                for (Proxy proxy : proxylist) {
                    SocketAddress addr = proxy.address();
                    if (addr != null) return addr;
                }
            }
        } catch (Exception e) {
            VpnStatus.logError("Error detectando proxy: " + e.getLocalizedMessage());
        }
        return null;
    }
}
