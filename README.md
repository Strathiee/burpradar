# BurpRadar 🎯🔊📹

An intelligent Android sentinel that detects ambient burps and belches using on-device machine learning (TensorFlow Lite / YAMNet) and automatically records a short video clip centered around the event.

Designed to run continuously (24/7) while plugged in, acting as a dedicated room monitor.

---

## ✨ Features

- **🧠 On-Device AI/ML Audio Classification**: Uses Google's YAMNet model via TensorFlow Lite Task Audio to classify sound events in real time. Specifically triggers on *"Burping, eructation"* (AudioSet ID `/m/01b_21`) rather than basic volume thresholding.
- **⏱️ Centered Video Recording**: Employs a continuous rolling video buffer and native sub-clip extraction (`MediaExtractor` & `MediaMuxer`) so the detected burp appears in the middle of the clip rather than getting cut off at the start.
- **🔄 Camera Flipping**: Dynamic toggle between rear-facing and front-facing (selfie) cameras with live preview.
- **🔋 Continuous Operation & Screen Protection**: Option to turn off live preview while the sentinel is running to prevent OLED burn-in and save power.
- **💾 Room Database & Local Storage**: Automatically logs every event with timestamp, confidence score, duration, and local video path. Automatically prunes the oldest clips when user-defined storage limits are reached.
- **🎬 History & In-App Playback**: Embedded ExoPlayer dialog to review captured clips directly inside the app.
- **⚙️ Custom Calibration**: User-adjustable settings for:
  - AI confidence threshold (10% – 90%)
  - Evidence clip length (1s – 10s)
  - Maximum saved clips limit (10 – 1000)

---

## 🛠️ Architecture & Tech Stack

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose with Material 3 Design
- **ML / Audio**: TensorFlow Lite Task Audio (`yamnet.tflite`)
- **Camera & Video**: CameraX (`VideoCapture`, `Recorder`, `PreviewView`)
- **Video Processing**: Android `MediaExtractor` & `MediaMuxer` (native sample remuxing without transcoding)
- **Database**: Room SQLite Database
- **Video Playback**: Media3 / ExoPlayer
- **Async & Reactive**: Kotlin Coroutines & StateFlow

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Ladybug (or newer)
- Android SDK 34+
- Physical Android device with camera and microphone permissions

### Building & Running
1. Clone the repository:
   ```bash
   git clone https://github.com/Strathiee/burpradar.git
   ```
2. Open the project in Android Studio.
3. Build the debug APK:
   ```bash
   ./gradlew assembleDebug
   ```
4. Run on a connected device:
   ```bash
   ./gradlew installDebug
   ```

---

## 📄 License
This project is for personal and experimental use.
