/*
 * Servicio VPN remoto de Hyperion: túnel OpenVPN real contra servidores
 * gratuitos de VPNGate usando el motor vendorizado de ics-openvpn
 * (paquete com.drex.hyperion.ovpn, GPL v2, Arne Schwabe).
 *
 * Este servicio es código propio de Hyperion (no vendorizado): implementa
 * los métodos que OpenVpnManagementThread espera de un servicio
 * (openTun, addRoute, addDNS, setLocalIP, ...) con VpnService.Builder,
 * lanza el binario openvpn (libovpnexec.so desde nativeLibraryDir, como
 * hace ics-openvpn) con la config por stdin y reporta el estado por
 * VpnStatus a la UI.
 *
 * NO interfiere con HyperionVpnService (modo local): el conector detiene
 * un modo antes de arrancar el otro; nunca corren dos túneles a la vez.
 */
package com.drex.hyperion.ovpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class OvpnVpnService extends VpnService {
    private static final int NOTIF_ID = 2001;
    private static final String CHANNEL = "hyperion_ovpn";

    public static final String ACTION_CONNECT =
            "com.drex.hyperion.ovpn.ACTION_CONNECT";
    public static final String ACTION_DISCONNECT =
            "com.drex.hyperion.ovpn.ACTION_DISCONNECT";
    public static final String EXTRA_LABEL = "label";
    public static final String EXTRA_COUNTRY_ISO = "country_iso";
    public static final String EXTRA_CONFIG_PATH = "config_path";

    /** Estado visible para la UI (la UI hace poll como con el modo local). */
    public static final AtomicBoolean running = new AtomicBoolean(false);
    public static volatile String currentLabel = "";
    public static volatile String currentCountryIso = "";
    public static volatile long connectedSince = 0;
    /** Último estado del management ("CONNECTED", "AUTH_FAILED", ...). */
    public static volatile String lastState = "NOPROCESS";
    public static volatile int lastLevel = ConnectionStatus.LEVEL_NOTCONNECTED;
    /** Último error legible para mostrar en la UI. */
    public static volatile String lastError = "";
    /** Servidores marcados como no disponibles en esta sesión. */
    public static final List<String> deadServers =
            java.util.Collections.synchronizedList(new ArrayList<String>());

    private OpenVpnManagementThread mMgmt;
    private OpenVPNThread mProcThread;
    private Thread mWorker;
    private volatile boolean everConnected = false;
    private volatile boolean userStopped = false;

    private final VpnStatus.StateListener svcListener = new VpnStatus.StateListener() {
        @Override public void updateState(String state, String logmessage, int level) {
            lastState = state;
            lastLevel = level;
            if ("CONNECTED".equals(state)) everConnected = true;
            if (level == ConnectionStatus.LEVEL_AUTH_FAILED) {
                lastError = "El servidor rechazó la conexión (AUTH_FAILED). Prueba con otro servidor.";
            }
            updateNotification();
        }
    };

    // ---- estado del túnel acumulado por el hilo de management ----

    private static class TunState {
        String v4ip; int v4prefix;
        String v6ip; int v6prefix; boolean hasV6;
        int mtu = 1500;
        final List<String> dns = new ArrayList<>();
        final List<String> searchDomains = new ArrayList<>();
        final List<String[]> routes4 = new ArrayList<>(); // {ip, prefix}
        final List<String[]> routes6 = new ArrayList<>();
    }
    private TunState tun = new TunState();

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Hyperion VPN",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Túnel VPN remoto (OpenVPN / VPNGate)");
        nm.createNotificationChannel(ch);
        VpnStatus.addStateListener(svcListener);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_DISCONNECT.equals(action)) {
            userStopped = true;
            stopVpnInternal();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_CONNECT.equals(action)) {
            final String label = intent.getStringExtra(EXTRA_LABEL);
            final String iso = intent.getStringExtra(EXTRA_COUNTRY_ISO);
            final String cfgPath = intent.getStringExtra(EXTRA_CONFIG_PATH);
            startForeground(NOTIF_ID, buildNotification("Conectando…"));
            userStopped = false;
            lastError = "";
            everConnected = false;
            // Si había una conexión previa, detenerla primero (un solo túnel).
            stopVpnInternal();
            currentLabel = label == null ? "" : label;
            currentCountryIso = iso == null ? "" : iso;
            running.set(true);
            connectedSince = System.currentTimeMillis();
            mWorker = new Thread(new Runnable() {
                @Override public void run() { runVpn(cfgPath); }
            }, "OvpnStarter");
            mWorker.start();
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    private void runVpn(String cfgPath) {
        try {
            String ovpnText = readFile(cfgPath);
            if (ovpnText == null || ovpnText.isEmpty()) {
                fail("No se pudo leer la configuración del servidor.");
                return;
            }
            OvpnProfile profile = new OvpnProfile(currentLabel, ovpnText);

            mMgmt = new OpenVpnManagementThread(profile, this);
            mMgmt.setPauseCallback(new OpenVPNManagement.PausedStateCallback() {
                @Override public boolean shouldBeRunning() { return running.get(); }
            });
            if (!mMgmt.openManagementInterface(this)) {
                fail("No se pudo abrir la interfaz de management.");
                return;
            }
            new Thread(mMgmt, "OpenVPNManagementThread").start();

            String nativeDir = getApplicationInfo().nativeLibraryDir;
            File binary = new File(nativeDir, "libovpnexec.so");
            if (!binary.exists() || !binary.canExecute()) {
                fail("Binario OpenVPN no encontrado en el APK (falta lib/<abi>/libovpnexec.so).");
                return;
            }
            String[] argv = { binary.getAbsolutePath(), "--config", "stdin" };
            String tmpDir = getCacheDir().getAbsolutePath();
            mProcThread = new OpenVPNThread(this, argv, nativeDir, tmpDir);
            new Thread(mProcThread, "OpenVPNProcessThread").start();

            // La config entra por stdin, como hace ics-openvpn.
            OutputStream stdin = mProcThread.getOpenVPNStdin();
            byte[] cfgBytes = profile.buildConfigText(tmpDir).getBytes(StandardCharsets.UTF_8);
            stdin.write(cfgBytes);
            stdin.flush();
            stdin.close();
            VpnStatus.logInfo("Configuración enviada al binario openvpn (" + cfgBytes.length + " bytes)");
        } catch (Exception e) {
            VpnStatus.logException("Error arrancando OpenVPN", e);
            fail("Error arrancando OpenVPN: " + e.getMessage());
        }
    }

    private void fail(String msg) {
        lastError = msg;
        VpnStatus.logError(msg);
        stopVpnInternal();
        stopSelf();
    }

    /** Llamado por OpenVPNThread cuando el proceso openvpn termina. */
    public void onOpenVpnProcessStopped() {
        if (!everConnected && !userStopped && lastError.isEmpty()) {
            String detail = VpnStatus.getLastStateMessage();
            lastError = "El servidor no respondió"
                    + (detail.isEmpty() ? "." : " (" + detail + ").")
                    + " Prueba con otro servidor.";
            if (!currentLabel.isEmpty() && !deadServers.contains(currentLabel))
                deadServers.add(currentLabel);
        }
        running.set(false);
        stopForeground(true);
        stopSelf();
    }

    private void stopVpnInternal() {
        running.set(false);
        try {
            if (mMgmt != null) mMgmt.stopVPN(false);
        } catch (Exception ignored) {}
        try {
            if (mProcThread != null) mProcThread.stopProcess();
        } catch (Exception ignored) {}
        mMgmt = null;
        mProcThread = null;
        tun = new TunState();
    }

    @Override
    public void onRevoke() {
        VpnStatus.logWarning("VPN revocada por el sistema");
        lastError = "El sistema revocó el permiso de VPN.";
        stopVpnInternal();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopVpnInternal();
        VpnStatus.removeStateListener(svcListener);
        super.onDestroy();
    }

    // ---- notificación persistente ----

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, com.drex.hyperion.MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT);
        Intent disc = new Intent(this, OvpnVpnService.class)
                .setAction(ACTION_DISCONNECT);
        PendingIntent dpi = PendingIntent.getService(this, 1, disc,
                PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Hyperion VPN · " + currentLabel)
                .setContentText(text)
                .setSmallIcon(com.drex.hyperion.R.drawable.ic_tab_shield)
                .setContentIntent(pi)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Desconectar", dpi).build());
        return b.build();
    }

    private void updateNotification() {
        if (!running.get()) return;
        String text;
        if (lastLevel == ConnectionStatus.LEVEL_CONNECTED) text = "Conectado · tus datos pasan por el túnel";
        else if (lastLevel == ConnectionStatus.LEVEL_AUTH_FAILED) text = "Falló la autenticación";
        else text = "Conectando… (" + lastState + ")";
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification(text));
    }

    // ---- métodos que usa OpenVpnManagementThread (réplica mínima de
    // ---- la lógica de tun de OpenVPNService de ics-openvpn) ----

    public ParcelFileDescriptor openTun() {
        Builder b = new Builder();
        TunState tc = tun;
        tun = new TunState(); // reset como hace ics-openvpn

        if (tc.v4ip == null && !tc.hasV6) {
            VpnStatus.logError("OpenVPN pidió abrir el túnel sin dirección IP");
            return null;
        }
        try {
            if (tc.v4ip != null) b.addAddress(tc.v4ip, tc.v4prefix);
            if (tc.hasV6) b.addAddress(tc.v6ip, tc.v6prefix);
        } catch (IllegalArgumentException iae) {
            VpnStatus.logError("Dirección IP inválida: " + iae.getMessage());
            return null;
        }
        for (String d : tc.dns) {
            try { b.addDnsServer(d); }
            catch (IllegalArgumentException iae) { VpnStatus.logWarning("DNS inválido: " + d); }
        }
        b.setMtu(tc.mtu);
        for (String[] r : tc.routes4) {
            try { b.addRoute(r[0], Integer.parseInt(r[1])); }
            catch (Exception e) { VpnStatus.logWarning("Ruta inválida: " + r[0] + "/" + r[1]); }
        }
        for (String[] r : tc.routes6) {
            try { b.addRoute(r[0], Integer.parseInt(r[1])); }
            catch (Exception e) { VpnStatus.logWarning("Ruta IPv6 inválida: " + r[0] + "/" + r[1]); }
        }
        for (String s : tc.searchDomains) {
            try { b.addSearchDomain(s); } catch (Exception ignored) {}
        }
        b.setSession(currentLabel.isEmpty() ? "Hyperion VPN" : currentLabel);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            b.setUnderlyingNetworks(null);
        }
        try {
            ParcelFileDescriptor pfd = b.establish();
            if (pfd == null) VpnStatus.logError("Builder.establish() devolvió null");
            else VpnStatus.logInfo("Túnel abierto: " + tc.v4ip + "/" + tc.v4prefix + " mtu " + tc.mtu);
            return pfd;
        } catch (Exception e) {
            VpnStatus.logException("Error abriendo el túnel", e);
            return null;
        }
    }

    public void addDNS(String dns) {
        if (dns != null && !dns.isEmpty() && !tun.dns.contains(dns)) tun.dns.add(dns);
    }

    public void addSearchDomain(String domain) {
        if (domain != null && !domain.isEmpty()) tun.searchDomains.add(domain);
    }

    public void addRoute(String dest, String mask, String gateway, String device) {
        try {
            int prefix = maskToPrefix(mask);
            tun.routes4.add(new String[]{dest, String.valueOf(prefix)});
        } catch (Exception e) {
            VpnStatus.logWarning("Ruta ignorada: " + dest + " " + mask);
        }
    }

    public void addRoutev6(String network, String device) {
        // network viene como "2000::/3"
        String[] p = network.split("/");
        if (p.length == 2) tun.routes6.add(new String[]{p[0], p[1]});
        else VpnStatus.logWarning("Ruta IPv6 ignorada: " + network);
    }

    public void setLocalIP(String local, String netmask, int mtu, String mode) {
        tun.v4ip = local;
        tun.v4prefix = maskToPrefix(netmask);
        tun.mtu = mtu;
    }

    public void setLocalIPv6(String ipv6addr) {
        // llega como "addr/prefix"
        String[] p = ipv6addr.split("/");
        if (p.length == 2) {
            tun.v6ip = p[0];
            try { tun.v6prefix = Integer.parseInt(p[1]); tun.hasV6 = true; }
            catch (NumberFormatException ignored) {}
        }
    }

    public void setMtu(int mtu) { tun.mtu = mtu; }

    public void addHttpProxy(String host, int port) {
        VpnStatus.logDebug("Proxy HTTP pedido por el servidor (ignorado): " + host + ":" + port);
    }

    /** Para PERSIST_TUN_ACTION: siempre reabrimos (seguro y simple). */
    public String getTunReopenStatus() { return "OPEN_BEFORE_CLOSE"; }

    /** El hilo lo llama si faltara contraseña (no ocurre: vpn/vpn precargado). */
    public void requestInputFromUser(String needed) {
        VpnStatus.updateStateString("NEED", "need " + needed,
                ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT);
        VpnStatus.logError("OpenVPN pidió entrada del usuario (" + needed + "): no soportado");
    }

    /** Mensajes SSO del servidor (OPEN_URL/CR_TEXT/WEB_AUTH): se registran. */
    public void onSsoInfo(String info) {
        VpnStatus.logDebug("SSO del servidor: " + info);
    }

    private static int maskToPrefix(String mask) {
        String[] p = mask.trim().split("\\.");
        int bits = 0;
        for (String s : p) {
            int v = Integer.parseInt(s);
            while (v != 0) { bits += v & 1; v >>>= 1; }
        }
        return bits;
    }

    private static String readFile(String path) {
        try {
            FileInputStream fis = new FileInputStream(new File(path));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
            fis.close();
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
