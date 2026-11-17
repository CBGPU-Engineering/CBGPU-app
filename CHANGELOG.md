# Changelog

## 1.0.11

- Replace the Android Flutter engine with a standalone Kotlin shell and Rust JNI policy core while retaining the current CBGPU design.
- Preserve package identity, existing signing certificate, WebView cookies, widget IDs and native session migration.
- Bind native notifications, alarms and private widgets to the current signed-in account.
- Require trusted main-frame origins for native bridge calls and retain HttpOnly shared-session rotation.
- Add real Android emulator coverage for WebView/JNI, cookie isolation, Keystore migration and legacy push suppression.

## 1.0.10

- Add capability-negotiated native workspace tabs with the website’s existing labels, Lucide geometry, Pretendard typeface and theme colors.
- Preserve the WebView and login session while navigating through the client router.
- Keep old APK navigation unchanged and hide native tabs for dialogs, the keyboard and full-screen AI.

## 1.0.9

- Open the screen carried by a push notification when it is tapped (signup and GPU request notifications open the phone admin screen).


## 1.0.8

- Expose the installed Android version to the trusted app website so it can show updates only when a newer signed build is available.
- Preserve optional dismissal and the system APK installation flow.

## Unreleased

- Extract CBGPU-app into an independently maintained organization repository.
- Preserve component authorship/history and add component-scoped CI and setup.
- See docs/MIGRATION.md for the original source commit and history mapping.
