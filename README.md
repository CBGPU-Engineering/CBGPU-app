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
