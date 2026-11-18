# GitHub Publishing Handoff

This handoff is for the separate agent that will publish LumiDrop X. Do not publish anything automatically from the preparation environment.

## 1. Public source repository

Use `LumiDrop-X-GitHub-Source.zip`. Extract it, inspect the root, initialize the intended GitHub repository, and commit the tracked source, Gradle wrapper, documentation, screenshots, and `.github` configuration. Use the repository's default branch, normally `main`, unless the owner specifies another branch.

Do not commit `keystore.properties`, any `.jks`/`.keystore`/`.p12` file, build directories, `.gradle`, IDE files, or local machine configuration. The included `.gitignore` is the source of truth for these exclusions. No `LICENSE` file is included because no license was supplied or approved.

The README already presents the supplied screenshots from `screenshots/overview/` and identifies the actual application ID, version, SDK compatibility, and local build commands.

## 2. First GitHub Release

Use the release package `LumiDrop-X-Release-Package.zip`. Suggested title: **LumiDrop X v1.0.0**. Suggested tag: `v1.0.0`.

Upload these assets from the package:

- `APKs/LumiDrop-X-v1.0.0-universal.apk`
- `AAB/LumiDrop-X-v1.0.0.aab` (available for Play distribution)
- `Checksums/SHA256SUMS.txt`
- `RELEASE_NOTES.md`

The release notes state the actual package ID, version, minimum SDK, target SDK, and that the APK is signed. Do not claim device testing beyond the verification report.

## 3. Private signing security

The private signing package is `LumiDrop-X-Private-Signing-Credentials.zip`. It must never be uploaded to the public repository or GitHub release. It contains the release JKS and the credentials needed for future local release builds.

Keep the keystore and credentials in an encrypted backup under the owner's control. Future updates must use this same signing identity or Android update compatibility will be affected. Never paste passwords into README files, release notes, workflow files, commits, or logs.

## 4. Future builds

Clone the public source, install JDK 17 and Android SDK Platform/Build Tools 34, copy the private signing materials to a secure local location, and create an untracked `keystore.properties` from `keystore.properties.example`. Then run `./gradlew test`, `./gradlew assembleRelease`, and optionally `./gradlew bundleRelease`. Verify the resulting package ID, version code/name, signature fingerprint, and SHA-256 before publishing.

The included GitHub Actions workflow intentionally verifies unit tests and an unsigned debug build only. It does not access or publish private release credentials.

## 5. Final publication checklist

- [ ] Public source ZIP extracts successfully.
- [ ] No private keystore or password appears in the public source or release package.
- [ ] README screenshots render from tracked paths.
- [ ] Release tag matches version `1.0.0` / version code `89`.
- [ ] Universal APK installs on the target test device(s).
- [ ] APK signature fingerprint matches the owner's private credentials.
- [ ] SHA-256 checksum matches the uploaded APK and AAB.
- [ ] Device/runtime testing results are added separately if performed.
