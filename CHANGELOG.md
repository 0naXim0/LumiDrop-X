# Changelog

## 1.0.5 — version code 96

- Published Production Final Pass 24.
- Replaced thumbnail-covering selection badges with a consistent accent ring across image, file, media, and app surfaces.
- Preserved thumbnail identity and selection state across filtering, sorting, virtualization, and rapid multi-selection.
- Added the Pass-24 web-client regression harness covering selection synchronization and thumbnail preservation.
- Maintained the existing signing identity for seamless updates from prior releases.

## 1.0.4 (version code 95)

- Fixed installer handoff by mapping staged update APKs through the internal cache FileProvider root.
- Added robust HTTP 416 recovery, real on-disk resume offsets, bounded retries, and complete-file reuse.
- Persisted discovered updates and ready-to-install state across process restarts.
- Corrected failed/cancelled installation retries so preserved APKs use the install path instead of re-downloading.
- Preserved the official signing identity for updates from v1.0.3 and earlier 1.0.4 builds.


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
