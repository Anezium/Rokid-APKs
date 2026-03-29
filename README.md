## Rokid-APKs

<p align="center">
  <img src="Rokid_APKs_logo.png" alt="Rokid-APKs logo" width="220" />
</p>

<p align="center">
  Upload and install Android APKs on Rokid glasses from your phone over Bluetooth and Wi-Fi Direct.
</p>

---

## Screenshot

<p align="center">
  <img src="Screenshot_Rokid-APKs.jpg" alt="Rokid-APKs app screenshot" width="300" />
</p>
<p align="center">
  <em>Phone companion app with live status for BLE, auth, Wi-Fi Direct, upload, and install.</em>
</p>

---

## Why this repo exists

The original `RokidApkUploader` flow was built around older CXR-M SDK behavior and no longer worked reliably on recent phones and newer Rokid samples. This project rewires the flow around the current SDK and the newer sample logic:

- `com.rokid.cxr:client-m:1.2.1`
- `initWifiP2P2(false, callback)` to discover the expected glasses peer
- a manual Wi-Fi Direct connection step modeled after the newer `CXRMSamples`
- `startUploadApk(apkPath, ipAddress, callback)` to upload against the actual glasses IP

The result is a phone-side uploader that works with the modern Rokid stack instead of relying on the fragile legacy auto-connect path.

---

## How it works

The app scans for Rokid glasses over BLE, opens the Rokid Bluetooth control channel, brings up Wi-Fi Direct, then uploads the selected APK and asks the glasses to install it. The whole transfer stays local between the phone and the glasses.

No developer cable. No desktop helper. No cloud relay.

---

## Features

- BLE scan and device picker for Rokid glasses
- Bluetooth auth flow compatible with the newer CXR-M SDK
- Wi-Fi Direct peer matching that targets the detected glasses instead of random nearby peers
- APK upload and install progress in a terminal-style UI
- Optional serial-number auth path when you do not want to rely on a bundled auth blob

---

## Private credentials

This public repo intentionally excludes private Rokid credentials and auth blobs.

The project can build without them, but the upload flow will stop at runtime until you provide your own local config.

1. Copy `local.properties.example` into `local.properties` and fill in your values.
2. Set `rokid.clientSecret` to your own Rokid developer client secret.
3. If you use a Rokid auth blob, set `rokid.authBlobName` to the raw resource name without the `.lc` extension.
4. Place the matching `.lc` file in `app/src/main/res/raw/<rokid.authBlobName>.lc`.

Example:

```properties
sdk.dir=C\:\\Users\\YourName\\AppData\\Local\\Android\\Sdk
rokid.clientSecret=your-rokid-client-secret
rokid.authBlobName=sn_your_auth_blob_name
```

If you prefer, you can leave out `rokid.authBlobName` and enter the glasses serial number in the app instead. In that case you still need `rokid.clientSecret`.

Any APK you build locally with your own Rokid credentials will embed those values in the generated app. Do not redistribute credentialed builds.

---

## Build

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-22'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

---

## First run

1. Install the app on your Android phone.
2. Put the Rokid glasses into their Bluetooth pairing flow.
3. If Android already auto-connected to the glasses, disconnect them first from Android Bluetooth settings.
4. Open `Rokid-APKs` and grant the requested Bluetooth, Wi-Fi, and nearby device permissions.
5. Optionally enter the glasses serial number if you are using the serial auth path.
6. Tap `Select` and choose the APK you want to send.
7. Tap `Scan` and pick the detected Rokid glasses.
8. Tap `Upload APK` and accept the Bluetooth PIN prompt if Android shows one.
9. Wait for the status panel to move through Bluetooth, Wi-Fi Direct, upload, and install.

---

## Credits

Based on [Miniontoby/RokidApkUploader](https://github.com/Miniontoby/RokidApkUploader) — the original APK uploader for Rokid glasses. This fork rewrites the connection flow for the newer CXR-M SDK and adds a redesigned UI.

---

## Notes

- The repo ignores `local.properties`, `.lc` auth blobs, build outputs, and keystores so they do not end up in Git by accident.
- The Wi-Fi Direct path resets stale P2P state before reconnecting, which helps on Samsung devices that keep broken persistent groups around.
- This project has been build-verified locally and tested on real Rokid glasses, but the exact pairing behavior can still vary by phone vendor and firmware.

