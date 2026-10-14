# Android releases

## Building the APK

The release packaging script currently targets the developer's Windows Android toolchain. It uses the Gradle wrapper and checks the packaged native libraries against staged build outputs:

```powershell
git submodule update --init --recursive
.\scripts\android\package-real-apk.ps1 -Variant Release
```

The native CMake build directories must already be configured at `targets/mkvsdcu/private/rexglue-host/out/android-arm64` and `references/rexglue-sdk/out/android-arm64`. The host configuration needs a legally obtained `default.xex` for ReXGlue code generation. Keep that game file, generated game data, and all signing keys out of Git. `-SkipNativeBuild` packages existing native outputs without rebuilding them.

The repository does not yet include a clean-checkout Android CMake bootstrap script. A developer setting up a new machine must configure those two Android ARM64 CMake builds with the Android SDK/NDK before using the packaging command. The current APK was produced in the existing developer build environment.

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
- APK: `mkvdcu-android-v0.1.0.apk`
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

## v0.1.1 prerelease

versionName `0.1.1`, versionCode `2`. Complete customizable Xbox 360 touch controls and new icon; Turnip Auto/Sysmem/GMEM selection with per-mode verification/caches; verification of selected Qualcomm ELF/helpers; periodic native pipeline-cache snapshot/IO in a background worker.

Normal and optional diagnostic APKs use the same package ID/signing key. Native-library provenance and ARM64/Gradle builds passed. No new Snapdragon FPS or physical Turnip validation is claimed. Known graphical/performance problems and experimental PanVK support remain.

## v0.1.2 prerelease

versionName `0.1.2`, versionCode `3`. UI update: redesigned launcher, MK/DC theme and banner, status/selected-driver display, direct control-editor access, on-screen MENU action and in-game session menu with HUD/control/session actions. Retains the native renderer and driver changes from v0.1.1.

Release and Diagnostic APKs packaged from the existing verified ARM64 outputs. This is a UI update, not a new claim of GPU compatibility or FPS improvement. New menu/session actions have not been physically validated in this publication round.
