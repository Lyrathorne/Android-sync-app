# DeviceSync for Android

[English](README.md) | [Русский](README.ru.md)

DeviceSync is the Android companion for a Windows PC. It discovers and securely pairs with the desktop app, then provides local file transfer, clipboard sharing, notification forwarding, phone-file access, background reconnect, and an optional DeviceSync keyboard.

<p align="center">
  <img src="docs/screenshots/devicesync-app.png" width="760" alt="DeviceSync Android home screen with computer status and quick actions">
</p>

> DeviceSync is under active development. It requests powerful permissions only for the features you enable; review the [permissions](#android-permissions) and [current limitations](#current-limitations).

## Contents

- [What it does](#what-it-does)
- [Screenshots](#screenshots)
- [Requirements](#requirements)
- [Install and run](#install-and-run)
- [Build from source](#build-from-source)
- [Pair with Windows](#pair-with-windows)
- [Everyday use](#everyday-use)
- [Architecture](#architecture)
- [Security and privacy](#security-and-privacy)
- [Android permissions](#android-permissions)
- [Development and testing](#development-and-testing)
- [Troubleshooting](#troubleshooting)
- [Current limitations](#current-limitations)
- [Contributing and licensing](#contributing-and-licensing)

## What it does

The Android app is the mobile endpoint of DeviceSync. It owns the connection to a trusted Windows computer and routes negotiated protocol messages to file, clipboard, notification, catalog, and synchronization components.

Implemented capabilities include:

- mDNS/DNS-SD and UDP-beacon discovery, QR scanning, and manual IP connection;
- QR pairing, persistent device trust, TLS server SPKI pinning, and signed identity authentication;
- automatic reconnect on LAN, hotspot, and USB tethering, with a slow Bluetooth RFCOMM fallback;
- bidirectional streamed file transfer with SHA-256 and resumable V2 checkpoints;
- manual text sharing and opt-in clipboard synchronization;
- Android notification forwarding with notification-access consent and an app allowlist;
- a permission-gated media and folder catalog for the Windows phone-files view;
- transfer queues, foreground services, and reboot recovery for enabled background work;
- experimental folder synchronization;
- an optional modular RU/EN DeviceSync keyboard with local suggestions, T9, emoji, clipboard tools, and privacy modes;
- English and Russian application resources.

The Android and Windows applications negotiate protocol versions and capabilities. Unsupported features stay disabled rather than being assumed.

## Screenshots

<p align="center">
  <img src="docs/screenshots/devicesync-app.png" width="48%" alt="DeviceSync home screen and trusted computer status">
  <img src="docs/screenshots/devicesync-app-scrolled.png" width="48%" alt="DeviceSync home screen quick file action">
</p>

The repository currently contains only home-screen captures. Clean screenshots of discovery, QR pairing, transfers, clipboard, notifications, permissions, background settings, phone catalog, and keyboard onboarding are still needed for a complete gallery.

## Requirements

| Use case | Requirement |
|---|---|
| Run the app | Android 8.0 or newer (`minSdk 26`) and a compatible DeviceSync Windows app |
| Connect over LAN | Phone and PC on the same reachable private network |
| Build from source | Android SDK 36, JDK 17 or a compatible newer JDK, and the included Gradle wrapper |
| Scan a pairing QR | A device with a camera, or use manual connection where appropriate |
| Background features | Notification permission on Android 13+, foreground-service notification, and vendor battery settings that allow DeviceSync to run |
| Bluetooth fallback | Bluetooth support and prior pairing in Android and Windows system settings |

The current app configuration targets Android API 36 and JVM bytecode 17. Kotlin is `2.2.10`, Android Gradle Plugin is `8.13.0`, and the wrapper uses Gradle `9.4.1`.

## Install and run

APK and AAB files are generated artifacts and are excluded from Git. Install a trusted APK supplied by the repository owner when available, or build one locally.

After creating a debug build, install it with Android Studio or ADB:

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

The application ID is currently `com.example.devicesync`. It is a development identifier and should be renamed before store publication.

## Build from source

Open the repository root in Android Studio, select a JDK compatible with the project (Java 17 language/bytecode target), allow Gradle sync to complete, choose the `app` run configuration, and run it on an API 26+ device or emulator.

If Java is not already available in your terminal, point `JAVA_HOME` at Android Studio's bundled runtime. A typical Windows installation uses:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
```

From PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
```

Debug APK:

```text
app\build\outputs\apk\debug\app-debug.apk
```

Create a local release build:

```powershell
.\gradlew.bat :app:assembleRelease
```

Release APK:

```text
app\build\outputs\apk\release\app-release.apk
```

Production signing can be provided through these environment variables:

```text
DEVICESYNC_ANDROID_KEYSTORE
DEVICESYNC_ANDROID_STORE_PASSWORD
DEVICESYNC_ANDROID_KEY_ALIAS
DEVICESYNC_ANDROID_KEY_PASSWORD
```

Without them, the local Release task falls back to debug signing and is not a production-distribution build. Never commit keystores or signing secrets.

## Pair with Windows

1. Start DeviceSync on Windows and keep both devices on the same private network.
2. In Windows **Devices**, choose **Connect phone** to display a time-limited QR code.
3. On Android, choose **Add computer** and allow camera access.
4. Scan the QR, review the verification information, and approve the pairing.
5. The trusted computer is stored locally and can reconnect without a new QR while its identity remains unchanged.
6. Enable background connection only if you want reconnect, notifications, or queued transfers while the Android UI is closed.

If multicast discovery is unavailable, use manual IPv4 entry. The default Windows endpoint uses TCP `54321`.

## Everyday use

### Send or receive files

Use the home-screen quick action or the device details screen to select a file. Incoming offers require approval unless a trusted-device automation policy explicitly allows them. Data is streamed instead of intentionally loaded into memory as one large buffer, checked with SHA-256, and finalized only after validation.

### Share clipboard text

Enable clipboard sharing in settings and for the selected trusted computer. Manual sending is the safer default. Automatic mode is opt-in, suppresses loops, and avoids sending empty or privacy-sensitive contexts where the app can identify them.

### Forward notifications

Open notification settings, grant Android's special notification-listener access, and choose allowed applications. Only enabled packages are forwarded to an authenticated Windows session. You can revoke the special access at any time in Android settings.

### Let Windows browse phone files

The catalog can expose permitted media, `Download`/`Documents` with all-files access where the user explicitly grants it, and additional folders selected through Android's document picker. Access can be revoked from DeviceSync settings.

### Keep the connection in the background

Enable background connection in DeviceSync. Android shows a foreground-service notification. Some vendors still suspend long-running apps; use **Allow unrestricted battery usage** only when background reliability is required.

### Enable DeviceSync Keyboard

Open the keyboard onboarding screen, enable DeviceSync Keyboard in Android's input-method settings, and select it as the active keyboard. Its dictionaries and suggestions run locally. Sensitive/incognito input disables suggestions, clipboard history, and learning-related behavior.

## Architecture

```text
app/                 Compose UI, connection graph, protocol, security and features
keyboard-engine/     Platform-light keyboard state, layout and suggestion engine
keyboard-ime/        Android InputMethodService and keyboard UI integration
docs/                Architecture, privacy, testing and media-catalog notes
third_party/         Dictionary licences and attribution
```

Key app areas:

| Area | Main responsibility |
|---|---|
| `core/network` | One connection/session owner, TLS, reconnect, transports and capability routing |
| `core/security` | QR parsing, pairing, Android Keystore identity, and trusted-device records |
| `core/transfer` | Incoming/outgoing files, queues, checkpoints and history |
| `core/sharing` | Clipboard and text sharing |
| `core/notifications` | Notification listener, allowlist, payload mapping and forwarding |
| `core/catalog` | Permission-gated Android media/folder catalog and thumbnails |
| `core/background` | Foreground services, reboot handling and background policy |
| `feature/*` | Compose screens and ViewModels |

`ConnectionManager` is the single normal-session socket reader. Feature managers receive routed messages instead of competing for the input stream. Wire frames use a four-byte big-endian length prefix followed by UTF-8 JSON shared with the Windows implementation.

## Security and privacy

- The LAN connection uses TLS and requires the Windows SPKI fingerprint received during pairing.
- Android stores the long-lived identity key in Android Keystore.
- Signed challenge/response authentication binds a reconnect to the trusted DeviceSync identity.
- Trusted devices, queues, and processed-message state are stored locally with Room/DataStore-backed repositories.
- DeviceSync does not need a cloud account for its direct synchronization path.
- File transfer validates metadata, offsets, size, temporary-file state, and SHA-256 before completion.
- Notification forwarding and clipboard automation are disabled until the user enables them.
- The keyboard does not send typed text for suggestions; local clipboard history is AES-GCM encrypted with a non-exportable Android Keystore key.
- Diagnostics are designed to redact secrets, content, and personal paths before support export.

No permission replaces trust: pair only your own PC, review each feature, and revoke devices that you no longer control.

## Android permissions

The manifest declares permissions for optional features. Runtime or special access is requested only when the corresponding flow needs it.

| Permission or special access | Purpose |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Local TCP/TLS connection and network-state monitoring |
| `CHANGE_NETWORK_STATE`, `CHANGE_WIFI_STATE` | Network-aware connection and discovery support |
| `CAMERA` | Scan the Windows pairing QR code |
| `POST_NOTIFICATIONS` | Show connection and transfer foreground-service notifications on Android 13+ |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Maintain an enabled device connection and active transfers under Android background rules |
| `WAKE_LOCK` | Keep explicitly enabled connection/transfer work alive long enough to complete |
| `RECEIVE_BOOT_COMPLETED` | Restore eligible background behavior after boot, app replacement, or user unlock |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Open the system flow for unrestricted battery use when the user requests it |
| `BLUETOOTH*` / Nearby devices | Discover already paired Bluetooth devices and use the RFCOMM fallback |
| `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO` | Expose user-approved media in the phone catalog on modern Android |
| `READ_EXTERNAL_STORAGE` | Legacy media access through Android 12L and older |
| `READ_MEDIA_VISUAL_USER_SELECTED` | Access only the visual media selected by the user where supported |
| `MANAGE_EXTERNAL_STORAGE` | Optional full access to `Download` and `Documents`; broad and not required when selected folders are sufficient |
| Notification listener special access | Read notifications from allowlisted apps for forwarding to Windows |
| Input method service enablement | Let the user select DeviceSync Keyboard; Android grants this only through system settings |
| `VIBRATE` | Keyboard haptics and relevant local feedback |

`MANAGE_EXTERNAL_STORAGE`, notification-listener access, unrestricted battery use, and keyboard enablement are high-impact choices. Leave them disabled if you do not use those features.

## Development and testing

Run the JVM test suites, lint, and build:

```powershell
.\gradlew.bat :keyboard-engine:test `
  :keyboard-ime:testDebugUnitTest `
  :app:testDebugUnitTest `
  :app:lintDebug `
  :app:assembleDebug
```

Run connected instrumentation tests on an available emulator or device:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

Protocol changes should be matched by Windows tests and shared vectors. Keyboard changes should also follow the [manual test matrix](docs/KEYBOARD_MANUAL_TEST_MATRIX.md), [privacy model](docs/KEYBOARD_PRIVACY.md), and attribution requirements.

## Troubleshooting

### The PC is not discovered

Make sure both devices are on the same LAN and client isolation is disabled. Temporarily disconnect VPNs that alter local routing. Use manual IP connection if multicast DNS is blocked.

### The connection times out

Start the Windows app first, allow it through Windows Firewall on private networks, and verify TCP port `54321`. Do not disable the firewall entirely.

### QR pairing fails

Allow camera permission, increase screen brightness if necessary, and generate a fresh QR code. Remove stale trust records from both sides if the Windows identity was rebuilt or reset.

### Reconnect stops after closing the app

Enable background connection, allow DeviceSync notifications, and check Android's battery settings. OEM task killers can still override standard Android foreground-service behavior.

### Notifications are not forwarded

Grant notification-listener access in system settings, enable forwarding in DeviceSync, and add the source package to the allowlist. Confirm the Windows endpoint negotiated `notifications-v1`.

### Windows cannot see phone files

Grant only the media categories or folders you want to expose. Full `Download`/`Documents` browsing may require all-files access. Reopen the catalog after changing permissions.

## Current limitations

- The package/application ID is still `com.example.devicesync` and is not ready for store publication.
- No APK or AAB is committed, and the repository does not document an official public distribution channel.
- Android vendor battery policies can interrupt background reconnect despite a foreground service.
- Bluetooth is a deliberately slow fallback for clipboard, commands, and small files; LAN-class transfers should use Wi-Fi, hotspot, or USB tethering.
- Folder sync, alternate transports, the keyboard, and some automation policies require broader real-device validation.
- The current screenshot set covers only the main screen.

## Contributing and licensing

Before submitting a change:

1. keep `build`, `.gradle`, APK, AAB, keystores, and `local.properties` out of Git;
2. add tests for behavior and permission changes;
3. update both platform implementations and protocol vectors for wire changes;
4. run tests and lint;
5. document security, battery, migration, and privacy impact.

No root `LICENSE` file is currently present, so the repository does not state a general reuse license. Ask the repository owner before redistributing or reusing the project. Keyboard and dictionary attribution is documented in [KEYBOARD_THIRD_PARTY_NOTICES.md](docs/KEYBOARD_THIRD_PARTY_NOTICES.md), with additional notices in `third_party/`.

The companion Windows repository is [Lyrathorne/Windows-sync-app](https://github.com/Lyrathorne/Windows-sync-app).
