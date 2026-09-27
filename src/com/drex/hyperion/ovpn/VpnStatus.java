/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see
 * res/raw/gpl_ics_openvpn.txt (copia de doc/LICENSE.txt de ics-openvpn).
 *
 * Vendorizado de ics-openvpn (github.com/schwabe/ics-openvpn, v0.7.65) y
 * adaptado al paquete com.drex.hyperion.ovpn. Cambios respecto al original:
 * - Sin aidl/binder (IStatusCallbacks): los listeners son en el mismo proceso.
 * - Sin R.string: los estados se reportan con su nombre ("CONNECTED", ...) y
 *   la UI de Hyperion los traduce a texto visible.
 * - Sin LogFileHandler/TrafficHistory: el conteo de bytes usa diferencias
 *   simples en memoria; el registro queda en un buffer circular.
 * - Sin ProfileNotifyListener / setConnectedVPNProfile (no hay base de perfiles).
 */
package com.drex.hyperion.ovpn;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.Vector;

/** Registro central y distribución de estados del motor OpenVPN. */
public class VpnStatus {
    static final int MAXLOGENTRIES = 1000;

    private static final LinkedList<LogItem> logbuffer = new LinkedList<>();
    private static final Vector<LogListener> logListener = new Vector<>();
    private static final Vector<StateListener> stateListener = new Vector<>();
    private static final Vector<ByteCountListener> byteCountListener = new Vector<>();

    private static String mLaststatemsg = "";
    private static String mLaststate = "NOPROCESS";
    private static int mLastLevel = ConnectionStatus.LEVEL_NOTCONNECTED;

    private static long mLastByteIn = 0;
    private static long mLastByteOut = 0;

    static {
        logInfo("Motor OpenVPN (ics-openvpn, Arne Schwabe, GPL v2) inicializado");
    }

    public enum LogLevel {
        INFO(2), ERROR(-2), WARNING(1), VERBOSE(3), DEBUG(4);
        private final int mValue;
        LogLevel(int value) { mValue = value; }
        public int getInt() { return mValue; }
    }

    public interface LogListener {
        void newLog(LogItem logItem);
    }

    /** Oyente de cambios de estado en el mismo proceso (sin aidl). */
    public interface StateListener {
        void updateState(String state, String logmessage, int level);
    }

    public interface ByteCountListener {
        void updateByteCount(long in, long out, long diffIn, long diffOut);
    }

    // ---- registro ----

    public static void logException(String context, Throwable e) {
        logException(LogLevel.ERROR, context, e);
    }

    public static void logException(Throwable e) {
        logException(LogLevel.ERROR, null, e);
    }

    public static void logException(LogLevel ll, String context, Throwable e) {
        StringWriter sw = new StringWriter();
        if (e != null) e.printStackTrace(new PrintWriter(sw));
        String msg = (context != null ? context + ": " : "")
                + (e != null && e.getMessage() != null ? e.getMessage() : "")
                + "\n" + sw;
        newLogItem(new LogItem(ll, msg));
    }

    public static void logInfo(String message)  { newLogItem(new LogItem(LogLevel.INFO, message)); }
    public static void logDebug(String message) { newLogItem(new LogItem(LogLevel.DEBUG, message)); }
    public static void logWarning(String message){ newLogItem(new LogItem(LogLevel.WARNING, message)); }
    public static void logError(String message) { newLogItem(new LogItem(LogLevel.ERROR, message)); }

    public static void logMessageOpenVPN(LogLevel level, int ovpnlevel, String message) {
        newLogItem(new LogItem(level, ovpnlevel, message));
    }

    /** Pistas legibles cuando OpenSSL rechaza un certificado/config. */
    public static void addExtraHints(String msg) {
        if (msg == null) return;
        if ((msg.endsWith("md too weak") && msg.startsWith("OpenSSL: error")) || msg.contains("error:140AB18E")
                || msg.contains("SSL_CA_MD_TOO_WEAK") || msg.contains("ca md too weak"))
            logError("OpenSSL reportó un certificado con hash débil; el servidor no es compatible.");
        if (msg.contains("digital envelope routines::unsupported"))
            logError("El método de cifrado de este servidor es obsoleto para el OpenSSL del teléfono.");
    }

    synchronized static void newLogItem(LogItem logItem) {
        if (!logbuffer.isEmpty() && logbuffer.getLast().getLogtime() <= logItem.getLogtime()) {
            logbuffer.addLast(logItem);
        } else {
            ListIterator<LogItem> itr = logbuffer.listIterator();
            long t = logItem.getLogtime();
            while (itr.hasNext()) {
                if (itr.next().getLogtime() > t) { itr.previous(); itr.add(logItem); break; }
            }
            if (!itr.hasNext()) itr.add(logItem);
        }
        while (logbuffer.size() > MAXLOGENTRIES) logbuffer.removeFirst();
        for (LogListener ll : logListener) {
            try { ll.newLog(logItem); } catch (Exception ignored) {}
        }
    }

    synchronized public static LogItem[] getlogbuffer() {
        return logbuffer.toArray(new LogItem[logbuffer.size()]);
    }

    public synchronized static void addLogListener(LogListener ll) {
        if (!logListener.contains(ll)) logListener.add(ll);
    }
    public synchronized static void removeLogListener(LogListener ll) { logListener.remove(ll); }

    // ---- estados ----

    public static boolean isVPNActive() {
        return mLastLevel != ConnectionStatus.LEVEL_AUTH_FAILED
                && mLastLevel != ConnectionStatus.LEVEL_NOTCONNECTED;
    }

    public static String getLastState() { return mLaststate; }
    public static int getLastLevel() { return mLastLevel; }
    public static String getLastStateMessage() { return mLaststatemsg; }

    private static int getLevel(String state) {
        String[] noreply = {"CONNECTING", "WAIT", "RECONNECTING", "RESOLVE", "TCP_CONNECT"};
        String[] replied = {"AUTH", "GET_CONFIG", "ASSIGN_IP", "ADD_ROUTES", "AUTH_PENDING"};
        for (String x : noreply) if (state.equals(x)) return ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET;
        for (String x : replied) if (state.equals(x)) return ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED;
        if (state.equals("CONNECTED")) return ConnectionStatus.LEVEL_CONNECTED;
        if (state.equals("DISCONNECTED") || state.equals("EXITING")) return ConnectionStatus.LEVEL_NOTCONNECTED;
        return ConnectionStatus.UNKNOWN_LEVEL;
    }

    static void updateStateString(String state, String msg) {
        if (mLastLevel == ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT && state.equals("GET_CONFIG"))
            return;
        updateStateString(state, msg, getLevel(state));
    }

    public synchronized static void updateStateString(String state, String msg, int level) {
        if (mLastLevel == ConnectionStatus.LEVEL_CONNECTED && (state.equals("WAIT") || state.equals("AUTH"))) {
            newLogItem(new LogItem(LogLevel.DEBUG,
                    "Ignorando estado OpenVPN en CONNECTED (" + state + "): " + msg));
            return;
        }
        mLaststate = state;
        mLaststatemsg = msg == null ? "" : msg;
        mLastLevel = level;
        for (StateListener sl : stateListener) {
            try { sl.updateState(state, mLaststatemsg, level); } catch (Exception ignored) {}
        }
    }

    public static void updateStatePause(OpenVPNManagement.pauseReason reason) {
        switch (reason) {
            case noNetwork:
                updateStateString("NONETWORK", "", ConnectionStatus.LEVEL_NONETWORK);
                break;
            case screenOff:
            case userPause:
                updateStateString("USERPAUSE", "", ConnectionStatus.LEVEL_VPNPAUSED);
                break;
        }
    }

    public synchronized static void addStateListener(StateListener sl) {
        if (!stateListener.contains(sl)) {
            stateListener.add(sl);
            try { sl.updateState(mLaststate, mLaststatemsg, mLastLevel); } catch (Exception ignored) {}
        }
    }
    public synchronized static void removeStateListener(StateListener sl) { stateListener.remove(sl); }

    // ---- bytes ----

    public synchronized static void addByteCountListener(ByteCountListener bcl) {
        if (!byteCountListener.contains(bcl)) {
            bcl.updateByteCount(mLastByteIn, mLastByteOut, 0, 0);
            byteCountListener.add(bcl);
        }
    }
    public synchronized static void removeByteCountListener(ByteCountListener bcl) {
        byteCountListener.remove(bcl);
    }

    public static synchronized void updateByteCount(long in, long out) {
        long diffIn = Math.max(0, in - mLastByteIn);
        long diffOut = Math.max(0, out - mLastByteOut);
        mLastByteIn = in;
        mLastByteOut = out;
        for (ByteCountListener bcl : byteCountListener) {
            try { bcl.updateByteCount(in, out, diffIn, diffOut); } catch (Exception ignored) {}
        }
    }
}
