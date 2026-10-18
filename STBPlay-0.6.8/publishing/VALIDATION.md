# STBPlay 0.6.8 — validation record

Prepared 17 October 2026. Application ID `com.stbplay.app`, versionCode **22**, versionName **0.6.8**. Based on the user-tested 0.6.7.7-beta, preserving the working 0.6.7.4-beta keyboard implementation byte-for-byte.

## Artifacts and build

- Final Gradle build succeeded: unit tests, release lint, optimized APK/AAB, debug app and Android test APK, privacy export.
- `STBPlay-0.6.8-release.aab`: signed with the newly generated private RSA-3072 upload key; bundletool validation and JAR signature verification passed. The normal self-signed certificate/no timestamp warnings are not a Google approval check.
- `STBPlay-0.6.8-release-test.apk`: minified/resource-shrunk release code, signed with the existing development certificate for TV sideload testing. APK v2/v3 signature verification passed. **Do not upload this test APK to Play.**
- The AAB and test APK contain identical release DEX bytes. No real upload keystore, password file, third-party reference APK or synthetic test video is bundled.
- `zipalign -c -P 16 4` passed. All four packaged native ABIs (armeabi-v7a, arm64-v8a, x86, x86_64) have 16,384-byte ELF PT_LOAD alignment. Actual execution on a 16-KB-page device was not performed.
- Merged release manifest: minSdk 24, targetSdk 35, `debuggable=false`, `allowBackup=false`, HTTP compatibility enabled. INTERNET and ACCESS_NETWORK_STATE plus the app's own signature-protected dynamic-receiver permission; no location, advertising ID, contacts, camera, microphone or broad storage permissions.

## Automated results

| Check | Result |
| --- | --- |
| JVM unit tests | 68 total: **67 passed**, one optional credential-dependent portal smoke skipped; no failures/errors |
| Release lint | **0 errors**, 38 warnings, 10 hints; dependency/version/style advisories retained with the tested media stack |
| Controlled Stalker end-to-end | Passed: series → season → episode → file selection, playable-link resolution and rendered video frames |
| Publishing/security instrumentation | 4 passed: protected local storage/migration/tamper handling, profile deletion, privacy/PIN behavior and manifest |
| Sources/player instrumentation | 3 passed: exclusive collapsed source sections, four-profile limit/update/delete, player directional navigation/focus |
| Keyboard instrumentation | 2 passed: navigation vs editing, hardware Enter, Back, real TV on-screen keyboard repeated D-pad OK letter selection and Done |
| Store capture instrumentation | Passed; eight actual UI captures exported |
| Installed optimized APK black-box check | Passed using external adb/UI Automator: cold launch, Settings, version/publisher/support, offline privacy dialog, notices dialog, absence of TV Guide and removed playback message |
| Asset/policy validator | Passed: description limits; icon, features, banners and eight RGB 1920×1080 screenshots; exact exported/app policy text; no scripts or external embedded resources on policy website |

The initial focused instrumentation run had two test-fixture failures: force-stopping the system keyboard invalidated its input session, and a MockWebServer URL lookup ran on the main thread. Both fixture issues were fixed; all three affected keyboard/capture tests passed on rerun. No production keyboard behavior was changed.

Running the debug AndroidJUnit test runner inside the R8-minified release failed before any test because its Kotlin runtime references did not match the shrunk app. That mixed-variant instrumentation run is **not** counted as a pass. The installed release was instead launched normally and checked independently using external adb/UI Automator, which passed. The production app itself launched successfully without instrumentation.

The emulator was an isolated Android TV API 36 test instance. Tests used a controlled local portal and synthetic footage, not the user's real subscription or formerly failing title. Screenshots contain fictional example.invalid sources and no credentials. No physical-TV 0.6.8 run has been performed here; the owner previously confirmed the provider fix in 0.6.7.7-beta.

## Outstanding before production submission

1. Test the supplied optimized APK on physical TVs, including the previously failing real episodes, remote typing, PIN, source refresh and audio. Test the signed AAB through Play internal testing for actual generated splits/signing.
2. **HTTP remains an unresolved secure-transmission policy risk.** The owner explicitly chose to retain HTTP support. Disclosure and a Data safety encryption answer of No do not satisfy Google's requirement to securely transmit sensitive authentication information. This artifact is not certified or guaranteed Play-compliant. See `docs/DATA-SAFETY.md` and `docs/SECURITY-AUDIT.md`.
3. Host `privacy-site/` over public HTTPS and enter its live URL in Console. No website was published during preparation. In-app policy is complete and offline; no in-app online URL is required for this design.
4. Back up the private upload keystore/password securely. They are deliberately outside the deliverable project. Keep the same upload key for updates.
5. Complete developer verification, accurate Data safety/ratings/audience declarations, lawful reviewer access, any account-specific testing requirement, and Console review. No Console upload/publication has occurred. Confirm versionCode 22 has not previously been used.
6. Dependency advisories/SDK Index and third-party provider practices need continued publisher review. This source/build audit is not an independent security certification or legal review.

Artifact SHA-256 hashes and certificate fingerprints are in `release-manifest.json`. Store asset dimensions are in `store-listing/asset-manifest.json`. Build caches are omitted from the project; Android Studio will regenerate them.

## Minor in-place label/preview update — 17 October 2026

Settings button and dialog title were renamed to **App Licenses**; license content remains bundled. The previously opened `publishing/privacy-template.html` preview now contains the complete policy, while the internal generator template moved to `tools/privacy-template.html`. Both policy exporters were updated. Version 0.6.8/code 22 is unchanged. APK/AAB were rebuilt and signing/asset checks repeated. Earlier emulator/unit results above precede this label-only update; those suites were not rerun for this minor change. Store screenshots 06/07 show the previous Open-source notices label; their interface is otherwise unchanged.
