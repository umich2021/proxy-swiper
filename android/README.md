# Proxy Swiper Android app

## Build and install

Turn on USB debugging on the phone (Settings → About phone → tap Build number 7 times, then Developer options → USB debugging) and plug it in.

- **Android Studio:** open this `android/` folder, wait for the Gradle sync, press Run
- **Command line:** with `ANDROID_HOME` set to your Android SDK and a JDK on your PATH (Android Studio's works):
  ```sh
  ./gradlew installDebug
  ```

Needs Android 11 or newer on the phone.

## First launch

The app opens a setup guide that walks you through each step. You can replay it from Settings → App.

1. Android 13+ only: Settings → Apps → Proxy Swiper → ⋮ → Allow restricted settings. The menu item only shows up after you've tried to turn on accessibility once
2. Accessibility → Proxy Swiper → Use Proxy Swiper → Allow
3. Paste your proxy link and `DEVICE_TOKEN` ([../proxy/README.md](../proxy/README.md))

Optional, so accessibility comes back on by itself after app updates:

```sh
adb shell pm grant com.autodate.app android.permission.WRITE_SECURE_SETTINGS
```

## When Hinge changes its layout

The app finds Hinge's buttons with the rules in `app/src/main/assets/selectors.json`. When Hinge updates and a button stops matching, the session stops with "can't find the skip button" or "unknown screen". To fix it:

1. Switch to Record, open the screen that broke, and tap the REC bubble
2. Settings → Data → Export data to Downloads, then copy the zip to your computer
3. `python3 ../tools/summarize_dumps.py autodate_*.zip` shows what Hinge exposes on that screen and which rules matched
4. Update `selectors.json`

The export has other people's profiles in it. Don't commit it or attach it to an issue.
