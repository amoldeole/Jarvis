# Phone Guardian (Jarvis)

A privacy-first Android storage manager for the `arena/01a03e9a-jarvis` branch. The project is a real Android Studio application, not a UI-only mock. It uses Android-standard storage authorities, a local Room index, SAF for user-approved folders, a verify-before-delete operation engine, offline duplicate detection, a checksum-verified local backup provider, and an optional authenticated LAN browser.

## What is implemented

### Foundation

- Kotlin + Jetpack Compose Material 3 application (`minSdk 26`, `targetSdk 35`).
- Bottom navigation: Home, Files, Clean, Backup, Assistant.
- Room schema for indexed files, protected folders, operations, Trash, backup manifests, and scan runs.
- First-run explanation and progressive permission flow: media permission first, SAF folder access only when requested, contacts and microphone only when their features are used.
- MediaStore fast scan plus recursive `DocumentFile` scan for user-selected folders.
- Batched IO work, cancellation-safe scan runs, and WorkManager maintenance jobs.
- Files are indexed as content URIs; the app does not depend on manufacturer-specific filesystem paths.

### Safety and intelligence

- Classifies by MIME, extension, folder signal, and common magic bytes. A renamed JPEG, PDF, ZIP, MP4, or audio file can still be recognized.
- Conservative `FolderSafety` policy protects unfamiliar existing folders by default. Known system/source folders such as `DCIM/Camera`, `Download`, `Screenshots`, WhatsApp, and Telegram are not flattened. Explicitly protected folders are persisted in Room.
- Organization is a recommendation preview only. It shows Before → After, counts, size, and a reason. A user must choose a writable SAF destination and approve before any move.
- Move/copy engine writes a pending operation record first, avoids collisions (`file (1).ext`), copies and verifies byte counts before removing a source, and records failures.
- Exact duplicate scan uses SHA-256 and runs as a deep, user-triggered phase. Metadata groups are available as a separate, non-destructive mode. No duplicate is deleted automatically.
- App-managed Trash uses a 30-day default retention; permanent deletion requires a second confirmation. Expiry physically removes only the app's Trash copy, then removes its database record. Trash items can be restored into a user-selected SAF folder after review.
- Large-file search, old-file search, operation history, and file-level cleanup review are available.

### Backup and browser access

- Offline SAF backup provider writes `Phone Guardian/<device>/...`, preserves a JSON manifest, skips unchanged files by checksum/size/modified time, and verifies each copied file.
- Contacts can be exported to a standard VCF file after a separate contacts permission request.
- Backup provider boundary includes an intentionally disabled cloud provider. No Google credentials, cloud SDK, or user file bytes are sent by default.
- Optional local browser access is a foreground service started only by the user. It creates a random Keystore-protected token, accepts private-LAN peers only, and supports paired read/download access plus bounded PUT upload to the app-private inbox. Switching it off revokes the token. It does not open a router port.
- The application explicitly calls this **Maximum Accessible Phone Backup**. Android private app data, a bit-for-bit OS clone, and raw flash recovery are not promised because a normal third-party app cannot access them.

### Jarvis assistant

- Explicit finite command grammar (`IntentParser`) for search, organize preview, duplicate scan, large/old files, backup navigation, app launch, volume/media commands, and optional accessibility Back/Home/Scroll/Click.
- Destructive voice commands become a confirmation state and never execute arbitrary natural-language actions.
- Speech uses Android's recognizer only after microphone permission. Phone Guardian does not store recordings.
- Accessibility service is optional, visible in Android Settings, and only performs visible global/navigation actions. File organization and backup do not require it.
- A Storage home-screen widget is included.

## Android capability boundaries

- Shared storage is accessed via `MediaStore` and user-granted Storage Access Framework trees. The app intentionally does **not** request `MANAGE_EXTERNAL_STORAGE`; Play policy and user privacy make all-files access inappropriate for the core product.
- On Android 13+, photos/video/audio permissions are separate. Media access can be limited by the user. If permission is denied, scanning continues only for locations the user explicitly grants through SAF.
- A document provider may refuse a move/delete or require a renewed grant. The original is kept and the failure is written to the operation log.
- Screen mirroring/WebRTC, Google Drive, cloud AI, full contact restore, raw deleted-storage recovery, and root mode are provider/capability boundaries rather than fake buttons. They can be added behind explicit interfaces without weakening the offline core.
- Manufacturer battery restrictions are not bypassed. Scheduled WorkManager work is best-effort and is visible in the app's status/notification surfaces.

## Build

Requirements:

- Android Studio Ladybug or newer
- JDK 17
- Android SDK Platform 35 and build tools installed
- Gradle 8.7+ (the checked-in `gradlew` bootstraps the pinned distribution when needed)

From the repository root:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Install the debug build on a connected device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug application id is `com.jarvis.phoneguardian.debug`. Test the first-run permission flow on Android 13+ and Android 10–12; use an actual OnePlus Nord as a compatibility target rather than relying on an emulator's storage provider.

### GitLab CI APK artifacts

`.gitlab-ci.yml` runs unit tests and produces a downloadable `app-debug.apk` artifact in the `build_debug_apk` job. Push this repository to GitLab, enable a Docker runner, then open **Build → Pipelines**. After the pipeline succeeds, open `build_debug_apk` and download **Job artifacts**. The pipeline also publishes `app-debug.apk.sha256` for integrity verification.

For signed release APK/AAB output, add these as **masked + protected** GitLab CI/CD variables: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`. Create a Git tag; the `build_release` job appears as a manual job and emits both `app-release.apk` and `app-release.aab`. The runner deletes the decoded keystore in `after_script`; never put signing material in the repository.

This sandbox checkout does not include an Android SDK/JDK, so an APK cannot be emitted here. The project is configured for a reproducible Android Studio/GitLab CI build; the first build downloads pinned dependencies from Google Maven/Maven Central.

## Release signing

No signing credentials are stored in the repository. Copy `keystore.properties.example` to `keystore.properties` or inject equivalent secrets in CI, wire it to the release signing config in the deployment pipeline, then produce:

```bash
./gradlew :app:bundleRelease
./gradlew :app:assembleRelease
```

Use a Play App Signing upload key, keep `keystore.properties` and the keystore out of Git, and verify the release with `apksigner`. R8 is enabled for release and the repository includes conservative Room/Gson keep rules.

## Test strategy

The included unit tests cover signature-first classification, stable category destinations, dangerous-command confirmation, size parsing, and search intent classification. For a release gate, add instrumented tests with a fake `ContentProvider`/SAF provider for:

- denied/revoked permissions and partial media grants;
- interrupted scans and WorkManager retries;
- copy/verify/delete failure injection;
- duplicate hash groups and collision naming;
- Trash expiry/restore;
- backup interruption, checksum mismatch, and destination-full conditions;
- LAN token revocation, non-private-peer rejection, and path/HTML escaping;
- Android 10, 12, 13, 14, and 15 behavior on Samsung, OnePlus, Pixel, Xiaomi, Motorola, Realme/Oppo, and Huawei/Honor where available.

The safest behavior is the default: scan and recommend first, preserve unfamiliar folders, ask for a folder grant at the point of use, never overwrite, never upload silently, and never claim a capability Android has not granted.
