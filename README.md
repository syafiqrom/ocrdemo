# DO Scanner (Android App)

An Android app that reads the **DO number** from a picking list using the phone camera or gallery images, then sends it to a Chrome extension through Firebase.

<img width="250" height="490" alt="image" src="https://github.com/user-attachments/assets/747a9345-2bb3-4acd-a6dd-cfd4d3dce3b0" />


## Features

- Live camera scanning with no button to press
- Pick one or many images from the gallery
- Finds the number printed under the **Number/Date** label
- Accepts only 9-digit numbers, and ignores duplicates
- Camera mode needs the same number in 2 consecutive frames, which avoids misreads
- Short vibration when a new number is added
- Sends each new number to Firebase Realtime Database
- Each phone generates its own secret code, so users never see each other's data
- Text recognition runs on the phone (ML Kit), with no per-scan cost

## How it works

```
Phone camera -> ML Kit OCR -> validate (9 digits, unique) -> Firebase: lists/<code>/pending
```

The [DO Scanner Filler](../do-filler) Chrome extension reads `pending`, fills the DO page, and moves each number to `done` or `failed`.

## Tech stack

- Kotlin, Android Views, Material 3
- Google ML Kit Text Recognition (bundled model)
- CameraX
- Firebase Realtime Database

## Requirements

- Android Studio (latest stable)
- Android 7.0+ (API 24)
- A Google account for Firebase
- A physical phone is recommended for camera and vibration testing

## Setup

### 1. Firebase

1. Create a project at [console.firebase.google.com](https://console.firebase.google.com).
2. Create a **Realtime Database** (choose the region closest to you, for example Singapore).
3. Set the rules and publish:

   ```json
   {
     "rules": {
       "lists": {
         "$code": {
           ".read": true,
           ".write": true
         }
       }
     }
   }
   ```

4. Add an Android app with your package name (`com.example.ocrdemo`).
5. Download `google-services.json` and place it in the `app/` folder.

> `google-services.json` is in `.gitignore`. Do not commit it.

### 2. Project config

1. In `MainActivity.kt`, set the database URL in `dbRef` to your own:

   ```kotlin
   FirebaseDatabase.getInstance("https://<your-project>-default-rtdb.<region>.firebasedatabase.app")
   ```

2. Sync Gradle (**File -> Sync Project with Gradle Files**).

### 3. Run

Connect a phone with USB debugging on, then click **Run**.

## Usage

1. Open the app and tap **Code** to see your secret code. Tap **Copy**.
2. Paste the code into the Chrome extension on first run.
3. Tap **Camera** and point it at the **Number/Date** area of a picking list. Hold steady for about a second.
4. The phone vibrates when the number is added. Numbers appear in the list and are sent to Firebase.
5. Or tap **Pick image** to scan one or more saved photos.

## Data layout in Firebase

```
lists/
  <secret code>/
    pending/   numbers scanned by the phone, waiting for the extension
    done/      numbers the extension has processed
    failed/    numbers that could not be matched
```

## Permissions

| Permission | Why |
|---|---|
| `CAMERA` | Live scanning |
| `VIBRATE` | Feedback when a number is added |
| `INTERNET` | Sending numbers to Firebase |

## Project structure

```
app/src/main/
  java/com/example/ocrdemo/MainActivity.kt   camera, OCR, validation, Firebase
  res/layout/activity_main.xml               screen layout
  AndroidManifest.xml
```

## Customizing

- **Different label:** change `"Number/Date"` in `findDOText()`.
- **Different digit count:** change `n.length == 9` in `addNumbers()` and `confirmAndAdd()`.
- **Scan speed:** change `250` (ms) in `analyzeFrame()`.
- **Vibration:** edit `vibrateShort()`.

## Limitations

- The secret code is the only protection for your data, so treat it like a password. For wider use, add Firebase Authentication and stricter rules.
- The duplicate check lives in memory, so it resets when the app restarts.
- Reinstalling the app or clearing its data creates a new secret code.

## Security notes

Never commit `google-services.json`, `local.properties`, or keystore files. Never post your secret code or screenshots that show it.
