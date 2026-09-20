# AIO MDM Agent (Device Owner DPC)

Standalone Android app that installs as a normal APK, becomes **Device Owner** via Android
Enterprise provisioning, and manages the device by talking to the existing AIO MDM Go server over
the **same WebSocket + HTTP contract** as the AOSP system-app client.

Unlike the platform-signed AOSP system-app client ( which must be baked into a platform-signed AOSP image),
this agent runs on a **stock device** (e.g. a Pixel 6). It uses `DevicePolicyManager` (Device
Owner) instead of `android.uid.system`, so a few capabilities degrade — see the capability matrix
in the plan. It advertises `agent_type=dpc` + its capability list on checkin so the dashboard can
gray out actions it can't perform.

## Requirements

- JDK 17, Android SDK 35, Build-Tools 35, Platform-Tools.
- On Arch: run `./scripts/setup-arch-toolchain.sh` (installs everything via `yay`, or use
  `yay -S android-studio`).

## Build

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Point the build at your server/key without editing source:

```bash
./gradlew assembleDebug -PmdmServerUrl=http://10.0.2.2:8080 -PmdmApiKey=<DEVICE_API_KEY>
```

(These are only defaults; the onboarding screen can override them at runtime.)

## Provision on a device (dev / adb path)

The device must have **no accounts** added (fresh or factory-reset) for `set-device-owner` to
succeed.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell dpm set-device-owner aio.app.mdmclient.dpc/.MdmDeviceAdminReceiver
# reach a locally-running server from the device:
adb reverse tcp:8080 tcp:8080
```

Open the **AIO MDM** app, set the server URL (`http://localhost:8080` with the reverse
above) + device API key, and tap **Save & connect**.

> **On GMS devices, the QR path does not work today.** Google Play Protect gates
> non-allowlisted custom DPCs at *provisioning* (QR and zero-touch), which a Pixel 3a XL
> confirmed on 2026-09-20: a correct payload — download location, checksums and all — still
> aborts in the setup wizard. The `adb` path above is unaffected and is the enrollment
> route for GMS hardware until the DPC is allowlisted with Google. The QR is for non-GMS
> devices; `aio-mdm-web/tools/enroll-adb.sh` does the adb path for one device or a batch.

## Agent OTA (updating this app)

The agent updates itself. The server hosts one agent APK (`/agent/aio-mdm-dpc.apk`); a
device that reports an older `versionCode` gets an **Update agent** action on its device
page, which sends `app_update` with that URL.

`AgentUpdater` downloads it, then refuses anything that is not a genuine upgrade of this
app: same package, same signing key, a higher version code, and the SHA-256 the server
sent. As Device Owner the install is silent. Committing it kills this process, so the
command is acknowledged by the *new* version at startup (`settlePending`) rather than here
— that is why a pending update is written to prefs before the install.

Publishing a new build, given the signing key and an admin API key:

```bash
set -a && . keystore/signing.env && set +a
tools/publish-agent.sh -s https://mdm-stage.dev.aioapp.com -k "$ADMIN_API_KEY"
```

The signing key must never change: Android only installs an update signed with the same
certificate as the build it replaces. CI does the same thing on a push (`.github/workflows/build.yml`).

## Layout

```
app/src/main/
  AndroidManifest.xml
  kotlin/aio/app/mdmclient/dpc/
    AgentApp.kt              notification channels
    AgentConfig.kt           prefs-backed server URL + API key (device-protected storage)
    Capabilities.kt          what this DPC can/can't do (reported on checkin)
    DeviceIdentity.kt        serial / build id / product resolution
    DeviceOwner.kt           DevicePolicyManager + admin component helper
    MdmDeviceAdminReceiver.kt Device Owner receiver
    BootReceiver.kt          start service on boot
    MdmService.kt            foreground service (checkin loop + WS in Phase 1)
    ui/MainActivity.kt       onboarding / status screen
```

## Status

Phase 0 (scaffold) complete. See the implementation plan for phases 1–6 (transport, command
handlers, remote screen/input, diagnostics, server/dashboard adaptation).
