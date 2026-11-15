# Release checklist

1. **Keystore**: `~/smbdrop-keys/upload.jks` + passwords in `keystore.properties` (git-ignored). BACK THESE UP — losing the upload key means a support request to Google to reset it. Enrol in Play App Signing (default for new apps).
2. Bump `versionCode` (must increase every upload) and `versionName` in `app/build.gradle.kts`.
3. Build: `JAVA_HOME=<jdk21> ./gradlew :app:bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`.
4. R8 mapping for crash de-obfuscation: `app/build/outputs/mapping/release/mapping.txt` — upload with the bundle.
5. Play Console: create app → upload AAB to Internal testing first → add yourself as tester.
6. Fill App content: privacy policy URL, Data safety (store/data-safety.md), Photo/video permission declaration and foreground-service declaration (store/permissions-declarations.md), content rating, target audience, ads=no.
7. Store listing: text from store/listing.md, icon, feature graphic, ≥2 phone screenshots (clean install, no personal server names).
8. Pre-launch report: fix anything flagged. Personal developer accounts created after Nov 2023 must run a closed test with 12+ testers for 14 days before production.
9. Test matrix before promoting: Android 8, 12, 14+ phones; OnePlus/Xiaomi/Samsung battery behaviour; a 4 GB upload; Wi-Fi off mid-upload; app killed mid-upload.

## Automated GitHub releases (APK)
- Bump `versionCode`/`versionName` in `app/build.gradle.kts` and add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, commit to `main`: `.github/workflows/release.yml` builds, signs, publishes the GitHub release and creates the `v<versionName>` tag (F-Droid watches tags).
- CI signs with a separate **GitHub release key** stored only in repository secrets (`SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`). Backup: `~/smbdrop-keys/github-release.jks` + `github-release.properties`. Back these up too.
- The **Play upload key** (`~/smbdrop-keys/upload.jks`) never leaves your machine; build the Play AAB locally with `./gradlew :app:bundleRelease`.
