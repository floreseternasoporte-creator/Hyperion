package com.drex.hyperion;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.drex.hyperion.battery.BatteryController;
import com.drex.hyperion.blocker.ShieldController;
import com.drex.hyperion.mobile.MobileController;
import com.drex.hyperion.ovpn.OvpnConnector;
import com.drex.hyperion.ovpn.OvpnVpnService;
import com.drex.hyperion.ui.Cine;

/** Actividad principal: VPN · Velocidad · Seguridad · Escudo · Batería · Móvil. */
public class MainActivity extends Activity {
    private static final int REQ_VPN = 100;
    private static final int REQ_OVPN = 101;
    private static final int TAB_COUNT = 6;

    private View vpnScreen, speedScreen, securityScreen, shieldScreen;
    private View batteryScreen, mobileScreen;
    private View indicator;
    private View[] tabs;
    private ImageView[] tabIcons;
    private TextView[] tabLabels;
    private int current = -1;

    private VpnController vpnController;
    private SpeedController speedController;
    private SecurityController securityController;
    private ShieldController shieldController;
    private BatteryController batteryController;
    private MobileController mobileController;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!OnboardingActivity.isDone(this)) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_main);

        vpnScreen = findViewById(R.id.vpn_screen);
        speedScreen = findViewById(R.id.speed_screen);
        securityScreen = findViewById(R.id.security_screen);
        shieldScreen = findViewById(R.id.shield_screen);
        batteryScreen = findViewById(R.id.battery_screen);
        mobileScreen = findViewById(R.id.mobile_screen);
        indicator = findViewById(R.id.tab_indicator);

        tabs = new View[]{
                findViewById(R.id.tab_vpn),
                findViewById(R.id.tab_speed),
                findViewById(R.id.tab_security),
                findViewById(R.id.tab_shield),
                findViewById(R.id.tab_battery),
                findViewById(R.id.tab_mobile)};
        tabIcons = new ImageView[]{
                (ImageView) findViewById(R.id.tab_vpn_icon),
                (ImageView) findViewById(R.id.tab_speed_icon),
                (ImageView) findViewById(R.id.tab_security_icon),
                (ImageView) findViewById(R.id.tab_shield_icon),
                (ImageView) findViewById(R.id.tab_battery_icon),
                (ImageView) findViewById(R.id.tab_mobile_icon)};
        // etiquetas: segundo hijo de cada tab
        tabLabels = new TextView[TAB_COUNT];
        for (int i = 0; i < TAB_COUNT; i++) {
            android.view.ViewGroup g = (android.view.ViewGroup) tabs[i];
            tabLabels[i] = (TextView) g.getChildAt(1);
            final int idx = i;
            tabs[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { selectTab(idx); }
            });
        }

        LayoutInflater inf = getLayoutInflater();
        vpnController = new VpnController(this, inf, vpnScreen);
        speedController = new SpeedController(this, speedScreen);
        securityController = new SecurityController(this, securityScreen);
        shieldController = new ShieldController(this, inf, shieldScreen);
        batteryController = new BatteryController(this, inf, batteryScreen);
        mobileController = new MobileController(this, inf, mobileScreen);

        selectTab(0);
        // posicionar el indicador cuando el layout esté medido
        indicator.post(new Runnable() {
            @Override public void run() { moveIndicator(0, false); }
        });
    }

    private void selectTab(int idx) {
        if (idx == current) return;
        View[] screens = {vpnScreen, speedScreen, securityScreen, shieldScreen,
                batteryScreen, mobileScreen};
        View outgoing = current >= 0 ? screens[current] : null;
        current = idx;
        // transición cinematográfica (Cine maneja visibility)
        Cine.switchTab(screens[idx], outgoing);
        for (int i = 0; i < TAB_COUNT; i++) {
            int active = (i == idx) ? 0xFF35E0FF : 0xFF8A90B8;
            tabIcons[i].setColorFilter(active);
            tabLabels[i].setTextColor(active);
            if (i == idx) Cine.popAccent(tabIcons[i]);
        }
        vpnController.setVisible(idx == 0);
        shieldController.setVisible(idx == 3);
        batteryController.setVisible(idx == 4);
        mobileController.setVisible(idx == 5);
        moveIndicator(idx, true);
    }

    private void moveIndicator(int idx, boolean animate) {
        int tabW = tabs[0].getWidth();
        if (tabW == 0) return;
        float target = tabW * idx + tabW / 2f - indicator.getWidth() / 2f;
        if (animate) Cine.slideIndicator(indicator, target);
        else indicator.setTranslationX(target);
    }

    // ---------- VPN ----------

    /** Inicia el flujo de permiso + servicio VPN (modo local). */
    public void startVpn() {
        // Exclusión mutua: el modo local detiene el túnel remoto si estaba activo.
        OvpnConnector.disconnect(this);
        Intent prep = VpnService.prepare(this);
        if (prep != null) {
            try { startActivityForResult(prep, REQ_VPN); }
            catch (Exception ignored) {}
        } else {
            doStartVpn();
        }
    }

    public void stopVpn() {
        // Detiene AMBOS modos (el que esté activo; el otro es no-op).
        try { stopService(new Intent(this, HyperionVpnService.class)); }
        catch (Exception ignored) {}
        OvpnConnector.disconnect(this);
    }

    private void doStartVpn() {
        startService(new Intent(this, HyperionVpnService.class));
    }

    // ---------- VPN remota (OpenVPN / VPNGate) ----------

    private VpnGateServer pendingRemoteServer;

    /** Inicia el flujo de permiso + túnel OpenVPN real al servidor indicado. */
    public void startRemoteVpn(VpnGateServer server) {
        pendingRemoteServer = server;
        Intent prep = VpnService.prepare(this);
        if (prep != null) {
            try { startActivityForResult(prep, REQ_OVPN); }
            catch (Exception ignored) {}
        } else {
            doStartRemoteVpn();
        }
    }

    private void doStartRemoteVpn() {
        if (pendingRemoteServer == null) return;
        String err = OvpnConnector.connect(this, pendingRemoteServer);
        if (err != null) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("No se pudo conectar")
                    .setMessage(err)
                    .setPositiveButton("Entendido", null)
                    .show();
        }
        pendingRemoteServer = null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                doStartVpn();
            } else if (vpnController != null) {
                // Barrido Hyperion 2.2: si el usuario cancela el diálogo de
                // permiso, LaunchButton.launching quedaba true para siempre y
                // el botón dejaba de responder a todos los taps.
                vpnController.cancelLaunch();
            }
        }
        if (requestCode == REQ_OVPN && resultCode == RESULT_OK) doStartRemoteVpn();
    }

    @Override
    protected void onDestroy() {
        if (vpnController != null) vpnController.destroy();
        if (speedController != null) speedController.destroy();
        if (securityController != null) securityController.destroy();
        if (shieldController != null) shieldController.destroy();
        if (batteryController != null) batteryController.destroy();
        if (mobileController != null) mobileController.destroy();
        super.onDestroy();
    }
}
