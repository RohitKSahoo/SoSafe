Here’s your **TECH_STACK_REQUIRED** document adapted precisely to your SoSafe architecture (mobile-first, Firebase-based, safety-critical system), aligned with your template 

---

# Technology Stack Documentation

## 1. Stack Overview

**Last Updated**: 22 March 2026
**Version**: 1.0

### Architecture Pattern

* **Type**: Hybrid (Mobile Client + Backend-as-a-Service)
* **Pattern**: Client-Service (Event-driven, real-time updates)
* **Deployment**: Cloud (Firebase-based infrastructure)

---

## 2. Frontend Stack

### Core Framework

* **Framework**: Android Native (Kotlin)
* **Version**: Latest stable (Android SDK 34+)
* **Reason**:

  * Full control over hardware triggers (power button, sensors)
  * Reliable background services
  * Required for safety-critical performance
* **Documentation**: [https://developer.android.com](https://developer.android.com)
* **License**: Apache 2.0

---

### UI Layer

* **Framework**: Jetpack Compose
* **Reason**:

  * Modern declarative UI
  * Faster iteration
  * Minimal UI complexity (fits MVP scope)

---

### State Management

* **Approach**: ViewModel + StateFlow
* **Reason**:

  * Lifecycle-aware
  * Stable under background execution
  * Prevents memory leaks

---

### Background Processing

* **Tools**:

  * Foreground Service (critical)
  * WorkManager (retry tasks)
* **Reason**:

  * Ensures app survives OS restrictions
  * Handles long-running tasks (audio, location)

---

### Sensors & Hardware Access

* **APIs**:

  * SensorManager (shake detection)
  * BroadcastReceiver (power button events)
* **Reason**:

  * Enables zero-touch SOS triggering

---

### Location Services

* **Library**: Google Play Services Location (FusedLocationProvider)
* **Reason**:

  * High accuracy + battery efficiency

---

### Audio Capture

* **API**: MediaRecorder / AudioRecord
* **Reason**:

  * Continuous recording support
  * Low-level control for chunking

---

## 3. Backend Stack

### Platform

* **Service**: Firebase (Primary Backend)

* **Components Used**:

  * Firestore
  * Firebase Storage
  * Firebase Cloud Messaging (FCM)

* **Reason**:

  * Rapid MVP development
  * Real-time capabilities
  * Minimal infrastructure overhead

---

### Database

* **Primary**: Firestore (NoSQL)
* **Reason**:

  * Real-time sync
  * Flexible schema
  * Scales automatically

---

### File Storage

* **Service**: Firebase Storage
* **Use Case**:

  * Audio chunk uploads
* **Reason**:

  * Tight integration with Firebase
  * Easy access control

---

### Real-Time Communication

* **Mechanism**: Firestore listeners + FCM
* **Reason**:

  * Simulates real-time streaming
  * No need for WebRTC complexity

---

### Authentication

* **Strategy**: Code-Based Identity (Custom)
* **Implementation**:

  * Unique user codes stored in Firestore
* **Reason**:

  * Zero friction onboarding
  * No OTP/email dependency

---

### API Layer

* **Type**: Minimal / Serverless (Firebase SDK-based)
* **Reason**:

  * Direct client-to-Firebase interaction
  * Reduces backend complexity

---

## 4. DevOps & Infrastructure

### Version Control

* **System**: Git
* **Platform**: GitHub
* **Branch Strategy**:

  * main (production)
  * dev (development)
  * feature/*

---

### CI/CD

* **Platform**: GitHub Actions
* **Workflows**:

  * Build APK on push
  * Lint + test checks
  * Optional Firebase deploy

---

### Hosting

* **Backend**: Firebase (Google Cloud)
* **Reason**:

  * Managed infrastructure
  * High reliability

---

### Monitoring

* **Crash Reporting**: Firebase Crashlytics
* **Analytics**: Firebase Analytics
* **Logging**: Firebase Logs

---

### Testing

* **Unit Testing**: JUnit
* **UI Testing**: Espresso
* **Coverage Target**: 70% (MVP realistic target)

---

## 5. Development Tools

### Code Quality

* **Linter**: ktlint
* **Formatter**: ktfmt
* **Static Analysis**: Detekt

---

### IDE Recommendations

* **Editor**: Android Studio
* **Plugins**:

  * Kotlin
  * Firebase Assistant
  * Jetpack Compose Tools

---

## 6. Environment Variables

### Required Variables

```bash
# Firebase
FIREBASE_PROJECT_ID="..."
FIREBASE_API_KEY="..."
FIREBASE_STORAGE_BUCKET="..."

# App Config
APP_ENV="development"
LOCATION_UPDATE_INTERVAL=5000
AUDIO_CHUNK_DURATION=5000

# SMS Fallback
SMS_ENABLED=true
```

---

## Strategic Notes (Important)

* **You intentionally avoided**:

  * Node.js backend
  * AWS S3
  * Complex auth systems

→ This is correct for MVP velocity.

* **Your stack is optimized for**:

  * Speed of development
  * Reliability
  * Minimal infrastructure overhead

* **Upgrade Path (Post-MVP)**:

  * Add custom backend (Node.js + WebSockets)
  * Replace chunk audio with WebRTC
  * Add phone-based authentication

