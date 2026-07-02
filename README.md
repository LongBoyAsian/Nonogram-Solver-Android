# Nonogram Solver Android

An Android application that uses computer vision (MediaProjection) to capture and automatically solve Nonogram (Picross) puzzles directly on your screen.

## Features

- **Screen Capture Integration:** Uses Android's MediaProjection API to seamlessly take screenshots of your active puzzle without requiring root access.
- **Computer Vision Grid Detection:** Automatically identifies the puzzle grid (currently optimized for up to 15x15 grids) and extracts the row/column clues using OCR.
- **Smart Solver Algorithm:** Efficiently solves the Nonogram logic and visually overlays the solution on top of your game.
- **Auto-Fill Gesture Service:** Uses an Android Accessibility Service to physically tap the screen and fill in the solved squares for you automatically!
- **Clue Editor:** A built-in floating editor lets you manually correct any OCR mistakes before running the solver, featuring a quick-entry numeric dialpad.

## How It Works

1. The app runs a background service and displays a floating "Solve" button.
2. When you open your favorite Nonogram puzzle game and press "Solve", the app captures the screen.
3. It detects the thick black/dark gray grid lines to determine the puzzle dimensions.
4. It reads the numeric clues from the edges of the grid.
5. If any clues were read incorrectly, you can fix them in the pop-up Clue Editor.
6. The JS-based solver algorithm processes the clues and overlays green blocks on the screen to show you the solution.
7. Press the **"Fill"** button to have the Accessibility Service automatically tap the correct squares in your game!

## Setup and Installation

*Note: Because this app uses system-level Screen Capture and Accessibility Services to draw over other apps and dispatch gestures, you cannot install it directly from the Play Store. It must be sideloaded.*

1. Build the APK using Android Studio, or download the `app-debug.apk`.
2. Transfer the APK to your Android device and install it.
3. You will likely be prompted by Google Play Protect or Files by Google; you must choose **"Install Anyway"** and toggle **"Allow from this source"**.
4. Once installed, go to your phone's Settings -> Apps -> Nonogram Solver Android.
5. You must manually toggle **"Allow restricted settings"** (usually found in the top-right 3-dot menu on modern Android versions).
6. Grant the "Display over other apps" permission.
7. Go to Accessibility Settings and enable the "Nonogram Solver Android" service so the Auto-Fill feature can dispatch taps.

## Technologies Used

- Kotlin
- Android MediaProjection API
- Android AccessibilityService API (for Auto-Fill gestures)
- Javascript (Rhino/QuickJS solver engine integration)
- OpenCV / custom bitmap pixel analysis