# LumiDrop X — v1.0.7 release notes template

> This is the template for the **GitHub release** the in-app updater reads.
> Publication steps: create release with tag **`v1.0.7`** (not prerelease, not
> draft), upload the release APK as the asset named
> **`LumiDrop-X-v1.0.7-universal.apk`**, and paste the body below with the
> placeholder SHA-256 replaced by the REAL checksum of the exact APK you
> upload. The updater enforces the checksum exactly when it is published in
> this form ("SHA-256: <64 hex>" on its own line, case-insensitive).

---

## Release body (copy from here)

LumiDrop X 1.0.7 — Direct Browser Downloads Restored

The optimized direct-download architecture is back as the primary way
received files are delivered: the browser's own native download mechanism
saves every file straight into its Downloads section, with no
JavaScript-mediated receiving in between.

What changed in 1.0.7:

- **Direct browser-native downloads restored.** Files download directly
  through the browser's own download mechanism into the browser's
  Downloads — the web client coordinates and tracks, but never
  accumulates the file in page memory.
- **iPad 4 (iOS 10.3.3, legacy Safari) reliability.** A download taken over
  by the browser's download manager no longer flips the transfer from
  Active back to Waiting — it stays one live transfer with an honest
  status, and if the browser never completes its takeover the transfer
  fails honestly instead of wedging at 0 KB.
- **Faster large direct downloads.** Large files begin streaming
  immediately (the small-file safety hold now applies only where it
  protects small deliveries), and the server's sockets are tuned for Wi-Fi
  bulk streaming.
- **Quick Accept works everywhere**, including legacy Safari — incoming
  items are accepted and downloaded immediately when enabled.
- Folder ZIP streaming, manual ZIPs, ordinary files, queues, transfer
  history, and every other supported receiving device work exactly as
  before.

SHA-256: <PASTE_THE_64_HEX_CHARS_OF_LumiDrop-X-v1.0.7-universal.apk_HERE>

---

## Publisher checklist (before publishing)

1. **Signing — the blocker to verify first:** the release APK must be signed
   with the SAME certificate as the installed application, or Android will
   refuse it as an update (the in-app updater also refuses cross-signing
   updates before the handoff). The official keystore was NOT included in
   the Pass-27 materials: place it (or its credentials) in the git-ignored
   `signing.local.properties` / `LDX_*` environment variables per
   `app/build.gradle`'s signing block and rebuild `assembleRelease`. Do not
   publish the unsigned artifact as the update.
2. Asset name: `LumiDrop-X-v1.0.7-universal.apk` (the updater's picker).
3. Real SHA-256 of the exact signed APK in the body, on its own line.
   Reference — the SHA-256 of the Pass-27 **unsigned** release artifact is
   `f4adbd05e1b73fd45a410ca9efd21c0f8d4f3ca5fa108d8b6f7267bd377cabe5`;
   **signing changes the bytes, so re-compute it after signing** and publish
   that value.
4. Tag `v1.0.7`, published (not draft, not prerelease).
