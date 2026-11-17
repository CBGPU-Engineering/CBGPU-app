# Third-party components

Original application code is Apache-2.0. The standalone Android application uses
AndroidX (Apache-2.0), Firebase Android SDK (Apache-2.0), Rust libraries listed in
`native-core/Cargo.lock`, and the Android system WebView. Upstream libraries retain
their respective licenses; a source license does not grant rights to service names
or trademarks. Firebase client configuration is public app configuration, not a
substitute for server access control; forks must use their own Firebase project.

- AndroidX source and notices: https://android.googlesource.com/platform/frameworks/support/
- Firebase Android SDK source and notices: https://github.com/firebase/firebase-android-sdk
- Rust dependency licenses: each pinned crate's `license` / `license-file` metadata
  and source notice in the Cargo registry. Inspect `cargo metadata --locked` before
  redistributing a modified dependency set.
- Standard Gradle wrapper/build tooling: https://github.com/gradle/gradle (Apache-2.0).
- Existing Lucide icon geometry: https://github.com/lucide-icons/lucide (ISC).

## Pretendard

Native hybrid navigation embeds Pretendard Variable by Kil Hyung-jin / orioncactus
to retain the website's typeface without a runtime font download.
Source: https://github.com/orioncactus/pretendard
Licensed under SIL Open Font License 1.1; full text is in assets/fonts/OFL.txt.
The navigation receives the website's existing Lucide SVG geometry rather than
substituting a different Android icon family. Existing Lucide licensing applies.
