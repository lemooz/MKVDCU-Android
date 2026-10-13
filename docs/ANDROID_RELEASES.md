# Android releases

## Versioning

Use `vMAJOR.MINOR.PATCH` tags. While the Android port is experimental, mark GitHub releases as prereleases; use a suffix such as `-alpha.1` only when the APK's `versionName` uses the same suffix. Keep Gradle's numeric `versionName` aligned with the tag and increase `versionCode` for every APK. Promote to a stable release only when the release notes describe repeatable device and gameplay validation.

## Preparing a release

1. Build the real ARM64 native libraries and the Gradle Release APK using `scripts/android/package-real-apk.ps1`.
2. Run `scripts/android/verify-real-apk.py` through the packaging script so the APK's native libraries match the staged, provenance-recorded build.
3. Copy the installable Release APK to `dist/` with a versioned filename. Generate a `.sha256` sidecar with `Get-FileHash -Algorithm SHA256`.
4. Write release notes with tested devices and observed results, known issues, driver limitations, and any required migration steps.
5. Create a GitHub prerelease for the matching `v*` tag and attach the APK and checksum. Do not commit APKs, signing keys, game data, or diagnostic captures to Git.
6. Verify the published asset's size and SHA-256 against the local files.

Publish the normal Release APK. Diagnostic builds collect extra information and can alter performance; distribute one only when a report specifically calls for it and label it clearly.

## Signing and updates

The current release build uses the developer's local Android debug signing key. Keep using the same private key for updates to install over the previous version. Never commit or upload the keystore. If that key is lost or changes, Android may require uninstalling the old build before installing the new one; back up game saves first.

## First public release

- Tag: `v0.1.0`
- Track: prerelease / experimental
- APK: `mkvdcu-android-v0.1.0-alpha.1.apk`
- ABI: `arm64-v8a`
- Device status reported by the developer: playable on Snapdragon 8 Elite; reaches in-game on Exynos 1380.
- Custom Vulkan drivers: partial experimental support for Mali and Adreno.

## Release checklist

- [ ] `versionName` matches the release tag; `versionCode` is greater than the previous release.
- [ ] APK is a non-debuggable Release build and contains no user game files.
- [ ] Native-library provenance verification succeeds.
- [ ] Device results and unresolved issues are written in the release notes.
- [ ] APK and SHA-256 sidecar are attached to the GitHub prerelease.
- [ ] Downloaded release asset hash matches the local hash.
