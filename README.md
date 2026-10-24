# ONE — Capacitor 6 project
npm install
npx cap add android      # generates android/ (once)
npm run native           # copies native/android/* into android/, builds web, cap sync
npm run open             # Android Studio -> Build APK/AAB

Notes
- Usage access (Settings > Permissions) enables real "last played" times.
- Android 13+: PDFs are not a MediaStore media type; grant "All-files access" to list them (Play Store needs a declaration for MANAGE_EXTERNAL_STORAGE; remove it if you don't need PDFs).
- Web preview shows "Will show real apps after APK build"; real data only on device.
