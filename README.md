# My Water Bill Android app

Android client for the Bremac Water Billing System.

- Application ID: `ke.co.bremac.mywaterbill`
- Minimum Android version: Android 12 (API 31)
- Backend API: `https://wbs.bremac.co.ke/api/mobile/`

## Build

Open this project in Android Studio or run `gradlew.bat :app:assembleDebug` on Windows.
The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Release signing

Release signing credentials and keystores are intentionally not stored in this repository.
Copy `release-signing.properties.example` to `release-signing.properties`, replace its
placeholder values, and place the matching upload keystore at the configured `storeFile`
path. The signing properties file and keystore are ignored by Git. Without local signing
credentials, Gradle can still build the release variant, but the APK is not Play-uploadable.

Build a signed release with `gradlew.bat :app:assembleRelease`.
