# Signing, final testing and the Play AAB

## Two different keys

Your **upload key** proves to Google that you are allowed to upload a bundle for this app. With Play App Signing, Google signs the APKs delivered to users using the separate **app signing key**. Google can manage that app signing key; you privately retain the upload keystore and its passwords.

The supplied `release-test.apk` is optimized release code signed with the existing development certificate for sideload testing. It is not a production signing key and must not be uploaded to Play. A Play-delivered install will usually have a different certificate. Android will refuse an in-place update from a development-signed install to a different signing certificate. Uninstalling/clearing data removes local profiles, favorites and history; there is intentionally no credential export or cloud backup.

## Your supplied upload key and AAB

A fresh upload key was generated locally at your request on 17 October 2026, outside this project, in the workspace's private signing folder. It uses RSA 3072/SHA256withRSA and a 10,000-day validity. Its keystore/password files have owner-only permissions. The password was not printed in chat or included in the project, website, screenshots, APK or AAB.

Open the separately linked private `READ-ME-PRIVATE.txt` to locate the key, alias and password file. Make an encrypted backup before your first upload. Local owner-only text files are not a substitute for your password manager; move/manage the key and password in your chosen secure storage after backup.

The supplied `STBPlay-0.6.8-release.aab` is signed with this upload key. You can use it for Play internal testing after completing Console setup, privacy hosting and policy decisions. It is not proof of policy approval. Keep using the same upload key for later versions; do not create another key simply because you are building an update.

The instructions below also explain how to select this existing key for a future Android Studio build. Choose **existing key**, rather than Create new, now that one has been supplied.

## Android Studio signing steps

1. Open the `STBPlay-0.6.8` project and let Gradle sync complete. Use Android Studio's configured JDK (the bundled JetBrains runtime used for validation) and the SDK requested by the project.
2. Build → Generate Signed App Bundle / APK → **Android App Bundle** → Next.
3. If you already registered an upload key for this app, select that existing key. Do not create a replacement for an existing Console app unless following Google's upload-key reset process.
4. For this supplied build, select the existing `stbplay-upload.jks` identified in your private signing instructions and alias `stbplay-upload`. Read the password locally from your private storage. Only choose Create new if preparing a different app that has no upload key; keep any new key outside the project and public repositories, with a strong password and validity of at least 25 years.
5. Save the keystore, alias and passwords in a password manager and make a separate encrypted backup. Do not send them in chat, attach them to support email, or check them into Git.
6. Select the **release** variant. Generate the signed `.aab` and note the output location the wizard shows.
7. Create the Console app if needed and enroll in Play App Signing. For a new app, Google's generated app signing key is the simplest managed option. Upload the signed AAB to internal testing first.

Android Studio can inject signing details without editing Gradle. For optional command-line signing, copy `keystore.properties.example` to the ignored `keystore.properties` and fill its values privately. The project consumes that file only when it exists. Alternatively, pass `-Pstbplay.signingPropertiesFile=/absolute/private/path/upload-signing.properties` to use a properties file outside the project. Never put its passwords in a public command. Without signing details, `assembleRelease` produces an unsigned APK and `bundleRelease` produces an unsigned bundle.

```sh
./gradlew testDebugUnitTest lintRelease assembleRelease
./gradlew bundleRelease
```

Run these from the project root. A signed release configuration is needed before the bundle is uploadable. Signing the bundle alone does not resolve a pending privacy URL or HTTP policy issue.

For locally testing the exported policy metadata after hosting:

```sh
./gradlew verifyPlayPublishing
```

Do not put passwords directly in shell command arguments, build logs or documentation. `keystore.properties`, keystores and build outputs are ignored by Git; still inspect your public repository before pushing.

## Test the artifact that Play will deliver

Use an internal test track and install through the Play Store on a registered tester TV. This tests generated APK splits and the Play signing certificate. Run the physical-TV checklist in `FINAL-TV-TESTS.md`, then proceed to closed testing if your developer account requires it. For applicable new personal accounts, Google currently requires at least 12 testers opted in continuously for 14 days before you can apply for production access; actual engagement and the Console's current instructions also matter.

Keep the package name stable and raise versionCode for later uploads. Preserve the release mapping file privately for support; do not publish mapping files alongside signing material.

Sources: [Android app signing](https://developer.android.com/studio/publish/app-signing), [upload bundles](https://developer.android.com/studio/publish/upload-bundle), [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756), [new personal-account testing](https://support.google.com/googleplay/android-developer/answer/14151465).
