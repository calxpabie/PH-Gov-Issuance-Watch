# PH-Gov Issuance Watch

A GitHub Pages web app and Android WebView app for tracking publicly available Philippine government issuances.

## Existing monitoring

- `monitor.py` checks configured official sources once a day through GitHub Actions.
- `circulars.json` and `daily-report.md` are refreshed by the monitoring workflow.
- The GitHub Pages website remains available at the repository's Pages URL.
- The Android APK bundles a snapshot of the web assets at build time; rebuild the APK to refresh that snapshot.

## Android release build

The Android project uses Firebase Cloud Messaging (FCM) for push notifications. The Android app subscribes to the `issuance-updates` topic. The daily monitor sends a topic notification only when `monitor.py` creates `new-items.md`.

The release workflow expects these GitHub Actions repository secrets:

- `SIGNING_KEY_STORE_BASE64` — Base64-encoded Android release keystore.
- `SIGNING_KEY_ALIAS` — key alias in that keystore.
- `SIGNING_STORE_PASSWORD` — keystore password.
- `SIGNING_KEY_PASSWORD` — key password.

The daily monitoring workflow can send push notifications when this additional secret is configured:

- `FIREBASE_SERVICE_ACCOUNT_JSON` — the complete JSON contents of a Google Cloud service-account key authorized to send Firebase Cloud Messaging messages for project `ph-gov-issuance-watch`.

Never commit the `.jks` signing keystore, service-account JSON, private keys, or passwords. Keep an offline backup of the release keystore and passwords; future APK updates must use the same signing key.

### Configure Firebase server sending

1. In Google Cloud Console, select project `ph-gov-issuance-watch` and enable the Firebase Cloud Messaging API.
2. Create a dedicated service account for GitHub Actions and grant only the Firebase Cloud Messaging send permission/role required by your project (typically **Firebase Cloud Messaging API Admin**).
3. Create a JSON key for that service account and store the entire JSON text as the GitHub Actions secret `FIREBASE_SERVICE_ACCOUNT_JSON`.
4. Do not put this service-account key in the Android app, `google-services.json`, or repository files.
5. Run the daily monitoring workflow manually after saving the secret. A push notification is sent only if new items are detected.

The `google-services.json` file in `app/` is the Android client configuration. It is not a server credential and cannot send notifications by itself.

### Build

The GitHub Actions workflow `Build Signed Android APK` creates the signed release artifact `ph-gov-issuance-watch-signed-release`.

For local builds, use JDK 17 and Android SDK 35:

```bash
gradle :app:assembleRelease
```

## Privacy and permissions

The Android manifest requests internet access and notification permission only. The app does not request contacts, SMS, location, microphone, camera, or broad file access. Firebase receives the app's push-registration information to deliver notifications. The app's notification content is limited to the count of new items and a prompt to open the report.

## Important limitations

- Push notifications require a working internet connection, Google Play services, user-granted notification permission on Android 13+, and the GitHub secret described above.
- Android app content is bundled at build time; the website may have newer circular data until a new APK is built.
- A clean third-party antivirus scan does not guarantee Google Play Protect approval. Investigate and appeal any Play Protect warning rather than disabling device protection.
