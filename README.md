# AI Pro Camera — Complete v1 project

A self-contained Android/Kotlin project intended to build from GitHub Actions without Android Studio. The project includes the source, XML UI, manifest, Gradle configuration, and CI workflow.

## Included in this version
- CameraX live preview
- Photo capture saved to `Pictures/AIProCamera`
- FHD video recording saved to `Movies/AIProCamera`
- Front/rear camera toggle
- Device-supported zoom-ratio slider
- Device-supported exposure-compensation slider
- Flash toggle for still photos where supported
- Photo, video, portrait-labelled, night-assist, and moon-assist UI modes
- GitHub Actions workflow that builds and uploads a debug APK

## Important feature truth
This is a complete buildable v1 camera application, **not** a recreation of Apple's or Samsung's proprietary camera software. It does not currently contain a trained AI enhancement model, multi-frame night stacking, simulated Studio/Stage Light portrait segmentation, or independent guarantees of all four physical camera modules. CameraX may let the device's logical camera switch physical lenses automatically as zoom changes, but exact lens switching and the maximum optical/digital zoom are controlled by the phone's camera HAL. The MOON mode is an exposure/zoom assist, not a dedicated astrophotography pipeline. Manual ISO and shutter time are not exposed in this version.

These are not claims of completed features; they are explicit limitations so the project does not mislead you. A genuine AI enhancement model and Camera2 manual controls require device capability checks and on-device validation.

## Build from GitHub on a phone
1. Create a new, empty repository called `AI-Pro-Camera`.
2. Download this ZIP and extract it on your phone using your file manager.
3. Upload the **contents** of the extracted folder to the repository root (including the hidden `.github` folder). Do not upload the ZIP itself as a single file.
4. Open the repository's **Actions** tab and run **Build AI Pro Camera APK**, or push the files to `main`.
5. Open the successful workflow run and download `AI-Pro-Camera-debug-apk` under Artifacts.
6. Extract the artifact ZIP and install `app-debug.apk`. Android may ask you to allow installs from your browser/file manager.

## Build details
- Android Gradle Plugin 8.7.3
- Kotlin 2.0.21
- Gradle 8.9 (installed by CI; no local Android Studio or Gradle wrapper needed)
- Java 17, Android SDK 35, minimum Android 8.0 (API 26)
- Debug APK is for testing and is not a Play Store release-signed APK

## Size target
This project bundles no large AI model and should be far below 200 MB. Final APK size is determined by the CI build. Keeping the app below 200 MB does not imply every advanced AI feature is implemented.
