# Google Play Console submission guide

Prepared 17 October 2026 for Android TV only. Source/build readiness does not guarantee policy approval. Review `VALIDATION.md` and resolve the listed publishing blockers first.

## 1. Account and app identity

Complete developer identity/contact verification required by your Console account. Create an app named **STBPlay**, default language English (United States), type App. Choose the intended free/paid model yourself; the current code has no purchases or ads. Package `com.stbplay.app` must be available/associated with your app. Do not use a new package for updates to an existing listing.

Publisher/contact prepared here: Krishnendu Patra / krsptrbeez@gmail.com. The Console may require additional verified contact/address details for your account, monetization status and markets; these have not been supplied or verified in this project.

## 2. Store listing

Use the exact text files under `publishing/store-listing/`. Suggested category: Video Players & Editors, subject to the Console's current options. Choose only accurate tags.

Upload:

- `assets/app-icon-512.png` (512×512).
- One of the three `assets/feature-graphics/` options (1024×500; upload one as the primary graphic).
- Android TV banner `assets/tv-banner-1280x720.png` (1280×720). The separate 320×180 launcher banner belongs inside the app and is not the store banner.
- Unaltered Android TV screenshots from `assets/screenshots/`, up to eight for this device type. These show fictional demo data in the real app. No provider credentials, copyrighted program art or third-party streaming logos are supplied. Do not add device frames or promotions that imply included channels.

The short description is within 80 characters; the full description is within 4,000. No rankings, download-count claims, unsupported format guarantees or suggestions of free premium content are included.

## 3. Privacy policy and app content

Host `privacy-site/`, test the live public HTTPS URL, and paste it into Console. The same policy is already inside Settings. Use `DATA-SAFETY.md` to prepare the Data safety answers. Resolve HTTP transport before calling the submission compliant.

Complete the actual App content declarations shown by your Console:

- Ads: the app adds no advertising SDK or developer ads. External provider streams may contain their own programming/ads; accurately review the Console's current "contains ads" definition for the submitted experience instead of treating all third-party programming as publisher ad inventory.
- App access: browsing/playback needs a user-supplied source. Provide a **lawful, reliable reviewer source or test credentials** privately in App access, with connection steps. The empty app UI alone cannot demonstrate restricted functionality. Do not put credentials in this project, store listing, GitHub Pages or screenshots. Use a dedicated test account, not your personal subscription. If enabling the optional PIN for a review build, include its test PIN privately or leave it disabled.
- Target audience: the app is not specifically directed at children and makes no child-safety/content filtering claims. The owner must choose the true intended age groups; an adult-only audience may be appropriate for a general external-source utility, but must reflect your actual intended users/marketing. Do not select children merely because there is a PIN feature.
- Content rating: complete IARC honestly for the app's accessible experience and external content capabilities. Do not assume an unrestricted IPTV player deserves an Everyone rating simply because it bundles no catalog. Do not advertise access to prohibited or infringing content.
- Content rights: use only sources/content you and reviewers are authorized to access. No content rights/license verification for arbitrary providers has been performed by this project. A player disclaimer does not authorize copyrighted content.
- Account deletion: no STBPlay account creation exists. The app's local data controls are available; provider accounts remain managed by those providers. If adding STBPlay accounts later, implement Google's account-deletion requirements then.
- Any further financial, health, news, government, accessibility-service, advertising-ID or other declarations: answer based on actual functionality; the app has none of these integrations in this build. Read the current Console question before answering.

## 4. Android TV form factor

Enable Android TV distribution in the Console's form-factor settings. The app requires Leanback, supports D-pad navigation, does not require touch, and includes a TV launcher banner. minSdk is 24 and targetSdk is 35. Google's current TV target requirement is API 34 or newer; this build satisfies that target-level threshold. This does not establish every TV quality criterion.

TV review also covers remote focus/input, Back/Home, playback controls, lifecycle pause/resume, physical-TV stability, memory on low-RAM devices, and 32/64-bit/16-KB compatibility where native code exists. Use the artifact-specific validation and the physical-TV checklist.

## 5. Upload and test

Generate a release AAB with your upload key, enroll in Play App Signing, and upload to internal testing first. Review automated/pre-launch reports and the TV device catalog. Install the Play-delivered build on actual TV devices and complete final testing.

If your personal account is subject to Google's closed-testing requirement, complete the current required test period and apply for production access. Do not try to substitute the local test APK for a Play test track.

## 6. Production release

Resolve all policy/technical warnings, approve the final Data safety preview, verify the published privacy URL, confirm reviewer access and screenshots, then create the production release. Suggested release notes are in `store-listing/release-notes.txt`. Roll out gradually if the Console supports that for your release and monitor user reports/Android vitals. Nothing has been uploaded or published automatically.

Sources: [TV quality](https://developer.android.com/docs/quality-guidelines/tv-app-quality), [target SDK](https://developer.android.com/google/play/requirements/target-sdk), [visual assets](https://support.google.com/googleplay/android-developer/answer/9866151), [testing](https://support.google.com/googleplay/android-developer/answer/14151465), [intellectual property](https://support.google.com/googleplay/android-developer/answer/9888072).
