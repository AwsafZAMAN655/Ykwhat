# Hand Navigation PRO (Android, built for Honor Pad 8)

Control your tablet with hand gestures seen by the **front camera**. A floating pointer follows your index finger, a pinch taps, and so on.
No Android Studio needed: **GitHub builds the APK for you.**

> **Honest status:** this project was written without being compiled or run on a real device
> (no Android SDK or internet was available when it was generated). It uses real libraries and APIs and was
> reviewed carefully, but the first GitHub build could still show a compile error, and the gesture thresholds
> have never been tried on a real Honor Pad 8. See "If something fails" below. Nothing here claims it was tested.

## What changed in 1.0.1 (code review)

- **Drag/scroll release:** a new touch can no longer cancel a touch that is still being released. If a drag or scroll ends while a gesture is in flight, the release is sent first and any following touch or tap waits for it. Previously a release could be dropped, leaving a touch "stuck down".
- **Camera frames:** RGBA row-stride handling is now explicit (padded rows are copied and cropped correctly), and rotation is applied by the app itself (clockwise by `rotationDegrees`, the same approach as Google's MediaPipe sample) instead of relying on MediaPipe's rotation option. Buffers are reused.
- **Build file:** safer Gradle syntax for keeping the model uncompressed. Version is now 1.0.1.
- Confirmed against Android's API docs: `continueStroke(..., startTime = 0, ...)` is correct, because `startTime` counts from the start of the NEW gesture, and each continuation starts at the end point of the previous stroke.
- Still true: **never compiled or run here.** Only a real GitHub build and a real tablet can confirm that.

## 1. Build the APK (no programming tools needed)

1. Create a free account at https://github.com and sign in.
2. Click **+ (top right) -> New repository**. Name it `HandNavigationPRO`, keep it **Public** or Private, click **Create repository**.
3. Unzip `HandNavigationPRO.zip` on your PC. Open the unzipped folder so you see `app`, `.github`, `build.gradle.kts`, `README.md`...
4. In your new repository click **uploading an existing file**. Select everything INSIDE the folder (including the `.github` folder) and drag it into the browser page. Wait until all files are listed, then click **Commit changes**.
5. Check that the repository now shows `.github/workflows/build.yml`. If `.github` is missing, click **Add file -> Create new file**, type `.github/workflows/build.yml` as the name, paste the contents of that file from the ZIP, and commit.
6. Open the **Actions** tab. If GitHub asks, click "I understand my workflows, enable them".
7. The build starts automatically after the upload. To start it by hand: Actions -> **Build debug APK** -> **Run workflow** -> green button.
8. Wait about 5-10 minutes for a green check mark. Click the finished run, scroll to **Artifacts**, download **HandNavigationPRO-debug-apk**. Unzip it: inside is `app-debug.apk`.

The workflow installs JDK 17 and the Android SDK, uses Gradle 8.7, downloads the official MediaPipe `hand_landmarker.task` model from Google, builds the debug APK and uploads it.

## 2. Install on the Honor Pad 8

1. Copy `app-debug.apk` to the tablet (USB cable, Google Drive, email to yourself...).
2. Open it from the Files app. Allow **Install unknown apps** for that app when asked. If Play Protect warns about an unknown app, choose **Install anyway** (it is your own debug build).
3. Open **Hand Navigation PRO**.

## 3. First-time setup (once)

1. Tap **Grant camera / notification permission** and allow both.
2. Tap **Open Accessibility Settings**. Find **Hand Navigation PRO** (often under *Installed apps* or *Downloaded apps*), switch it **On** and confirm. This is required so the app can draw the pointer and perform taps. It does not read your screen.
   - If the switch is greyed out ("Restricted setting"): Settings -> Apps -> Hand Navigation PRO -> three-dot menu (top right) -> **Allow restricted settings**, then try again.
3. Back in the app, all three permission lines should say `[OK]`.
4. Recommended on Honor/MagicOS: Settings -> Battery -> App launch -> Hand Navigation PRO -> manage manually (allow background activity), so the system does not kill the service.

## 4. Using it

- Tap **Start navigation**. A notification "Hand Navigation PRO is running" appears (camera is on).
- Prop the tablet so the front camera sees your hand at roughly 30-60 cm.
- **Stop** any time: the **Stop navigation** button, the **Stop** button in the notification, or disable the Accessibility Service.
- The app shows tracking status (hand detected, current gesture, fps, ms/frame). There is no camera preview, which keeps it fast and private.

## 5. Gestures

| Gesture | Action |
|---|---|
| Index finger up, move hand | Move the pointer (follows the index fingertip) |
| Thumb tip touches index tip, then release | Tap |
| Pinch, hold, move, release | Drag and drop. Pinch held ~0.5 s without moving = long-press |
| Index + middle fingers up, ring and pinky folded, move hand up/down | Vertical scroll (hand up = content moves up). Use "Invert scroll direction" to flip |
| Closed fist, hold ~0.35 s | Back |
| Open palm, five fingers spread, hold ~0.7 s | Pause / resume tracking |

Pointer colors: teal = moving, orange = pinching, red-orange = dragging, blue = scrolling, gray = paused. Tap shows an expanding ring.

Anti-misfire measures: poses must be seen for several frames, pinch uses on/off hysteresis, fist and palm must be held and then released before they can trigger again (cooldowns of 1.2 s and 1.5 s), clicks land where the pointer was ~160 ms before the pinch started (so the pointer does not slide while fingers close), and short tracking dropouts (<0.25 s) do not break a drag or scroll.

## 6. Settings

- **Gesture sensitivity**: higher = pinch triggers more easily. Lower it if you get accidental clicks, raise it if pinches are missed.
- **Pointer speed**: higher = smaller hand movements cross the screen.
- **Smoothing**: higher = steadier pointer, slightly more lag. Uses a One Euro filter, so fast movements stay responsive.
- **Calibrate pointer center**: start navigation, press the button, then within 3 seconds hold your index finger where your hand rests comfortably. That spot becomes the screen center.
- **Use GPU**: experimental, off by default (CPU is the safe choice). Falls back to CPU if GPU fails. Restart navigation after changing.

## 7. Performance design

CameraX ImageAnalysis with `STRATEGY_KEEP_ONLY_LATEST` (stale frames are dropped, no backlog), 640x480 RGBA frames, one dedicated high-priority analysis thread, MediaPipe in VIDEO mode (tracks between frames, quick recovery when your hand returns), a single reused bitmap, no per-frame allocations in the gesture path, and a dirty-rectangle overlay so only the area around the pointer is redrawn. All work is off the main thread.

## 8. If something fails

**GitHub build fails (red X):** open the failed run, click the **Build debug APK** step, copy the last ~40 lines of the log and send them to me. Compile errors are usually a one-line fix.

**Gestures feel wrong:** the status box shows live numbers (`idx mid ring pinky` = fingertip distance from wrist in palm lengths, `pinch` = thumb-to-index distance in palm lengths). Thresholds are constants at the top of `app/src/main/java/com/handnav/pro/GestureEngine.kt` (`FIST_MAX_R`, `EXT_MARGIN`, `THUMB_OPEN`, `SCROLL_MIN_PINCH`, hold times). Send me the numbers you see for each pose and I can tune them.

**Drag or scroll does nothing:** they use Android "continued" gestures, which need Android 8+ and a working Accessibility Service. Turn the service off and on again in Accessibility settings.

**Pointer moves in the wrong direction after rotating the tablet:** stop and start navigation once.

## 9. Privacy

Camera frames are processed on the device only and never stored or sent. There is no INTERNET permission. Camera processing starts only after you tap Start and always shows a notification.

## 10. Project layout

```
.github/workflows/build.yml          GitHub Actions workflow
settings.gradle.kts, build.gradle.kts, gradle.properties
app/build.gradle.kts                 Kotlin, CameraX 1.3.4, MediaPipe tasks-vision 0.10.14
app/src/main/AndroidManifest.xml
app/src/main/java/com/handnav/pro/
  MainActivity.kt                    settings + status screen
  TrackingService.kt                 foreground camera service
  HandAnalyzer.kt                    CameraX frames -> MediaPipe landmarks
  GestureEngine.kt                   gestures, pointer mapping, cooldowns
  OneEuroFilter.kt                   pointer smoothing
  NavAccessibilityService.kt         taps, drags, scroll, Back, overlay host
  PointerView.kt                     floating pointer drawing
  AppSettings.kt, TrackingState.kt
app/src/main/res/                    layout, strings, theme, accessibility config
```
