# STBPlay 0.6.8 — release preparation

Package: `com.stbplay.app` • version name `0.6.8` • version code `22` • Android TV / Google TV only.
Prepared on 17 October 2026 from the user-tested 0.6.7.7-beta. Earlier versions remain separate.

## What to use

- Open the project root (the folder containing `settings.gradle.kts`) in Android Studio.
- Install the separately supplied `STBPlay-0.6.8-release-test.apk` on the test TV. It uses release optimization and is not debuggable, but uses a development/test certificate. It is **not a Play upload artifact**.
- `publishing/privacy-site/` is the entire standalone GitHub Pages website. Upload only this folder's files to the public policy repository, not the whole Android project.
- `publishing/store-listing/` contains copy/paste descriptions and visual assets.
- `publishing/docs/` contains publishing, signing, Data safety, audit and test instructions.
- `publishing/VALIDATION.md` records actual checks and remaining release decisions. Do not confuse a successful build with Google approval.

## Before uploading to Play

1. Complete final testing on physical TV, including the formerly failing Stalker episodes. User confirmed these worked in 0.6.7.7-beta; test the optimized 0.6.8 APK too.
2. Host the policy page over public HTTPS and paste the live URL into Play Console. The complete policy is embedded inside the app. `privacyPolicyUrl` intentionally stays blank so the app does not offer an online-policy button.
3. Review the retained HTTP secure-transmission issue described in `DATA-SAFETY.md` and `SECURITY-AUDIT.md`. The compatibility release currently preserves HTTP at the owner's earlier request. A privacy disclosure does not make unencrypted credentials compliant.
4. A new **private upload key** has been generated outside this project and is used for the separately supplied signed release **AAB**. Back it up securely and follow `SIGNING-AND-BUILD.md`. Do not upload the supplied test APK or use the shared development key as an upload key.
5. Complete the Console declarations with the Data safety guide, actual content ratings and reviewer access. No developer-account verification, upload or publication has been performed here.

## Production configuration

`publishing.properties` contains publisher/contact information, the optional in-app URL, and an optional `publicPrivacyPolicyUrl` used only for a local pre-submission check. After hosting the page, set the latter and run `./gradlew verifyPlayPublishing`. Its URL check validates syntax, not website availability, legal compliance or review approval.

Do not change the application ID after publishing. Every Play update must have a higher version code than every previously uploaded version. If code 22 has already been used in your Console, raise it before generating the AAB.

No provider subscription, account, portal credential, third-party APK, demo playlist or testing video is bundled into the production application. Demo footage exists only in the Android test APK/assets.
