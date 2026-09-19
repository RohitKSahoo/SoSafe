# Implementation Plan: WebRTC Live Camera Video Transmission & Free Storage

## Goal
Implement zero-cost live P2P camera video streaming over WebRTC during active SOS alerts, with background video chunk recording uploaded to Cloudinary free storage.

## Architectural Principles (Ponytail Mode)
- **Minimal Code**: Extend existing `WebRTCManager.kt` using native WebRTC `Camera2Capturer` and `VideoTrack` abstractions without adding heavy external dependencies.
- **Zero Cost**: Live video runs via WebRTC P2P (Google public STUN); chunk recording uploads to existing free Cloudinary setup.
- **Fail-safe**: Video track failure must never interrupt continuous live WebRTC audio or location tracking.

---

## Proposed Changes & Affected Files

### Component 1: WebRTC Video Capturer & Video Track
#### [MODIFY] [WebRTCManager.kt](file:///d:/Projects/SoSafe/app/src/main/java/com/rohit/sosafe/utils/WebRTCManager.kt)
- Add `VideoCapturer` (`Camera2Capturer`), `VideoSource`, and `VideoTrack` creation in `initPeerConnection()`.
- Add local and remote `SurfaceViewRenderer` attachment callbacks.
- Add `switchCamera()` method for toggling between Front and Rear cameras.

### Component 2: Compose Video Viewport & UI Integration
#### [MODIFY] [DashboardState.kt](file:///d:/Projects/SoSafe/app/src/main/java/com/rohit/sosafe/ui/DashboardState.kt)
- Add `isVideoEnabled: Boolean`, `isFrontCamera: Boolean`, and `remoteVideoTrack: VideoTrack?` fields to state.

#### [MODIFY] [MonitoringScreen.kt](file:///d:/Projects/SoSafe/app/src/main/java/com/rohit/sosafe/ui/MonitoringScreen.kt)
- Add Compose `AndroidView` wrapping WebRTC `SurfaceViewRenderer` to display incoming live video feed on Guardian monitoring screen.
- Add "Switch Camera" remote control button to Guardian UI.

### Component 3: Background Video Chunk Recorder
#### [MODIFY] [CloudinaryUploader.kt](file:///d:/Projects/SoSafe/app/src/main/java/com/rohit/sosafe/utils/CloudinaryUploader.kt)
- Add `uploadVideo(file: File, ...)` method to handle MP4 video uploads to Cloudinary `/video/upload` endpoint.

---

## Tasks

- [x] **Task 1: Add Video Track and Camera2Capturer to WebRTCManager**
  - Extend `WebRTCManager.kt` to initialize `Camera2Capturer` and attach `VideoTrack` to `PeerConnection`.
  - *Verify*: Compile with `./gradlew assembleDebug`.

- [x] **Task 2: Implement Switch Camera Support in WebRTCManager**
  - Implement `switchCamera()` on `Camera2Capturer` in `WebRTCManager.kt`.
  - *Verify*: Test camera toggle method compilation.

- [x] **Task 3: Add WebRTC SurfaceViewRenderer Compose Wrapper to MonitoringScreen**
  - Render incoming live WebRTC video feed using `AndroidView(factory = { SurfaceViewRenderer(context)... })` in `MonitoringScreen.kt`.
  - *Verify*: `./gradlew assembleDebug` builds without errors.

- [x] **Task 4: Add Video Upload Support to CloudinaryUploader**
  - Add `uploadVideo()` in `CloudinaryUploader.kt` pointing to `https://api.cloudinary.com/v1_1/$CLOUD_NAME/video/upload`.
  - *Verify*: `./gradlew assembleDebug` builds successfully.

- [x] **Task 5: Update Phase Tracker & Verify Build**
  - Mark Phase 6 tasks as completed in `study_docs/phase-6.md` and `study_docs/README.md`.
  - *Verify*: Run `./gradlew assembleDebug`.

---

## Done When
- [x] `./gradlew assembleDebug` succeeds with zero compilation errors.
- [x] Live WebRTC video stream and remote camera switching logic are fully integrated.
- [x] Phase documentation (`study_docs/phase-6.md`) updated per `.agents/rules/phase-tracker.md`.
