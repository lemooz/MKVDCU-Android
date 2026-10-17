# MKVDCU Android

An early Android ARM64 port of **Mortal Kombat vs. DC Universe**, built on the open source [ReXGlue](https://github.com/rexglue/rexglue-sdk) static recompilation project. This is an unofficial community project and is not affiliated with Warner Bros. Games, NetherRealm Studios, or Microsoft.

> **Experimental alpha:** the first APK is playable, but compatibility, graphics, performance, and custom-driver support are still being developed. Back up your save data before installing updates.

## Current prerelease

Download **[MKVDCU Android v0.1.17](https://github.com/lemooz/MKVDCU-Android/releases/tag/v0.1.17)** from Releases. This experimental prerelease includes UE3 scene-buffer scaling (0.5×/0.75×), graphics presets, presentation frame skip and a Mali performance preset targeting 30 FPS output. The presentation cap does not wait in guest `XE_SWAP` or change the guest clock, so it does not itself slow game simulation; it also cannot guarantee that the device renders every scene at 30 FPS. Scene scaling, frame skip and performance remain experimental, with no new physical validation in this version. The APK is ARM64 (`arm64-v8a`) and requires Android 9 or later with Vulkan 1.1 support. It does not include game data; provide your own legally obtained copy.

### Tested devices

| SoC | Reported result |
| --- | --- |
| Qualcomm Snapdragon 8 Elite | Playable |
| Samsung Exynos 1380 | Reaches in-game |

These are the developer's current test devices. Results can vary with phone model, Android version, thermal state, and Vulkan driver.

## Graphics drivers

The app can import Vulkan driver packages and includes **partial, experimental support for custom Adreno and Mali drivers**. Driver packages are not bundled. Import a package from **Settings → Vulkan Drivers**, run **Verify**, and select it only if the app approves it. Keep the system driver selected if verification fails. PanVK compatibility is incomplete and some features required by the game are not available on every PanVK device.

## Install and play

1. Download the normal APK from the [current prerelease](https://github.com/lemooz/MKVDCU-Android/releases/tag/v0.1.17). The diagnostic APK is for investigation and can affect performance.
2. Install it on an Android 9+ ARM64 device with Vulkan 1.1.
3. In the app, select your own game image when prompted. The game image is not part of this repository or release.
4. Start with the system Vulkan driver. Try imported drivers only after the app verifies them.

For a bug report, include the phone model, SoC, Android version, selected Vulkan driver, graphics settings, and a description of what happened. Do not attach game images, decrypted game files, or personal data.

## Development

- `android/` — Android app, Gradle wrapper, Java/Kotlin-facing UI, JNI and Vulkan driver management.
- `targets/mkvsdcu/private/rexglue-host/` — game host and Android native target.
- `patches/rexglue-sdk/` — MKVDCU changes against the pinned ReXGlue SDK submodule.
- `scripts/android/` — APK packaging, provenance checks, driver checks, and graphics investigation utilities.
- `docs/ANDROID_RELEASES.md` — release numbering, APK handling, signing, and publication steps.

Builds require Android SDK/NDK and your own legally obtained game files. See [Android release and build notes](docs/ANDROID_RELEASES.md). The desktop port from which this Android work derives is documented in the repository history and its original [KawaiiBunga/MKVDCU-Recomp](https://github.com/KawaiiBunga/MKVDCU-Recomp) project.

## References

Related open source projects used as reading and compatibility references include [DMC-RECOMP](https://github.com/WINDROID-EMU/DMC-RECOMP), [NFSMW-RECOMP](https://github.com/WINDROID-EMU/NFSMW-RECOMP), [UnleashedRecomp-Android](https://github.com/SansNope/UnleashedRecomp-Android), and [Xenia Canary](https://github.com/xenia-canary/xenia-canary). Their code and project-specific patches are not copied into this repository unless explicitly attributed and licensed.

## License

This project follows the GNU GPL v3 license in [`COPYING`](COPYING), with third-party notices in [`android/app/src/main/assets/licenses/`](android/app/src/main/assets/licenses/). Game data remains the user's responsibility and is not distributed here.
