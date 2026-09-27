/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see
 * res/raw/gpl_ics_openvpn.txt (copia de doc/LICENSE.txt de ics-openvpn).
 *
 * Vendorizado de ics-openvpn (github.com/schwabe/ics-openvpn, v0.7.65) y
 * adaptado al paquete com.drex.hyperion.ovpn. Cambios respecto al original:
 * - VpnProfile -> OvpnProfile, OpenVPNService -> OvpnVpnService.
 * - Sin anotaciones androidx/jetbrains; sin R.string (textos literales).
 * - Sin integración Orbot ni mensajes ACC (ningún servidor VPNGate los usa).
 * - El switch moderno (case "I" ->) se reescribió clásico (javac -source 8).
 * La lógica del protocolo de management (tun-fd por SCM_RIGHTS, auth,
 * hold/release, bytecount, estados) es idéntica a la original.
 */
package com.drex.hyperion.ovpn;

import android.content.Context;
import android.content.Intent;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Vector;

public class OpenVpnManagementThread implements Runnable, OpenVPNManagement {

    private static final String TAG = "openvpn";
    private static final Vector<OpenVpnManagementThread> active = new Vector<>();
    private final Handler mResumeHandler;
    private LocalSocket mSocket;
    private OvpnProfile mProfile;
    private OvpnVpnService mOpenVPNService;
    private LinkedList<FileDescriptor> mFDList = new LinkedList<>();
    private LocalServerSocket mServerSocket;
    private boolean mWaitingForRelease = false;
    private long mLastHoldRelease = 0;
    private LocalSocket mServerSocketLocal;
    private int maxAccMessageSize = 1200;

    private pauseReason lastPauseReason = pauseReason.noNetwork;
    private PausedStateCallback mPauseCallback;
    private boolean mShuttingDown;
    private final Runnable mResumeHoldRunnable = new Runnable() {
        @Override public void run() {
            if (shouldBeRunning()) {
                releaseHoldCmd();
            }
        }
    };
    private transient Connection mCurrentProxyConnection;

    public OpenVpnManagementThread(OvpnProfile profile, OvpnVpnService openVpnService) {
        mProfile = profile;
        mOpenVPNService = openVpnService;
        mResumeHandler = new Handler(openVpnService.getMainLooper());
    }

    private static boolean stopOpenVPN() {
        synchronized (active) {
            boolean sendCMD = false;
            for (OpenVpnManagementThread mt : active) {
                sendCMD = mt.managmentCommand("signal SIGINT\n");
                try {
                    if (mt.mSocket != null)
                        mt.mSocket.close();
                } catch (IOException e) {
                    // Ignore close error on already closed socket
                }
            }
            return sendCMD;
        }
    }

    public boolean openManagementInterface(Context c) {
        // Could take a while to open connection
        int tries = 8;

        String socketName = (c.getCacheDir().getAbsolutePath() + "/" + "mgmtsocket");
        // The mServerSocketLocal is transferred to the LocalServerSocket, ignore warning

        mServerSocketLocal = new LocalSocket();

        while (tries > 0 && !mServerSocketLocal.isBound()) {
            try {
                mServerSocketLocal.bind(new LocalSocketAddress(socketName,
                        LocalSocketAddress.Namespace.FILESYSTEM));
            } catch (IOException e) {
                // wait 300 ms before retrying
                try {
                    //noinspection BusyWait
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                }
            }
            tries--;
        }

        try {
            mServerSocket = new LocalServerSocket(mServerSocketLocal.getFileDescriptor());
            return true;
        } catch (IOException e) {
            VpnStatus.logException(e);
        }
        return false;
    }

    /**
     * @param cmd command to write to management socket
     * @return true if command have been sent
     */
    public boolean managmentCommand(String cmd) {
        try {
            if (mSocket != null && mSocket.getOutputStream() != null) {
                mSocket.getOutputStream().write(cmd.getBytes());
                mSocket.getOutputStream().flush();
                return true;
            }
        } catch (IOException e) {
            // Ignore socket stack traces
        }
        return false;
    }

    @Override
    public void run() {
        byte[] buffer = new byte[2048];

        String pendingInput = "";
        synchronized (active) {
            active.add(this);
        }

        try {
            // Wait for a client to connect
            mSocket = mServerSocket.accept();
            InputStream instream = mSocket.getInputStream();

            // Close the management socket after client connected
            try {
                mServerSocket.close();
            } catch (IOException e) {
                VpnStatus.logException(e);
            }

            managmentCommand("version 3\n");

            while (true) {
                int numbytesread = instream.read(buffer);
                if (numbytesread == -1)
                    return;

                FileDescriptor[] fds = null;
                try {
                    fds = mSocket.getAncillaryFileDescriptors();
                } catch (IOException e) {
                    VpnStatus.logException("Error reading fds from socket", e);
                }
                if (fds != null) {
                    Collections.addAll(mFDList, fds);
                }

                String input = new String(buffer, 0, numbytesread, "UTF-8");

                pendingInput += input;

                pendingInput = processInput(pendingInput);
            }
        } catch (IOException e) {
            if (e.getMessage() == null
                    || (!e.getMessage().equals("socket closed")
                        && !e.getMessage().equals("Connection reset by peer")))
                VpnStatus.logException(e);
        }
        synchronized (active) {
            active.remove(this);
        }
    }

    //! Hack O Rama 2000!
    private void protectFileDescriptor(FileDescriptor fd) {
        try {
            Method getInt = FileDescriptor.class.getDeclaredMethod("getInt$");
            int fdint = (Integer) getInt.invoke(fd);

            // You can even get more evil by parsing toString() and extract the int from that :)

            boolean result = mOpenVPNService.protect(fdint);
            if (!result)
                VpnStatus.logWarning("Could not protect VPN socket");

            fdClose(fd);
        } catch (NoSuchMethodException | IllegalArgumentException | InvocationTargetException
                | IllegalAccessException | NullPointerException e) {
            VpnStatus.logException("Failed to retrieve fd from socket (" + fd + ")", e);
        }
    }

    private void fdClose(FileDescriptor fd) {
        try {
            Os.close(fd);
        } catch (Exception e) {
            VpnStatus.logException("Failed to close fd (" + fd + ")", e);
        }
    }

    private String processInput(String pendingInput) {
        while (pendingInput.contains("\n")) {
            String[] tokens = pendingInput.split("\\r?\\n", 2);
            processCommand(tokens[0]);
            if (tokens.length == 1)
                // No second part, newline was at the end
                pendingInput = "";
            else
                pendingInput = tokens[1];
        }
        return pendingInput;
    }

    private void processCommand(String command) {
        if (command.startsWith(">") && command.contains(":")) {
            String[] parts = command.split(":", 2);
            String cmd = parts[0].substring(1);
            String argument = parts[1];

            if (cmd.equals("INFO")) {
                /* Ignore greeting from management */
                return;
            } else if (cmd.equals("PASSWORD")) {
                processPWCommand(argument);
            } else if (cmd.equals("HOLD")) {
                handleHold(argument);
            } else if (cmd.equals("NEED-OK")) {
                processNeedCommand(argument);
            } else if (cmd.equals("BYTECOUNT")) {
                processByteCount(argument);
            } else if (cmd.equals("STATE")) {
                if (!mShuttingDown)
                    processState(argument);
            } else if (cmd.equals("PROXY")) {
                processProxyCMD(argument);
            } else if (cmd.equals("LOG")) {
                processLogMessage(argument);
            } else if (cmd.equals("PK_SIGN")) {
                processSignCommand(argument);
            } else if (cmd.equals("INFOMSG")) {
                processInfoMessage(argument);
            } else if (cmd.equals("ACC")) {
                // Mensajes ACC (control personalizado de app): ningún servidor
                // VPNGate los envía; se ignoran.
                VpnStatus.logDebug("ACC message ignored: " + argument);
            } else {
                VpnStatus.logWarning("MGMT: Got unrecognized command (" + cmd + "):" + command);
                Log.i(TAG, "Got unrecognized command" + command);
            }
        } else if (command.startsWith("SUCCESS:")) {
            /* Ignore this kind of message too */
            return;
        } else if (command.startsWith("PROTECTFD: ")) {
            FileDescriptor fdtoprotect = mFDList.pollFirst();
            if (fdtoprotect != null)
                protectFileDescriptor(fdtoprotect);
        } else {
            Log.i(TAG, "Got unrecognized line from managment" + command);
            VpnStatus.logWarning("MGMT: Got unrecognized line from management:" + command);
        }
    }

    private void processInfoMessage(String info) {
        if (info.startsWith("OPEN_URL:") || info.startsWith("CR_TEXT:")
                || info.startsWith("WEB_AUTH:")) {
            mOpenVPNService.onSsoInfo(info);
        } else {
            VpnStatus.logDebug("Info message from server:" + info);
        }
    }

    private void processLogMessage(String argument) {
        String[] args = argument.split(",", 4);
        // 0 unix time stamp
        // 1 log level I,F,N,W,D
        // 2 flags, 3 message
        Log.d("OpenVPN", argument);

        VpnStatus.LogLevel level;
        if (args.length > 1) {
            String l = args[1];
            if (l.equals("W")) level = VpnStatus.LogLevel.WARNING;
            else if (l.equals("D")) level = VpnStatus.LogLevel.VERBOSE;
            else if (l.equals("F")) level = VpnStatus.LogLevel.ERROR;
            else level = VpnStatus.LogLevel.INFO;
        } else {
            level = VpnStatus.LogLevel.INFO;
        }

        int ovpnlevel = 0;
        String msg = argument;
        if (args.length > 3) {
            try { ovpnlevel = Integer.parseInt(args[2]) & 0x0F; } catch (NumberFormatException ignored) {}
            msg = args[3];
        }

        if (msg.startsWith("MANAGEMENT: CMD"))
            ovpnlevel = Math.max(4, ovpnlevel);

        VpnStatus.logMessageOpenVPN(level, ovpnlevel, msg);
    }

    boolean shouldBeRunning() {
        if (mPauseCallback == null)
            return false;
        else
            return mPauseCallback.shouldBeRunning();
    }

    private void handleHold(String argument) {
        mWaitingForRelease = true;
        int waittime = 0;
        try { waittime = Integer.parseInt(argument.split(":")[1]); } catch (Exception ignored) {}
        if (shouldBeRunning()) {
            if (waittime > 1)
                VpnStatus.updateStateString("CONNECTRETRY", String.valueOf(waittime),
                        ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET);
            mResumeHandler.postDelayed(mResumeHoldRunnable, waittime * 1000L);
            if (waittime > 5)
                VpnStatus.logInfo("Esperando " + waittime + "s antes de reintentar la conexión");
            else
                VpnStatus.logDebug("Esperando " + waittime + "s antes de reintentar la conexión");
        } else {
            VpnStatus.updateStatePause(lastPauseReason);
        }
    }

    private void releaseHoldCmd() {
        mResumeHandler.removeCallbacks(mResumeHoldRunnable);
        if ((System.currentTimeMillis() - mLastHoldRelease) < 5000) {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {
            }
        }
        mWaitingForRelease = false;
        mLastHoldRelease = System.currentTimeMillis();
        managmentCommand("hold release\n");
        managmentCommand("bytecount " + mBytecountInterval + "\n");
        managmentCommand("state on\n");
    }

    public void releaseHold() {
        if (mWaitingForRelease)
            releaseHoldCmd();
    }

    private void processProxyCMD(String argument) {
        String[] args = argument.split(",", 3);

        Connection.ProxyType proxyType = Connection.ProxyType.NONE;

        int connectionEntryNumber = 0;
        try { connectionEntryNumber = Integer.parseInt(args[0]) - 1; } catch (Exception ignored) {}
        String proxyport = null;
        String proxyname = null;
        boolean proxyUseAuth = false;

        Vector<Connection> activeConnections = new Vector<>();

        if (mProfile != null && mProfile.mConnections != null) {
            for (Connection conn : mProfile.mConnections) {
                if (conn.mEnabled) {
                    activeConnections.add(conn);
                }
            }
        }

        if (activeConnections.size() > connectionEntryNumber) {
            Connection connection = activeConnections.get(connectionEntryNumber);
            proxyType = connection.mProxyType;
            proxyname = connection.mProxyName;
            proxyport = connection.mProxyPort;
            proxyUseAuth = connection.mUseProxyAuth;

            // Use transient variable to remember http user/password
            mCurrentProxyConnection = connection;
        } else if (mProfile != null && !mProfile.mConnections.isEmpty()) {
            VpnStatus.logError(String.format(Locale.ENGLISH,
                    "OpenVPN is asking for a proxy of an unknown connection entry (%d)",
                    connectionEntryNumber));
        }

        // auto detection of proxy
        if (proxyType == Connection.ProxyType.NONE && mProfile != null) {
            SocketAddress proxyaddr = ProxyDetection.detectProxy(mProfile);
            if (proxyaddr instanceof InetSocketAddress) {
                InetSocketAddress isa = (InetSocketAddress) proxyaddr;
                proxyType = Connection.ProxyType.HTTP;
                proxyname = isa.getHostName();
                proxyport = String.valueOf(isa.getPort());
                proxyUseAuth = false;
            }
        }

        if (args.length >= 2 && proxyType == Connection.ProxyType.HTTP) {
            String proto = args[1];
            if (proto.equals("UDP")) {
                proxyname = null;
                VpnStatus.logInfo("Not using an HTTP proxy since the connection uses UDP");
            }
        }

        // Sin Orbot en Hyperion: si el perfil pidiera ORBOT se conecta directo.
        if (proxyType == Connection.ProxyType.ORBOT) {
            VpnStatus.logWarning("Proxy ORBOT no soportado en Hyperion; conectando directo");
            proxyType = Connection.ProxyType.NONE;
        }
        sendProxyCMD(proxyType, proxyname, proxyport, proxyUseAuth);
    }

    private void sendProxyCMD(Connection.ProxyType proxyType, String proxyname, String proxyport,
                              boolean usePwAuth) {
        if (proxyType != Connection.ProxyType.NONE && proxyname != null) {
            VpnStatus.logInfo("Usando proxy " + proxyname);

            String pwstr = usePwAuth ? " auto" : "";

            String proxycmd = String.format(Locale.ENGLISH, "proxy %s %s %s%s\n",
                    proxyType == Connection.ProxyType.HTTP ? "HTTP" : "SOCKS",
                    proxyname, proxyport, pwstr);
            managmentCommand(proxycmd);
        } else {
            managmentCommand("proxy NONE\n");
        }
    }

    private void processState(String argument) {
        String[] args = argument.split(",", 3);
        String currentstate = args[1];

        if (args[2].equals(",,"))
            VpnStatus.updateStateString(currentstate, "");
        else
            VpnStatus.updateStateString(currentstate, args[2]);
    }

    private void processByteCount(String argument) {
        //   >BYTECOUNT:{BYTES_IN},{BYTES_OUT}
        int comma = argument.indexOf(',');
        try {
            long in = Long.parseLong(argument.substring(0, comma));
            long out = Long.parseLong(argument.substring(comma + 1));
            VpnStatus.updateByteCount(in, out);
        } catch (Exception e) {
            VpnStatus.logException("Error parsing BYTECOUNT", e);
        }
    }

    private void processNeedCommand(String argument) {
        int p1 = argument.indexOf('\'');
        int p2 = argument.indexOf('\'', p1 + 1);

        String needed = argument.substring(p1 + 1, p2);
        String extra = argument.split(":", 2)[1];

        String status = "ok";

        if (needed.equals("PROTECTFD")) {
            FileDescriptor fdtoprotect = mFDList.pollFirst();
            protectFileDescriptor(fdtoprotect);
        } else if (needed.equals("DNSSERVER") || needed.equals("DNS6SERVER")) {
            mOpenVPNService.addDNS(extra);
        } else if (needed.equals("DNSDOMAIN")) {
            mOpenVPNService.addSearchDomain(extra);
        } else if (needed.equals("ROUTE")) {
            String[] routeparts = extra.split(" ");
            /*
            buf_printf (&out, "%s %s %s dev %s", network, netmask, gateway, rgi->iface);
            else
            buf_printf (&out, "%s %s %s", network, netmask, gateway);
            */
            if (routeparts.length == 5) {
                mOpenVPNService.addRoute(routeparts[0], routeparts[1], routeparts[2], routeparts[4]);
            } else if (routeparts.length >= 3) {
                mOpenVPNService.addRoute(routeparts[0], routeparts[1], routeparts[2], null);
            } else {
                VpnStatus.logError("Unrecognized ROUTE cmd:" + Arrays.toString(routeparts)
                        + " | " + argument);
            }
        } else if (needed.equals("ROUTE6")) {
            String[] routeparts = extra.split(" ");
            mOpenVPNService.addRoutev6(routeparts[0], routeparts[1]);
        } else if (needed.equals("IFCONFIG")) {
            String[] ifconfigparts = extra.split(" ");
            int mtu = 1500;
            try { mtu = Integer.parseInt(ifconfigparts[2]); } catch (Exception ignored) {}
            mOpenVPNService.setLocalIP(ifconfigparts[0], ifconfigparts[1], mtu, ifconfigparts[3]);
        } else if (needed.equals("IFCONFIG6")) {
            String[] ifconfig6parts = extra.split(" ");
            int mtu = 1500;
            try { mtu = Integer.parseInt(ifconfig6parts[1]); } catch (Exception ignored) {}
            mOpenVPNService.setMtu(mtu);
            mOpenVPNService.setLocalIPv6(ifconfig6parts[0]);
        } else if (needed.equals("PERSIST_TUN_ACTION")) {
            // check if tun cfg stayed the same
            status = mOpenVPNService.getTunReopenStatus();
        } else if (needed.equals("OPENTUN")) {
            if (sendTunFD(needed, extra))
                return;
            else
                status = "cancel";
        } else if (needed.equals("HTTPPROXY")) {
            String[] httpproxy = extra.split(" ");
            if (httpproxy.length == 2) {
                try {
                    mOpenVPNService.addHttpProxy(httpproxy[0], Integer.parseInt(httpproxy[1]));
                } catch (NumberFormatException ignored) {}
            } else {
                VpnStatus.logError("Unrecognized HTTPPROXY cmd: " + Arrays.toString(httpproxy)
                        + " | " + argument);
            }
        } else {
            Log.e(TAG, "Unknown needok command " + argument);
            return;
        }

        String cmd = String.format("needok '%s' %s\n", needed, status);
        managmentCommand(cmd);
    }

    private boolean sendCommandWithFd(String cmd, ParcelFileDescriptor pfd) {
        if (pfd == null)
            return false;

        Method setInt;
        int fdint = pfd.getFd();
        try {
            setInt = FileDescriptor.class.getDeclaredMethod("setInt$", int.class);
            FileDescriptor fdtosend = new FileDescriptor();

            setInt.invoke(fdtosend, fdint);

            FileDescriptor[] fds = {fdtosend};

            // Trigger a send so we can close the fd on our side of the channel
            // The API documentation fails to mention that it will not reset the file descriptor to
            // be send and will happily send the file descriptor on every write ...
            mSocket.setFileDescriptorsForSend(fds);

            managmentCommand(cmd);

            // Set the FileDescriptor to null to stop this mad behavior
            mSocket.setFileDescriptorsForSend(null);
            pfd.close();
        } catch (InvocationTargetException | NoSuchMethodException | IllegalAccessException |
                 IOException exp) {
            VpnStatus.logException("Could not send fd over socket", exp);
            return false;
        }
        return true;
    }

    private boolean sendTunFD(String needed, String extra) {
        if (!extra.equals("tun")) {
            // We only support tun
            VpnStatus.logError(String.format(
                    "Device type %s requested, but only tun is possible with the Android API, sorry!",
                    extra));
            return false;
        }
        ParcelFileDescriptor pfd = mOpenVPNService.openTun();

        String cmd = String.format("needok '%s' %s\n", needed, "ok");
        return sendCommandWithFd(cmd, pfd);
    }

    private void processPWCommand(String argument) {
        //argument has the form 	Need 'Private Key' password
        // or  ">PASSWORD:Verification Failed: '%s' ['%s']"
        String needed;

        try {
            // Ignore Auth token message, already managed by openvpn itself
            if (argument.startsWith("Auth-Token:")) {
                return;
            }

            int p1 = argument.indexOf('\'');
            int p2 = argument.indexOf('\'', p1 + 1);
            needed = argument.substring(p1 + 1, p2);
            if (argument.startsWith("Verification Failed")) {
                proccessPWFailed(needed, argument.substring(p2 + 1));
                return;
            }
        } catch (StringIndexOutOfBoundsException sioob) {
            VpnStatus.logError("Could not parse management Password command: " + argument);
            return;
        }

        String pw = null;
        String username = null;

        if (needed.equals("Private Key")) {
            pw = mProfile.getPasswordPrivateKey();
        } else if (needed.equals("Auth")) {
            pw = mProfile.getPasswordAuth();
            username = mProfile.mUsername;
        } else if (needed.equals("HTTP Proxy")) {
            if (mCurrentProxyConnection != null) {
                pw = mCurrentProxyConnection.mProxyAuthPassword;
                username = mCurrentProxyConnection.mProxyAuthUser;
            }
        }
        if (pw != null) {
            if (username != null) {
                String usercmd = String.format("username '%s' %s\n",
                        needed, OvpnProfile.openVpnEscape(username));
                managmentCommand(usercmd);
            }
            String cmd = String.format("password '%s' %s\n", needed,
                    OvpnProfile.openVpnEscape(pw));
            managmentCommand(cmd);
        } else {
            mOpenVPNService.requestInputFromUser(needed);
            VpnStatus.logError(String.format(
                    "Openvpn requires Authentication type '%s' but no password/key information available",
                    needed));
        }
    }

    private void proccessPWFailed(String needed, String args) {
        VpnStatus.updateStateString("AUTH_FAILED", needed + args,
                ConnectionStatus.LEVEL_AUTH_FAILED);
    }

    @Override
    public void networkChange(boolean samenetwork) {
        if (mWaitingForRelease)
            releaseHold();
        else if (samenetwork)
            managmentCommand("network-change samenetwork\n");
        else
            managmentCommand("network-change\n");
    }

    @Override
    public void setPauseCallback(PausedStateCallback callback) {
        mPauseCallback = callback;
    }

    @Override
    public void sendCRResponse(String response) {
        managmentCommand("cr-response " + response + "\n");
    }

    public void signalusr1() {
        mResumeHandler.removeCallbacks(mResumeHoldRunnable);
        if (!mWaitingForRelease)
            managmentCommand("signal SIGUSR1\n");
        else
            // If signalusr1 is called update the state string
            // if there is another for stopping
            VpnStatus.updateStatePause(lastPauseReason);
    }

    public void reconnect() {
        signalusr1();
        releaseHold();
    }

    private void processSignCommand(String argument) {
        String[] arguments = argument.split(",");

        SignaturePadding padding = SignaturePadding.NO_PADDING;
        String saltlen = "";
        String hashalg = "";
        boolean needsDigest = false;

        for (int i = 1; i < arguments.length; i++) {
            String arg = arguments[i];
            if (arg.equals("RSA_PKCS1_PADDING"))
                padding = SignaturePadding.RSA_PKCS1_PADDING;
            else if (arg.equals("RSA_PKCS1_PSS_PADDING"))
                padding = SignaturePadding.RSA_PKCS1_PSS_PADDING;
            else if (arg.startsWith("saltlen="))
                saltlen = arg.substring(8);
            else if (arg.startsWith("hashalg="))
                hashalg = arg.substring(8);
            else if (arg.equals("data=message"))
                needsDigest = true;
        }

        String signed_string = mProfile.getSignedData(mOpenVPNService, arguments[0], padding,
                saltlen, hashalg, needsDigest);

        if (signed_string == null) {
            managmentCommand("pk-sig\n");
            managmentCommand("\nEND\n");
            stopOpenVPN();
            return;
        }
        managmentCommand("pk-sig\n");
        managmentCommand(signed_string);
        managmentCommand("\nEND\n");
    }

    @Override
    public void pause(pauseReason reason) {
        lastPauseReason = reason;
        signalusr1();
    }

    @Override
    public void resume() {
        releaseHold();
        /* Reset the reason why we are disconnected */
        lastPauseReason = pauseReason.noNetwork;
    }

    @Override
    public boolean stopVPN(boolean replaceConnection) {
        boolean stopSucceed = stopOpenVPN();
        if (stopSucceed) {
            mShuttingDown = true;
        }
        return stopSucceed;
    }
}
