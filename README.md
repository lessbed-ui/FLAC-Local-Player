# Music Player (mobile)

The web app lives in `www/index.html`. Capacitor wraps it as a native Android/iOS app.

## Easiest: get an APK with no installs (GitHub)
1. Create a free GitHub repository and upload everything in this folder.
2. Open the repo's **Actions** tab, pick **Build Android APK**, click **Run workflow**.
3. When it finishes (about 5 minutes), download **MusicPlayer-debug-apk** from the run page.
4. Unzip it, copy `app-debug.apk` to your phone, open it, and allow "install unknown apps" when asked.

## On your own computer (Android)
Requires Node 20+, JDK 17, and Android Studio.
```
npm install
npm run libs
npx cap add android
node scripts/setup-android.js   # adds background audio service
npx cap sync android
npx cap open android      # then press Run in Android Studio
```

## iOS
Requires a Mac with Xcode and an Apple ID.
```
npm install
npm run libs
npx cap add ios
npx cap sync ios
npx cap open ios
```

## Changing the app
Edit `www/index.html`, then run `npm run sync` and rebuild.
Change the app ID and name in `capacitor.config.json` before publishing to a store.

## Background audio
`native/` holds a small Android plugin (foreground service + media session) that keeps music playing with the screen off and shows lock-screen controls. `scripts/setup-android.js` installs it into the generated `android/` project during the build.
