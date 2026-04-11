# 5 Asides Near Me Camera Android MVP

This is a runnable Android Studio project for a first 5 Asides Near Me camera MVP.

## What this build does

- opens the back camera with a live preview
- records rolling 5-second HD segments with CameraX
- keeps only the latest 3 segments in cache
- saves the latest ~15 seconds as a single MP4 highlight using Jetpack Media3 Transformer
- stores clips in the phone gallery under `Movies/FiveAsidesNearMeCamera`
- includes a deep link scheme for future launch from 5 Asides Near Me: `fiveasidesnearmecamera://open`

## Important MVP limits

- this version only works while the app stays in the foreground
- it saves the latest completed 5-second segments, so the most recent partial segment is not included
- it is Android only
- it is not yet linked to Firebase or 5 Asides Near Me match events

## Open in Android Studio

1. Extract the zip.
2. Open the `five_asides_near_me_camera_android` folder in Android Studio.
3. Let Gradle sync.
4. Run on a real Android phone.
5. Grant camera and microphone permissions.
6. Tap **Start buffer**.
7. Wait at least 6 to 10 seconds.
8. Tap **Save last 15s**.

## Future integration into 5 Asides Near Me

Because your main 5 Asides Near Me app is not a native Android camera app, the clean approach is:

- keep this as a separate Android app for now
- add a button in 5 Asides Near Me that launches this app with a deep link
- later pass match metadata into the deep link
- later upload finished clips to Firebase Storage

Example future launch URL from 5 Asides Near Me mobile shell or web wrapper:

```text
fiveasidesnearmecamera://open?matchId=abc123&tag=goal
```

## Main files

- `app/src/main/java/com/fiveasidesnearme/camera/MainActivity.kt`
- `app/src/main/java/com/fiveasidesnearme/camera/HighlightRecorderManager.kt`
- `app/src/main/res/layout/activity_main.xml`

## Notes on the tech choices

CameraX is the recommended starting point for new camera apps on Android, and `PreviewView` is the standard preview widget. Media3 Transformer supports combining multiple video assets into a single exported MP4, which is why it is used here for highlight merging. CameraX support spans Android 5.0+ in general, while this app targets API 26+ for a cleaner MVP baseline. citeturn705803search1turn705803search6turn926268search3turn926268search7

## Next sensible upgrades

- include the most recent partial segment when saving
- add `Save last 15s + next 5s`
- attach tags and scorer metadata
- upload saved clips to Firebase Storage
- add foreground service support for better long-session resilience
