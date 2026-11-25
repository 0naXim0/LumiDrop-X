# Changelog

## 1.0.4 (version code 94)

- Fixed the video/download queue conflict so selected downloads progress one at a time without interfering with playback.
- Added server-side cleanup for never-requested plain download rows and improved retry handling.
- Improved first-open loading through server-start prewarming and cached document listing.
- Added iOS-family music volume handling through a gesture-safe Web Audio fallback.

## 1.0.3 (version code 93)

- Production-final updater reliability pass: deterministic finalization, verification, and installation handoff.
- Added guarded update operations and immediate progress feedback to prevent duplicate actions and stuck downloads.
- Redesigned Settings → About → Updates with a compact purple-themed AUTO CHECK pill and one state-driven primary action.
- Preserved the established signing identity so v1.0.2 users can update in place.

## 1.0.2 (version code 92)

- Hardened the in-app update center with version-code-aware release selection.
- Added APK package, version, signature, checksum, and install-result verification before installation.
- Improved download resume, cancellation, failure handling, and update-state persistence.
- Refined transfer, media, web-client, and native file-sharing reliability.
- Published a signed, zip-aligned universal APK using the established LumiDrop X release key.

## 1.0.1 (version code 90)

- Added **Check for update** to Settings → About.
- Checks the official LumiDrop X GitHub release for a newer version.
- Added a persistent **Download Latest Version** action backed by Android DownloadManager.
- Optimized the portfolio banner for faster loading.

## 1.0.0 (version code 89)

This is the supplied LumiDrop X first-public-release source baseline. The release-preparation pass preserved application code, resources, permissions, identity, version values, and existing build behavior. Repository-only changes add public documentation, organized screenshots, GitHub issue/CI templates, and secure externalized release signing configuration.
