# CBGPU App

[![CI](https://github.com/CBGPU-Engineering/CBGPU-app/actions/workflows/ci.yml/badge.svg)](https://github.com/CBGPU-Engineering/CBGPU-app/actions/workflows/ci.yml)
[![Kotlin](https://img.shields.io/badge/Android-Kotlin-7F52FF?logo=kotlin)](https://developer.android.com/kotlin)
[![Rust](https://img.shields.io/badge/Core-Rust-000000?logo=rust)](https://www.rust-lang.org/)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-D22128)](LICENSE)

The native Android client for **CBGPU**, built with Kotlin and a Rust JNI core.
Version **1.0.11 / build 12** retains the existing application ID
`com.cbgpu.cbgpu_app`, signing identity and mobile website design. The Android
build has no Flutter Gradle plugin, Flutter engine or Dart runtime.

Kotlin owns the Android UI and platform integrations. Existing feature screens
load from `https://app.cbgpu.com`; native workspace tabs render the same labels,
Lucide SVG geometry, Pretendard typeface and theme colors supplied by the site.
Rust validates navigation and native bridge messages. Desktop CBGPU links are
redirected to the mobile workspace; external links use the system browser.

## Build

Install JDK 17, Node.js 22.18+, Rust, Android SDK 36/build tools 36.0.0, and NDK
28.2.13676358. Set `ANDROID_HOME`; do not commit local SDK paths or signing keys.
AGP 9 uses built-in Kotlin and Gradle 9.1. The checked-in Gradle wrapper and its
distribution use the official upstream checksums.

```bash
npm ci --ignore-scripts
npm run check
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo test --manifest-path native-core/Cargo.toml --locked
./android/gradlew -p android assembleDebug assembleDebugAndroidTest
```

The Gradle build compiles Rust for ARM64, ARMv7 and x86_64 and compiles the small
TypeScript bridge into generated JavaScript assets. No Node.js or Rust compiler
is installed on the phone. `android/version.properties` is the Android version
source. Release builds require the **existing** private `android/key.properties`
and its keystore; there is no debug-signing fallback:

```bash
./android/gradlew -p android assembleRelease
```

The APK is `android/app/build/outputs/apk/release/app-release.apk`.
`node scripts/publish-android-release.mts` verifies the existing APK certificate,
package/version and monotonic build without changing downloads. Only an explicit
`--publish` takes the publication lock, backs up the previous APK, atomically
replaces APK files and writes the update manifest last. Release CI additionally
requires a successful emulator CI run for the exact source commit. The signing
certificate must match the currently distributed APK before publishing an update.
The previous APK remains available privately for rollback. Release CI checks the
configured certificate, package/version and checksums before uploading assets.
Never advertise a version before its verified APK is available.

## Native features and security

- Native themed workspace navigation, Android back navigation, deep links,
  file chooser and authenticated material download/share.
- System WebView cookies and origin storage retained across updates, including
  HttpOnly parent cookies used by CBGPU's same-origin login broker. Native code
  never reads shared cookies through JavaScript.
- Native session storage uses AES-GCM with AndroidKeyStore. The previous Flutter
  shared-preferences session can be migrated; cookie/browser data is not cleared.
- All twelve existing native bridge actions are implemented. AndroidX
  `WebMessageListener` grants the channel only to `https://app.cbgpu.com`, and
  dispatch additionally requires the main frame and current trusted URL. Tenant,
  notebook, LaTeX and external origins receive no privileged bridge.
- Isolated official EduSet login, without a CBGPU bridge or copied CBGPU tokens.
  Session candidates are returned only while the original CBGPU owner remains
  signed in; the website verifies them with EduSet before storage.
- Native scheduled/repeating notifications, boot/update alarm restoration,
  notification/exact-alarm permission handling and Firebase push integration.
- Existing GPU, printer, laser, meal and timetable widgets keep their provider
  IDs, layouts and preferences, with native WorkManager refreshes. Private widget
  data is cleared at logout and cannot be committed by a different account's
  stale refresh.

SSL failures fail closed. File/cleartext access is restricted. Tokens, passwords
and school sessions are not logged. Firebase's public Android configuration is
included; service-account credentials and signing keys are not.

## Verification

Rust policy tests exercise mobile routing, bridge origins and tab boundaries.
Android CI builds all three JNI ABIs and runs WebView/JNI boundary tests on an
Android emulator. A local signed build and a compiled instrumentation APK do not
prove physical-device notification delivery, Doze behavior or every manufacturer's
WebView behavior. Those require device validation before broad rollout.

Legacy Dart/iOS/desktop source remains for reference and the previous release;
it is not used by the standalone Android build. This change does not certify a
native iOS replacement. See [CONTRIBUTING.md](CONTRIBUTING.md) and
[SECURITY.md](SECURITY.md). Original code is [Apache-2.0](LICENSE); bundled
Pretendard retains its OFL license and dependencies retain their licenses.

Native push registration explicitly uses `native_kind=android-native-v1`. Deploy
the additive capability schema and owner-bound data-only sender before releasing
this APK. Legacy tokens retain their old delivery mode. The native handler checks
both the unexpired encrypted owner and the current WebView owner; foreign,
ownerless and signed-out messages are ignored. Token changes unregister the old
owner, retire the Firebase installation token, and register a fresh token; a
retirement failure never reassigns the old token to another account.
