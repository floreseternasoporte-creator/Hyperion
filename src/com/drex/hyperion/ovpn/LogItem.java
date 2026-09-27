/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see
 * res/raw/gpl_ics_openvpn.txt (copia de doc/LICENSE.txt de ics-openvpn).
 *
 * Vendorizado de ics-openvpn (github.com/schwabe/ics-openvpn, v0.7.65) y
 * adaptado al paquete com.drex.hyperion.ovpn. Cambios respecto al original:
 * se eliminó Parcelable/aidl y los constructores con recursos de cadena
 * (R.string); solo quedan constructores con texto literal.
 */
package com.drex.hyperion.ovpn;

/** Una línea del registro de OpenVPN. */
public class LogItem {
    private final VpnStatus.LogLevel mLogLevel;
    private final String mMessage;
    private final long mLogTime = System.currentTimeMillis();
    private final int mOvpnLevel;

    public LogItem(VpnStatus.LogLevel logLevel, String message) {
        this(logLevel, 0, message);
    }

    public LogItem(VpnStatus.LogLevel logLevel, int ovpnLevel, String message) {
        mLogLevel = logLevel;
        mMessage = message == null ? "" : message;
        mOvpnLevel = ovpnLevel;
    }

    public VpnStatus.LogLevel getLogLevel() { return mLogLevel; }
    public String getString() { return mMessage; }
    public long getLogtime() { return mLogTime; }
    public int getOvpnLevel() { return mOvpnLevel; }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof LogItem)) return false;
        LogItem other = (LogItem) o;
        return mMessage.equals(other.mMessage) && mLogLevel == other.mLogLevel;
    }
}
