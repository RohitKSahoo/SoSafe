# Product Requirements Document (PRD)

---

## 1. Product Overview

* **Project Title**: SoSafe
* **Version**: 1.0
* **Last Updated**: 22 March 2026
* **Owner**: Rohit

---

## 2. Problem Statement

In emergency situations (harassment, assault, unsafe environments), users—especially women—often cannot manually call for help, unlock their phone, or share their location.

Existing safety apps:

* Require manual interaction (too slow)
* Fail under stress conditions
* Lack real-time contextual data (audio, continuous tracking)

**Core problem:**
There is no reliable, instant, hands-free mechanism to alert trusted contacts with real-time situational data.

---

## 3. Goals & Objectives

### Business Goals

* Achieve **<3 seconds SOS activation-to-alert latency**
* Reach **90% successful alert delivery rate** under varying network conditions
* Ensure **<5% false trigger rate** through optimized gesture detection

### User Goals

* Trigger SOS instantly without unlocking phone
* Share live location and audio automatically
* Ensure family receives actionable, real-time updates
* Operate silently without drawing attention

---

## 4. Success Metrics

* **SOS Trigger Time**: <1 second
* **Alert Delivery Time**: <3 seconds
* **Session Reliability**: >90% uninterrupted transmission
* **False Trigger Rate**: <5%
* **User Retention (MVP testing)**: >60% after 1 week

**Measurement Approach:**

* Firebase logs (event timestamps)
* Session completion tracking
* Trigger vs cancel ratio
* Crash analytics

---

## 5. Target Users & Personas

### Primary Persona: Urban Female User (Safety-Focused)

* **Demographics**: Women aged 16–40, urban/semi-urban, smartphone users
* **Pain Points**:

  * Cannot access phone quickly in danger
  * Fear of escalation when visibly calling for help
  * Lack of immediate support
* **Goals**:

  * Discreetly alert trusted contacts
  * Share real-time situation data
* **Technical Proficiency**: Moderate

---

### Secondary Persona: Guardian / Family Member

* **Demographics**: Parents, siblings, partners
* **Pain Points**:

  * Delayed or no information during emergencies
  * Inability to assess seriousness
* **Goals**:

  * Receive immediate alerts
  * Track live location
  * Hear real-time surroundings
* **Technical Proficiency**: Low to Moderate

---

## 6. Features & Requirements

### Must-Have Features (P0)

#### 1. SOS Trigger System

* **Description**: Detect emergency via power button presses or sustained shaking
* **User Story**: As a user, I want to trigger SOS instantly without unlocking my phone so that I can get help quickly
* **Acceptance Criteria**:

  * [ ] SOS triggers on 3–5 rapid power button presses
  * [ ] SOS triggers on ≥5 seconds continuous shaking
  * [ ] 2-second vibration feedback allows cancellation
* **Success Metric**: >95% trigger detection accuracy

---

#### 2. Real-Time Location Sharing

* **Description**: Continuous GPS updates sent to backend and receivers
* **User Story**: As a user, I want my live location shared so that my family can track me
* **Acceptance Criteria**:

  * [ ] Location updates every 3–5 seconds
  * [ ] Fallback to network location if GPS unavailable
  * [ ] Data visible on receiver app map
* **Success Metric**: Location accuracy within ±20 meters

---

#### 3. Audio Transmission (Chunk-Based)

* **Description**: Continuous microphone capture uploaded in short intervals
* **User Story**: As a user, I want my surroundings recorded so that others understand my situation
* **Acceptance Criteria**:

  * [ ] Audio recorded in 3–5 second chunks
  * [ ] Chunks uploaded immediately after recording
  * [ ] Receiver can play near real-time audio
* **Success Metric**: <5 second delay in playback

---

#### 4. Emergency Alert System

* **Description**: Notify linked contacts via push + SMS fallback
* **User Story**: As a user, I want my contacts alerted immediately so they can respond
* **Acceptance Criteria**:

  * [ ] FCM push sent within 2 seconds of trigger
  * [ ] SMS fallback if internet unavailable
  * [ ] Alert includes session ID and location link
* **Success Metric**: >90% delivery success rate

---

#### 5. Code-Based Identity System

* **Description**: Users connect via unique codes (no login required)
* **User Story**: As a user, I want a simple way to connect with family without signup friction
* **Acceptance Criteria**:

  * [ ] Unique 6–8 digit code generated per user
  * [ ] Contacts added via code input
  * [ ] Only linked users can access sessions
* **Success Metric**: <10 seconds onboarding time

---

#### 6. Foreground Emergency Service

* **Description**: Persistent service ensuring reliability in background
* **User Story**: As a user, I want the app to work even if closed
* **Acceptance Criteria**:

  * [ ] Foreground service activates on SOS
  * [ ] Persistent notification present
  * [ ] Service survives app minimization
* **Success Metric**: <2% service termination rate

---

---

### Should-Have Features (P1)

#### 1. Receiver Dashboard (Guardian App)

* Live map tracking
* Audio playback
* Session status

---

#### 2. Retry & Failover System

* Retry failed uploads
* Cache last known data locally

---

#### 3. Battery & Network Indicators

* Display sender battery level
* Show last update timestamp

---

### Nice-to-Have Features (P2)

#### 1. Wearable Integration

* SOS via smartwatch

#### 2. AI Distress Detection

* Voice stress recognition

#### 3. Nearby User Alert Network

* Crowd-sourced safety alerts

---

## 7. Explicitly OUT OF SCOPE

* Video streaming
* AI-based threat detection (MVP phase)
* Integration with police/emergency services
* Social features or public feed
* Cross-platform (iOS) support
* End-to-end encryption (initial MVP)

---

## 8. User Scenarios

### Scenario 1: Immediate Threat Situation

* **Context**: User feels unsafe while walking alone
* **Steps**:

  1. User presses power button rapidly
  2. Device vibrates (2-second cancel window)
  3. SOS session activates automatically
  4. Location + audio transmission begins
  5. Contacts receive alert
* **Expected Outcome**:
  Contacts track user and hear surroundings in near real-time
* **Edge Cases**:

  * No internet → SMS fallback triggered
  * GPS unavailable → fallback location used

---

## 9. Dependencies & Constraints

### Technical Constraints

* Android background execution limits
* Battery optimization restrictions
* Microphone + location permission requirements

### Business Constraints

* Solo developer / small team
* MVP timeline constraint (hackathon / demo-driven)

### External Dependencies

* Firebase (Firestore, Storage, FCM)
* Google Location Services
* SMS services (device-based fallback)

---

## 10. Timeline & Milestones

* **MVP**: 4–6 weeks

  * Core triggers
  * Location sharing
  * Audio chunk upload
  * Basic receiver app

* **V1.0**: 8–10 weeks

  * Improved reliability
  * UI polish
  * Retry systems

---

## 11. Risks & Assumptions

### Risks

* **Background service termination**
  → Mitigation: Foreground service + user prompts

* **Audio streaming instability**
  → Mitigation: Chunk-based fallback

* **False triggers**
  → Mitigation: Cancel window + tuning thresholds

---

### Assumptions

* Users will grant required permissions
  → Validate via onboarding flow

* Internet is intermittently available
  → Covered via hybrid SMS fallback

---

## 12. Non-Functional Requirements

* **Performance**:

  * Alert latency <3 seconds
  * Audio delay <5 seconds

* **Security**:

  * Access restricted to linked users
  * Secure session IDs

* **Accessibility**:

  * Minimal interaction required
  * Works without screen usage

* **Scalability**:

  * Firebase-backed horizontal scaling
  * Session-based architecture

---

## 13. References & Resources

* Competitor apps: bSafe, Citizen, Noonlight
* Android Developer Docs (Foreground Services, Sensors)
* Firebase Documentation (FCM, Firestore, Storage)


