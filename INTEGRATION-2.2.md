# Hyperion 2.2 — nota de integración (2026-09-27)

Código final integrado en `~/workspace/hyperion` desde 5 copias de trabajo
paralelas. Paquete `com.drex.hyperion`, versionCode 4, versionName 2.2.
APK firmado: `~/workspace/your_files/Hyperion-2.2.apk` (mismo certificado que 2.1).

## Fuentes por track

- **W1 países VPN** (`hyperion-w1`): `src/.../ovpn/` (11 clases, núcleo
  adaptado de ics-openvpn v0.7.65), `VpnGateClient.java`, `VpnGateServer.java`,
  `FlagDrawables.java`, `native-libs/{arm64-v8a,armeabi-v7a}` (libovpnexec.so +
  libopenvpn.so), 16 banderas `res/drawable/flag_*.xml`,
  `res/layout/item_country_group.xml` + `item_vpngate_server.xml`,
  `screen_vpn.xml` reescrito, `res/raw/gpl_ics_openvpn.txt` (atribución GPL v2).
  Eliminados: `VpnCountries.java`, `res/raw/servers.json`,
  `res/layout/item_country.xml` (la lista "Próximamente" fue reemplazada por
  servidores VPNGate reales).
- **W2 bloqueo de anuncios** (`hyperion-w2`): `DnsEngine.java` (RST a DoH/DoT,
  A→0.0.0.0 / AAAA→NODATA), `Blocklist.java`, `blocker/AdBlocker.java`,
  `blocker/PacketUtil.java`, `blocker/DnsLogStore.java`,
  `blocker/DnsHealth.java`, `DnsLogActivity.java` +
  `res/layout/activity_dns_log.xml`, `res/raw/doh.txt`,
  `blocker/ShieldController.java` + `screen_shield.xml` (botón registro DNS).
- **W3 desconexión/bugs** (`hyperion-w3`): `HyperionVpnService.java`
  (intent-null → stopSelf, START_NOT_STICKY), `VpnController.java`
  (flag `disconnecting`, `cancelLaunch()`), `MainActivity.java`
  (onActivityResult resetea el botón si se cancela el permiso).
- **W4 cero emojis** (`hyperion-w4`): 6 drawables `ic_*` + `screen_speed.xml`,
  `screen_security.xml`, `BoostEngine.java`, `SecurityController.java`,
  `SpeedController.java`.
- **W5 móvil** (`hyperion-w5`): `mobile/DataPlan.java`,
  `mobile/PlanBootReceiver.java`, `res/drawable/plan_bar.xml`,
  `mobile/MobileController.java` + `screen_mobile.xml`.

## Fusiones manuales (archivos tocados por 2+ tracks)

- `HyperionVpnService.java`: base W2 (rutas /32 DoH/DoT, multiplex de
  listeners) + los 2 fixes de W3 (null-intent guard, START_NOT_STICKY).
- `VpnController.java`: base W1 (UI de países + túnel remoto) + flag
  `disconnecting` y `cancelLaunch()` de W3 (nota: `cancelLaunch()` llama a
  `button.setConnected(false)`, que resetea `LaunchButton.launching`).
- `MainActivity.java`: base W1 (REQ_OVPN=101, startRemoteVpn, stopVpn detiene
  ambos modos) + rama de cancelación de permiso de W3.
- `AndroidManifest.xml`: versión 2.2 + permisos de W1
  (FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS) y W5
  (RECEIVE_BOOT_COMPLETED) + `OvpnVpnService` (specialUse) + `DnsLogActivity`
  + `PlanBootReceiver` + `extractNativeLibs="true"`.

## Limpieza de UI (barrido 2.2)

- Cero emojis de color en toda la app (se quitaron los que W2 había
  introducido en el registro DNS: se usan `ic_warn` y glifos ✕/✓).
- Textos de layout acortados: ningún `android:text` supera ~100 caracteres;
  se eliminaron párrafos explicativos de batería, seguridad, velocidad,
  escudo y VPN.

## Verificación

- `bash build.sh` en verde: aapt2 + javac + d8 + zipalign + apksigner.
- `apksigner verify` OK; certificado SHA-256 idéntico al de 2.1
  (b56cf1e78d06a1697d425f87b30ea263db2ab3c40a7a212a67181aabc013da8d).
- Test DNS: 57/57 pass contra las clases integradas.
- Test DataPlan.cycleFor: 6/6 pass.
- `aapt dump badging`: package com.drex.hyperion versionCode 4 versionName 2.2.

## Límites honestos (solo validables en teléfono real)

- El túnel OpenVPN no se puede probar en este servidor (sin /dev/kvm ni
  Android): probar conectar a un servidor JP/KR, verificar IP pública,
  fallo de servidor ("No disponible") y cambio con túnel activo.
- El registro DNS muestra eventos reales; si el usuario tiene DNS privado
  (DoT) o "DNS seguro" de Chrome activos, el filtro se evade: la app lo
  detecta y guía a desactivarlos.
- Las alertas del plan de datos se evalúan con la app abierta; con la app
  cerrada, un reinicio pliega el consumo previo (PlanBootReceiver).
