## 💡 MOOD-ON - Multimodal-based Integrated Emotion Feedback Service

## Project Description
This repository contains a Capstone Design project developed as part of my undergraduate graduation requirements.
*"Understand your emotions objectively, and receive real healing feedback through IoT and generative AI."*
<br>

## 📖 Overview

**Mood-ON** is a multimodal emotion-recognition application that helps users record their daily emotions through journaling and receive real-time emotional feedback. Unlike conventional mood-tracking apps that rely on users manually selecting how they feel, Mood-ON analyzes both the **text of a diary entry** and the user's **facial expression** to measure emotions more objectively. The recognized emotion is then reflected in IoT hardware (mood light and ambient sound) and an AI chatbot, delivering a genuine emotional-healing experience.
<br>

## 💡 Background

Existing emotion-diary apps such as MindLog and Daylio depend on users directly selecting their emotions, which introduces subjective bias and stops at simple record-keeping and statistics. Mood-ON was designed to address three key points:

1. **Difficulty of mental-health management** — Modern life leaves little room to recognize and manage one's own emotions.
2. **Limitations of existing emotion-diary apps** — Reliance on manual emotion input and lack of meaningful feedback.
3. **Advances in multimodal emotion recognition, IoT, and generative AI** — Making objective measurement and real-time feedback technically feasible.
<br>

## ✨ Key Features

- **Multimodal Emotion Analysis** — Simultaneously analyzes diary text and a facial video recorded via the front camera while writing.
- **Weighted Fusion** — Combines the text-emotion model (KcELECTRA) and the facial-emotion model (MediaPipe) using a weighted average (text 0.6 : video 0.4) to determine the final emotion.
- **IoT Mood Feedback** — The recognized emotion is delivered to ESP32 hardware, which outputs a mapped mood-light color and ambient sound.
- **AI Emotion Chatbot** — A GPT-based chatbot provides emotion-based counseling; diaries and conversation history are stored for later review.
- **Emotion Statistics** — Weekly and monthly emotion trends, along with exercise and study time, are visualized.
<br>

## 🧠 Emotion Categories

Mood-ON recognizes **five emotions**, each mapped to a mood-light color and ambient sound:

| Emotion | Type | Goal | Light | Ambient Sound |
|---------|------|------|-------|---------------|
| Joy (기쁨) | Positive | Sustain | Yellow | Bright acoustic + birdsong |
| Sadness (슬픔) | Negative | Recover | Purple | Soft piano + rain |
| Anger (분노) | Negative | Calm | Blue / Red | Slow ambient + waves |
| Anxiety (불안) | Negative | Soothe | Blue | Drone + pink noise |
| Embarrassment (당황) | Negative | Stabilize | Green | Ambient + stream |

> Color and sound mappings are grounded in color-psychology and sound-psychology research: positive emotions are reinforced, while negative emotions are gently regulated.
<br>

## 🛠️ Tech Stack - My Contributions (Android & Hardware)

**Mobile App**
- Kotlin, Jetpack Compose, MVVM + Repository pattern
- CameraX (front-camera recording), OkHttp
- Firebase Auth / Firestore / Storage / Realtime Database

**Hardware**
- ESP32 (Arduino), WS2812B NeoPixel LED ring, DFPlayer Mini
- Reads emotion results from Firebase Realtime Database via polling
- Emotion-to-color and emotion-to-sound mapping based on color/sound psychology

## 🏗️ System Architecture

![Mood-ON System Architecture](System%20Architecture.png)

**Why the app (not the server) writes to Firebase**<br>
Emotion analysis runs on the GCP server, but the final result is saved to Firebase by the authenticated Android client rather than by the server directly.
Writing from the server would require deploying a Firebase Service Account Key, a highly sensitive credential that grants full admin access to the entire project. Exposing this key (e.g., in the repository or on the server) would be a serious security risk.
Instead, the app — already authenticated via Firebase Auth — writes results under its own user context (auth.uid).
