# LumiDrop X Build and Verification Report

## Scope

This report records preparation of the supplied LumiDrop X Android project for a public GitHub repository and first release. The application source, resources, manifest permissions, application ID, version identity, and existing behavior were preserved. No emulator or physical-device runtime test was available in this Linux build environment, so this report does not claim device-level startup or transfer testing.

## Original project inspection

- Supplied source archive: `LumiDrop-X Final Source Code version.zip`
- Supplied screenshots archive: `LumiDrop-X-Screenshots for Users.zip`
- Application ID: `com.lumidrop.x`
- Version: `1.0.0` / version code `89`
- minSdk: `21`; targetSdk/compileSdk: `34`
- Android Gradle Plugin: `8.2.2`
- Kotlin Gradle plugin: `1.9.22`
- Gradle wrapper: `8.5`
- Existing release configuration: R8/minified release build

The untouched source completed a baseline `./gradlew --no-daemon assembleRelease` successfully before preparation.

## Preparation changes

- Removed the bundled release keystore and hardcoded signing passwords from the public repository tree.
- Added external signing configuration using ignored `keystore.properties` or environment variables.
- Added `keystore.properties.example` with placeholders only.
- Generated a dedicated private JKS release identity, stored outside the public repository.
- Added README, changelog, contribution and security documentation.
- Added a non-publishing GitHub Actions verification workflow and issue template.
- Organized all seven supplied screenshots under `screenshots/overview/` without altering their pixels.
- Added release/handoff documentation. No license was invented because none was supplied.

## Build environment and commands

- OpenJDK `17.0.21`
- Android SDK Platform `34`
- Android Build Tools `34.0.0`
- Android Platform Tools installed and verified
- Gradle wrapper `8.5`

Commands executed:

```text
./gradlew --no-daemon test
./gradlew --no-daemon assembleRelease bundleRelease
```

## Results

- Unit tests: **BUILD SUCCESSFUL**
- Release APK: **BUILD SUCCESSFUL**
- Release AAB: **BUILD SUCCESSFUL**
- APK signature verification: v1 and v2 verified by `apksigner`
- APK package: `com.lumidrop.x`
- APK version: `1.0.0` / `89`
- APK SDK metadata: min `21`, target `34`, compile `34`
- ABI packaging: one APK with no ABI split suffix; delivered as the universal APK

## Artifacts

| Artifact | Size | SHA-256 |
|---|---:|---|
| `LumiDrop-X-v1.0.0-universal.apk` | 3770181 bytes | `2219ca3e02cc72121a7cf6dcf159616e389f18794d400f8495f74aa61d65ac25` |
| `LumiDrop-X-v1.0.0.aab` | 4025872 bytes | `9d6582ebe2e6ee6bd47b3b39c612ae3105519c095ddea2e5d9e0461201a14403` |

Signing certificate SHA-256 fingerprint:

`5C:56:81:12:E7:5D:29:51:77:63:4A:3A:02:D2:C5:BD:28:81:9E:A4:57:E6:69:C3:ED:D3:08:F8:EB:56:A9:83`

## Limitations and unresolved verification

The APK was not installed or exercised on an Android emulator or physical device here. Consequently, runtime behavior, startup behavior on a particular OEM, network transfer interoperability, permissions UI, and Android 5–14 device coverage remain for device testing. The source's extensive compiler warnings were non-fatal and were not converted into unrelated refactors.
