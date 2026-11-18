# LumiDrop X

LumiDrop X is an Android application for sharing files and text between nearby devices over a local network. The project includes native Android-to-Android transfer support, an embedded web client, device discovery, resumable transfer handling, media/file pickers, and received-file management. The source and assets in this repository are preserved from the supplied LumiDrop X project.

## Screenshots

![LumiDrop X overview](screenshots/overview/hero.png)

| Connected devices | Transfers | Empty state |
|---|---|---|
| ![Connected device view](screenshots/overview/connected-device-top.png) | ![Transfer view](screenshots/overview/transfer-device-bottom.png) | ![No-device view](screenshots/overview/no-device.png) |

## Project information

- Application ID: `com.lumidrop.x`
- Version name: `1.0.0`
- Version code: `89`
- Minimum Android version: API 21 (Android 5.0)
- Target and compile SDK: API 34
- Module: `app`
- Release build: R8/minified, signed locally with the private release key

## Build locally

Install JDK 17, Android SDK Platform 34, Android SDK Build Tools 34.0.0, and accept the Android SDK licenses. Copy `keystore.properties.example` to `keystore.properties`, then point it at your privately backed-up release keystore. The keystore and `keystore.properties` are intentionally ignored by Git.

```bash
./gradlew test
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew bundleRelease
```

The release signing configuration accepts either `keystore.properties` values or the `LUMIDROPX_STORE_FILE`, `LUMIDROPX_STORE_PASSWORD`, `LUMIDROPX_KEY_ALIAS`, and `LUMIDROPX_KEY_PASSWORD` environment variables. Never place real credentials in tracked files or CI configuration committed to the repository.

## Repository and release notes

See [CHANGELOG.md](CHANGELOG.md), [CONTRIBUTING.md](CONTRIBUTING.md), [SECURITY.md](SECURITY.md), and [GITHUB-PUBLISHING-HANDOFF.md](GITHUB-PUBLISHING-HANDOFF.md). This repository does not include a `LICENSE` file because no license was supplied with the source package.
