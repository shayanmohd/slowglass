# Slowglass

Slowglass is a long exposure camera for Android phones whose stock camera has no light trail or slow shutter mode. The camera stream is stacked on the GPU while you watch, in five modes: light trails, silky water, motion blur, star trails and a lightning trigger that saves each strike from the frames just before the flash. Sessions run from one second to unlimited, keep going with the screen dimmed, and save the stack plus the sharpest single frame to Pictures/Slowglass. A camera check on first launch reports what the phone can do. Paid once, no ads, no account, no network.

Everything runs on the device. The app declares no network permission and sends nothing anywhere.

## Build

Requires JDK 17 and the Android SDK with platform 36.

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew bundleRelease       # needs keystore.properties, see below
```

`keystore.properties` and the `.jks` are not committed. Without them the release build stays
unsigned instead of failing:

```properties
storeFile=<slug>-upload.jks
storePassword=...
keyAlias=<slug>
keyPassword=...
```

## How it works

CameraX Preview renders into a `SurfaceTexture` owned by the app's own EGL context (`gl/StackRenderer.kt`), so frames stay on the GPU as external OES textures. Trails, stars and lightning stack with `GL_MAX` blending; water and motion blur keep a true mean as a three-level tree (blocks of 64, superblocks of 64, a running accumulator) so no half-float blend weight falls below 1/64. RGBA16F framebuffers are used where the GPU renders to them; elsewhere RGBA8 buffers keep a running mean at every level of the same schedule, with a dither that moves every frame so rounding averages out. The camera, the renderer and every session live in `capture/CaptureService.kt`, which becomes a camera foreground service when a session starts. The maths that can be tested on the JVM lives in the `:core` module (no Android imports) and is covered by the tests in `app/src/test`.

## Layout

```
app/src/main/kotlin/com/mohdshayan/slowglass/
  App.kt, MainActivity.kt   process and activity entry points
  capture/                  CaptureService (camera foreground service), CameraController, engine state
  gl/                       EglCore, shaders, StackRenderer
  save/                     PhotoSaver (MediaStore JPEG plus EXIF), MediaDeleter
  di/                       ServiceLocator, the manual dependency container
  data/prefs/               DataStore settings (AppPrefs)
  data/db/                  Room: sessions, strikes, camera checks
  data/repo/                SessionRepository: export and import of the session log
  ui/capture, library, session, check, settings, info   one package per screen
  ui/theme/                 colour, type, shape and motion tokens, AppTheme
  ui/components/            reusable composables
core/src/main/kotlin/.../core/   pure Kotlin: stack, stars, lightning, sharp, timer, exif, orient, check, quirks, export
app/src/test/               JVM unit tests over :core
app/src/debug/              debug-only clip feed (see below); never compiled into release
store/                      Play listing copy, icon, feature graphic, screenshots
docs/                       privacy policy and font licences
```

## Debug clip feed

Debug builds can play a folder of JPEG frames into the renderer's input surface instead of the camera, which is how the store screenshots were made on the emulator:

```bash
adb push frames/ /sdcard/Android/data/com.mohdshayan.slowglass.debug/files/clips/night
adb shell 'echo night > /sdcard/Android/data/com.mohdshayan.slowglass.debug/files/clips/active'
```

Delete the `active` file to return to the camera. The frames are not in this repository.

## Store screenshot footage

Screenshots 01 to 05 show the app stacking recorded public-domain (CC0) footage through the debug clip feed on the API 36 emulator; screenshot 06 is the camera check on the emulator's own virtual camera. Sources, all CC0 on Wikimedia Commons:

- Light trails and motion blur: "Straßenbahnhalt Pariser Tor - Ankunft Variobahn in der Nacht.webm" (screenshot 01 loops its first 60 frames, before the camera in that clip starts to shake)
- Silky water: "Minnehaha Falls, Minneapolis.webm"
- Lightning: "Lightning storm over Colorado, USA.ogg"

## Licences of bundled files

- Michroma, Copyright 2011 The Michroma Project Authors, SIL Open Font License 1.1 (`docs/OFL-Michroma.txt`)
- Hanken Grotesk, Copyright 2021 The Hanken Grotesk Project Authors, SIL Open Font License 1.1 (`docs/OFL-HankenGrotesk.txt`); the three static weights were instanced from the variable font with fontTools
- AndroidX, Jetpack Compose, CameraX, Room and kotlinx libraries under the Apache License 2.0
- `assets/device_quirks.json` is own work and starts with no rules

## Licence

Copyright SocialSure Private Limited.
