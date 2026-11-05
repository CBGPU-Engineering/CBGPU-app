# CBGPU App

[![CI](https://github.com/CBGPU-Engineering/CBGPU-app/actions/workflows/ci.yml/badge.svg)](https://github.com/CBGPU-Engineering/CBGPU-app/actions/workflows/ci.yml)
[![Flutter](https://img.shields.io/badge/Flutter-Android-02569B?logo=flutter)](https://flutter.dev)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-D22128)](LICENSE)

The Flutter Android shell for [CBGPU Web](https://github.com/CBGPU-Engineering/CBGPU-web): mobile navigation,
authentication-session persistence, notifications, home-screen widgets and the
official EduSet login bridge. Current native version: **1.0.7 / build 8**.

## Development

```bash
git clone https://github.com/CBGPU-Engineering/CBGPU-app.git
cd CBGPU-app
flutter pub get
flutter test test/app_navigation_test.dart test/eduset_login_policy_test.dart
```

CI uses Flutter 3.47.8. Prepare your own Firebase configuration and private Android
signing settings for a fork. Do not commit keystores or service-account keys.
Backend schemas and Edge Functions have moved to CBGPU-backend; they are not
maintained in this native client repository.

## Releases

[Download signed APKs](https://github.com/CBGPU-Engineering/CBGPU-app/releases). `vX.Y.Z` tags must match
`pubspec.yaml`; increase the Android build number for each new app update.
The `android-release` GitHub environment restricts signing secrets to release
tags. The workflow verifies the signing certificate and publishes an APK plus
SHA256SUMS. Local builds use `flutter build apk --release` with the same signing
key if they must update an existing installation.

The repository includes other platform targets, but they need their own setup
and device validation. Policy tests do not certify physical phone behavior.
See [CONTRIBUTING.md](CONTRIBUTING.md) and [migration notes](docs/MIGRATION.md).

## License

Original code is [Apache-2.0](LICENSE); dependencies retain their own licenses.

### In-app Android updates

The trusted `app.cbgpu.com` WebView bridge exposes the installed package version
from Android's package manager. The website checks `/api/app-release` at startup,
on return to the foreground, and every 30 minutes while visible. It offers an
optional update only for a newer Android build; dismissing it snoozes that build
for 24 hours. Shipped apps before 1.0.8 lack version reporting and receive the
upgrade notice once a build >=9 is published. Browser users are never prompted.

After building with the existing release signing key, publish on the host with:

```bash
python3 scripts/publish-android-release.py
```

This verifies package identity, source version and the existing APK certificate,
backs up the previous APK privately, replaces the download atomically, and writes
`android-release.json` last. The web service reads that host manifest at runtime,
so future APK publications do not need a web rebuild. Never advertise a build
before its verified APK is available. Android still asks the user to install the
update; silent installation is not used.
