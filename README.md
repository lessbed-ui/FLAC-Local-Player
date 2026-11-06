# Music Player (mobile)

The web app lives in `www/index.html`. Capacitor wraps it as a native Android/iOS app.

## Background audio
`native/` holds a small Android plugin (foreground service + media session) that keeps music playing with the screen off and shows lock-screen controls. `scripts/setup-android.js` installs it into the generated `android/` project during the build.
