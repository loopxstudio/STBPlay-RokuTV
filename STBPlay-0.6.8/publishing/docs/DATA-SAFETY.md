# Data safety decision guide — STBPlay 0.6.8

Audited 17 October 2026. This is a source-based preparation guide, not an already-submitted Console form. Google defines collection as data transmitted off the device, including to a third party. "The publisher has no backend" is therefore **not sufficient** to select "No data collected." The form must cover all configurations of the version distributed through Play, including third-party libraries. Re-check the actual Console wording and provider practices before submitting.

## Verified facts

- No publisher analytics, ads, automatic crash uploads or developer-operated user account/backend.
- Only Internet/network-state permissions in application source; verify the merged release manifest in the validation report.
- No reading of hardware MAC, Android ID, advertising ID, location, contacts, camera or microphone. Stalker MAC is manually entered by the user for provider authentication.
- Provider username/password, manually entered MAC, authenticated URLs and catalog/playback requests can leave the device for the chosen provider. Remote Stalker catalog search sends the entered query to that provider.
- Profiles/last-used connection details are encrypted in private local storage. Favorites and recent-item payloads are locally encrypted; ordinary titles, groups, some settings and progress metadata are not a fully encrypted database.
- Subtitle files are selected through the document picker and used locally. The app does not upload them.
- Session cookies are in memory. No background guide downloader remains. No app-controlled cloud backup or device-transfer export is enabled.

## Suggested form approach for the HTTP-compatible artifact

| Console item | Preparation answer / reasoning |
| --- | --- |
| Does the app collect or share required data types? | **Yes is the conservative starting point** because authentication identifiers and remote searches are transmitted to providers. Do not select No just because the developer has no copy. |
| Personal info → User IDs | Disclose provider usernames/account identifiers when used. Purpose: app functionality/authentication. The user can choose an M3U source instead of supplying Xtream credentials, so review the form's optional/required wording for the particular feature. |
| Device or other IDs | Disclose user-entered provider MAC identifiers and provider session identifiers where in scope. They are not read from Android hardware and are not used for advertising. Purpose: app functionality. Optional across source types. |
| App activity → In-app search history | Disclose searches actually sent to a provider for remote catalog lookup. Local-only channel filtering is not collection. Purpose: app functionality; searches are optional. |
| App activity → Other actions / App interactions | Review the category matching requested channels/titles, episode selection and playback requests visible to providers. Conservatively include content-selection activity where the form treats it as app activity. There is no developer analytics telemetry. Purpose: app functionality. |
| Location | The app does not access or infer location. Do not declare precise/approximate location simply because Internet packets have an IP address. If a particular integrated service actually derives/retains location from IP, that behavior must be reviewed and disclosed. We cannot certify arbitrary providers' practices. |
| Email/name | The app does not collect a user profile/email. Voluntary support email is outside the app's automatic flows; the policy covers it separately. Reassess if an in-app contact form or support SDK is added. |
| Photos/video/audio/files | No user media upload. External media is streamed to the TV. Subtitle URI/name stays local; no broad media-library access. |
| Crash logs/diagnostics | No automatic developer upload. Local safe error tags and Android/system logs are not developer-side collection. Do not add crash reporting later without revisiting this form. |
| Sharing | Provider requests follow a specific user-initiated Connect/Play/Search action and can qualify for Google's user-initiated sharing exception. Document why the user reasonably expects the transmission. This exception concerns **sharing**, and does not automatically remove **collection**. If the exception does not apply to a transfer, disclose sharing too. |
| Ephemeral processing | Do not mark provider identifiers/searches ephemeral without evidence of server retention. The publisher cannot establish arbitrary provider log retention. In-memory app cookies alone do not prove the provider retains nothing. |
| All data encrypted in transit? | **No** while HTTP compatibility remains enabled. Do not claim Yes based on encrypted local storage or the privacy website's HTTPS. |
| Data deletion | Local history/profile/all-data deletion is available. The developer holds no remote app account/data to erase. Do not promise deletion of a provider's server records or tick a remote collected-data deletion assertion without a verified mechanism. Users must contact the provider for provider-account deletion. |
| App account creation | STBPlay does not create a publisher-operated app account. Login to an existing external provider is not creation of an STBPlay account. Do not invent an account-deletion endpoint. |
| Independent security review | No certification obtained. Do not select a review/certification badge. |

Passwords/authentication secrets still count as sensitive data handling even if the form has no separate "password" checkbox. Describe them clearly in the privacy policy. No collected data is used by STBPlay for advertising or developer analytics.

## Unresolved HTTP policy issue

Google's User Data policy requires secure transmission of personal/sensitive data, including authentication information. The current compatibility build accepts authenticated HTTP because the owner explicitly requested it. Answering "No" to encryption is accurate but does not itself satisfy the secure-transmission requirement. A privacy notice or consent does not encrypt the connection. Do not describe this artifact as guaranteed Play-compliant. Before production submission, choose a secure transport design and verify every authentication, catalog, stream, HLS segment, subtitle and redirect endpoint involved. On 17 October 2026, the owner chose to retain HTTP in the release and document the risk. No separate HTTPS-only variant is supplied. This decision preserves compatibility but does not resolve Google's secure-transmission requirement.

## Review before submitting

Inspect actual traffic on a lawful test source, obtain any relevant provider retention/privacy information, and reconcile this guide with Google's live form. Do not import a made-up Data safety CSV: question IDs and required choices can change. Use the current Console form and save a copy of the final answers privately.

Sources: [Data safety definitions, exceptions and form](https://support.google.com/googleplay/android-developer/answer/10787469), [User Data and secure-transmission policy](https://support.google.com/googleplay/android-developer/answer/10144311).
