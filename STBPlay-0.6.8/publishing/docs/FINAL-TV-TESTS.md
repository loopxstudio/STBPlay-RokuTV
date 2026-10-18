# Final testing checklist

Record TV model, Android/Google TV version, source type, network type and the artifact version. Do not record passwords or full token-bearing URLs in a shared report. Test the final optimized APK, then the actual Play internal-test installation.

## Essential flows

- Cold launch with no sources, an existing saved catalog, and a large saved catalog. No indefinite loading; recovery does not silently delete profiles.
- D-pad through every tab/button/field. Focus visible. Fields do not open the keyboard until OK. On the **TV's actual on-screen keyboard**, select multiple letters, numbers, symbols and password characters; Done/Back behaves correctly. Repeat for Sources, all search fields and PIN dialogs.
- Stalker, Xtream and M3U: connect without saving; explicit named Save; update same-name profile; renamed save creates a new profile; refresh/reconnect; edit; confirmed delete; four-profile limit. Switch between different source types and verify source-isolated favorites/history.
- Stalker: browse all relevant folders, then show → season → episode → file. Test the exact formerly failing title and other file/container combinations. No different episode is substituted on error.
- Live TV: play, hide controls, restore with remote, select top-row/volume controls, next/previous, Back to list. Long playback and source/network interruption recover sensibly.
- Movies/episodes: resume versus restart, seeking, pause/play, volume, supported audio tracks, subtitle settings and file-picker return. Test available formats/codecs on each TV. No claim of universal codec or DRM support.
- PIN: off by default; enable/verify; cold launch and returning after Home; incorrect PIN throttling; change/disable requires correct PIN; protected clear-data behavior. PIN is not a content filter.
- Privacy: full policy readable with remote while offline; publisher/support correct; clear history, delete profile, clear all data behave as described. No browser required.
- Home/Back: leaving the player pauses/stops correctly, tab history returns correctly, Back from app Home returns to Android TV Home, no audio keeps playing behind another app.

## Device/release coverage

- At least one real 32-bit/older Android TV and one modern 64-bit Google TV where available; API 24 minimum and target behavior on newer APIs.
- Low-RAM TV with large catalog; observe memory and startup times against Google's current TV quality limits. No low-RAM certification is implied by this checklist.
- 16-KB-page-size device/emulator if the final APK includes native libraries. Check archive/ELF alignment and Play-generated splits too.
- Rotation/configuration/lifecycle changes applicable to TV; disconnect/reconnect Wi-Fi; invalid/expired subscription; HTTPS certificate errors; logout/profile switching.
- Confirm install/update behavior with the development test certificate versus Play's app signing certificate. Back up connection details privately before uninstalling; clearing data loses local history/favorites.

## Report

| Test | Device / OS | Source type | Pass / fail | Safe observations |
| --- | --- | --- | --- | --- |
| Cold launch | | | | |
| Remote keyboard | | | | |
| Formerly failing episode | | | | |
| Live controls return | | | | |
| Profile lifecycle | | | | |
| PIN/privacy/deletion | | | | |
| Play-installed build | | | | |

For a failure, capture the exact visible error and fixed diagnostic stage/code if available. Redact usernames, passwords, MAC identifiers, cookies, tokens and private URLs before sharing logs/screenshots.
