# Source and release audit — 0.6.8

Scope: STBPlay source, declared dependencies, merged release artifact and focused tests. This is not an independent penetration test or Google certification. Provider servers, their content rights, logging, and retention cannot be audited from an APK.

## Local information and controls

Sensitive profile fields and playback URLs/commands are protected by AES-GCM with random nonces and a per-install data key wrapped by Android Keystore. Legacy values are migrated. Catalog titles/grouping metadata and some preference/progress metadata remain in ordinary app-private storage. Avoid describing this as full-database encryption. PIN verification uses a salted PBKDF2 verifier, with persisted failure throttling. Local clear-data and profile/history deletion are present. Backup/device-transfer extraction is excluded by manifest/rules; vendor behavior is not a guaranteed remote wipe.

## Network

ProviderNetwork handles portal/API requests using Android's TLS validation. OkHttp/Media3 handles playback, with scoped session cookies, origin-aware authorization stripping and HTTPS-downgrade rejection. HTTP compatibility remains present, so authenticated HTTP traffic is unencrypted. There is no permissive certificate verifier or developer proxy. Provider usernames, manually entered registered MAC identifiers and provider catalog/search/playback requests can go off-device.

No hardware MAC, Android ID, advertising ID, location, contacts, camera, microphone or broad media-library reading was found. Subtitle imports use Android's document picker and retain only selected local URI/name where available. No automatic analytics/crash/advertising SDK or publisher backend is configured. No background guide scheduler remains.

## Logging

Custom playback diagnostics log fixed stage/error tags rather than credentials or stream URLs. Media3's own logging is disabled in non-debug builds because library exceptions can include private provider URLs. Android codec/system diagnostics are outside the app's complete control. Do not ask testers to publish unredacted logcat captures.

## Release/configuration

Release is minified and resource-shrunk, with `debuggable=false`. Upload signing is optional/configured privately; no signing key or real keystore properties are distributed. The sideload APK has a development certificate and is for testing only. Debug/test-only dependencies and synthetic test footage must not appear in the production APK. See `VALIDATION.md` for actual manifest, archive, ABI/alignment and test results.

The app's package, version and contact are consistent; the complete policy remains available offline. The exported page is generated from the same source text, with no scripts, external fonts or analytics. No provider credentials or STBEMU APK/decompiled code are included. The comparison informed an independent implementation of the provider's public protocol behavior; proprietary code/assets were not copied.

## Release decisions still owned by the publisher

- Secure transport: the owner chose to retain HTTP on 17 October 2026. HTTP credential handling remains an unresolved policy concern, not something a policy notice fixes.
- Public HTTPS privacy URL: host the provided page, verify global/public accessibility and enter it in Console.
- Secure backup/management of the newly supplied private upload key and Console enrollment/verification.
- Accurate Data safety/content rating/target audience answers and lawful reviewer access.
- Final physical-TV and low-RAM/device compatibility testing; passing an emulator test is not a measurement of every TV model.
- Check the current Play SDK Index/dependency advisories before future releases. This task retains the user-tested media stack to avoid introducing unrelated playback regressions; it does not assert that all dependency versions are the newest available.

Source guide: [User Data](https://support.google.com/googleplay/android-developer/answer/10144311), [Data safety](https://support.google.com/googleplay/android-developer/answer/10787469), [16-KB page sizes](https://developer.android.com/guide/practices/page-sizes).
