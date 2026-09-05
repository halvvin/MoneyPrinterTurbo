# BUILD ANDROID

## Requirements
- JDK 17 (Temurin)
- Android SDK 35 + Build Tools (standard AGP 8.5 setup)
- No NDK needed to build the app — the static FFmpeg binary is prebuilt in CI (job `ffmpeg`) and passed between jobs.
- Android 10+ (API 29) arm64-v8a device/emulator for installation.

## Build with Gradle

```bash
cd android
./gradlew testDebugUnitTest      # JVM unit tests
./gradlew assembleDebug          # debug APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # release APK (signed with debug keystore in CI so it installs)
```

## GitHub Actions (recommended)

Push to the `android-native` branch — `.github/workflows/android.yml` runs:
1. **ffmpeg job** — cross-compiles static FFmpeg + libx264 + libass + freetype + fribidi + harfbuzz for arm64-v8a (NDK r27c), verifies ELF/PIE, uploads artifact `ffmpeg-arm64`.
2. **build job** — downloads the binary into `jniLibs/arm64-v8a/`, runs unit tests, builds debug+release APKs, verifies `libffmpeg_exec.so` inside the APK, prints SHA-256, uploads artifacts.

Artifacts: `mpt-android-debug-apk`, `mpt-android-release-apk`, `ffmpeg-arm64`.

## Rebuilding FFmpeg yourself

```bash
sudo apt install meson ninja-build nasm
./android/scripts/build_ffmpeg_android.sh android/app/src/main/jniLibs/arm64-v8a
```
Requires `ANDROID_NDK_HOME` (or it downloads NDK r27c automatically). ~15–25 min on 4 cores.

## Install on device

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Minimum Android 10 (exec from nativeLibraryDir + W^X rules). arm64-v8a only.

## APK size

~30 MB: app code + Compose + bundled assets (fonts, 10 preset songs ≈ 15 MB, azure voices catalog) + static FFmpeg (~20 MB compressed).

## Troubleshooting builds
- `ffmpeg binary is missing` at runtime → the jniLibs binary was absent at build time; run the ffmpeg job first (CI does this automatically).
- Gradle 8.9+ required (AGP 8.5.2).
