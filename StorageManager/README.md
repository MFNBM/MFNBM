# Storage Manager (Android)

An Android app to manage phone storage: see usage, uninstall apps, and deep-clean APKs and other junk.

## Features

- **Overview**: used / free storage.
- **Apps**: list installed apps sorted by size, multi-select uninstall (system confirmation per app), shortcut to each app's info page to clear cache or data. Grant *usage access* for exact size including cache.
- **Cleaner (deep scan)**, with a checkbox list and a delete confirmation:
  - **APK files**: reads each APK (name, version, package) and tells you if it is already installed, newer than installed, or corrupt. Also finds `.apks` / `.xapk` / `.apkm`.
  - **Junk**: `.tmp`, `.log`, `.bak`, `.old`, `.crdownload`, `.part`, thumbnail and trash folders.
  - **Duplicates**: same-content files (MD5), the oldest copy is kept.
  - **Large files**: adjustable threshold (20 MB – 1 GB).
  - **Empty folders**.

## Build

You need an Android SDK, or just use GitHub Actions:

1. Create a GitHub repo and push this folder.
2. Open **Actions → Build APK → Run workflow**.
3. Download the `StorageManager-debug-apk` artifact, unzip it, and install `app-debug.apk` on your phone (allow "install unknown apps").
4. Share the APK with yourself or friends in Discord by uploading the file.

Local build: `gradle assembleDebug` (Gradle 8.9, JDK 17), or open the folder in Android Studio.

## Notes

- Android blocks apps from touching other apps' private data, so cache clearing for other apps goes through the app info page.
- Deletion is permanent. Review the list before confirming.
- Not distributed on Google Play as-is: `MANAGE_EXTERNAL_STORAGE` and `QUERY_ALL_PACKAGES` need Play policy approval.
