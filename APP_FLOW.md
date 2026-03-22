# Application Flow Documentation

---

## 1. Entry Points

### Primary Entry Points

* **Direct App Launch**:
  User opens **SoSafe (Sender App)** → lands on **Dashboard Screen**

  * Displays:

    * User code
    * Connected contacts
    * “System Active” status

* **Landing Page**:
  Minimal interface (not a marketing app)

  * Focus: readiness, not exploration
  * No onboarding friction

* **Deep Links**:

  * Push Notification (FCM) → opens **Receiver App → Live Emergency Screen**
  * SMS Link → opens:

    * Google Maps (if app not installed)
    * Receiver app (if installed)

* **OAuth/Social Login**:
  Not applicable (intentionally removed for MVP simplicity)

---

### Secondary Entry Points

* **Receiver App Launch**:

  * Opens:

    * “No Active Alerts” state OR
    * “Active Emergency Session”

* **Marketing Campaigns**:
  Not applicable for MVP

---

## 2. Core User Flows

---

### Flow 1: SOS Emergency Activation

**Goal**: Trigger emergency alert instantly without interacting with UI
**Entry Point**: Hardware triggers (power button / shake)
**Frequency**: Rare but critical

---

#### Happy Path

1. **System State: Passive Monitoring**

   * Elements:

     * Sensor listeners (accelerometer)
     * Power button event listener
   * User Action:

     * Rapid power button presses OR sustained shaking
   * Trigger:

     * Threshold met

---

2. **System Action: Pre-Trigger Validation**

   * Elements:

     * 2-second vibration feedback
   * User Action:

     * No cancellation
   * Trigger:

     * Timeout completes

---

3. **System Action: SOS Session Initialization**

   * Backend:

     * Generate session_id
     * Mark session ACTIVE

---

4. **System Action: Foreground Service Activation**

   * Elements:

     * Persistent notification
   * Behavior:

     * Prevent OS termination

---

5. **System Action: Alert Dispatch**

   * Actions:

     * Send FCM push to contacts
     * Send SMS fallback (if offline)

---

6. **System Action: Data Streaming**

   * Location:

     * Updates every 3–5 seconds
   * Audio:

     * Record → upload chunks continuously

---

7. **Receiver App: Alert Entry**

   * Elements:

     * Emergency notification
   * User Action:

     * Opens session

---

8. **Receiver App: Live Monitoring Screen**

   * Elements:

     * Live map
     * Audio playback
     * Status indicators
   * Success State:

     * Guardian receives real-time updates

---

#### Error States

* **Trigger Failure**

  * Silent failure → improved via tuning

* **Permission Missing**

  * Block functionality
  * Prompt on next app open

* **Push Notification Failure**

  * SMS fallback triggered

* **Audio Upload Failure**

  * Retry queue
  * Continue location updates

---

#### Edge Cases

* Accidental shake → mitigated by duration threshold
* App killed → foreground service restart
* No GPS → fallback location
* No internet → SMS-only mode

---

#### Exit Points

* Success: Active monitoring
* Cancel: During vibration window
* Failure: Permission/service failure

---

### Flow 2: Contact Linking (Code-Based)

**Goal**: Connect user with trusted contacts
**Entry Point**: Dashboard → Add Contact
**Frequency**: Low

---

#### Happy Path

1. **Page: Dashboard**

   * Elements:

     * “Add Contact” button
   * User Action:

     * Clicks button

---

2. **Page: Add Contact Screen**

   * Elements:

     * Code input field
     * Submit button
   * User Actions:

     * Enters 6–8 digit code
   * Validation:

     * Format + existence check

---

3. **System Action: Link Users**

   * Backend:

     * Validate code
     * Create connection

---

4. **Page: Confirmation**

   * Elements:

     * Success message
   * Success State:

     * Contact added

---

#### Error States

* Invalid code → “User not found”
* Duplicate → “Already connected”

---

#### Edge Cases

* Network failure → retry
* Repeated invalid attempts → rate limit

---

#### Exit Points

* Success: Contact linked
* Failure: Invalid input

---

### Flow 2: Emergency Monitoring (Receiver)

**Goal**: Monitor emergency in real-time
**Entry Point**: Push notification / app open
**Frequency**: Rare

---

#### Happy Path

1. **Page: Notification**

   * Elements:

     * Emergency alert
   * User Action:

     * Clicks notification

---

2. **Page: Alert Screen**

   * Elements:

     * User identifier
     * “View Live” CTA
   * User Action:

     * Opens session

---

3. **Page: Live Monitoring Dashboard**

   * Elements:

     * Map (live location)
     * Audio playback stream
     * Last updated timestamp
     * Battery status

---

4. **System State: Continuous Updates**

   * Behavior:

     * Fetch + render updates
   * Success State:

     * Guardian informed

---

#### Error States

* Session expired → show message
* Audio delay → buffering indicator
* No updates → timestamp shown

---

#### Edge Cases

* Late join → partial session data
* Network fluctuation → degraded updates

---

#### Exit Points

* Manual exit
* Session end

---

## 3. Navigation Map

### Primary Navigation

#### Sender App (SoSafe)

* Dashboard
  → Add Contact
  → Contact List
  → Settings

* Background Flow
  → SOS Trigger
  → Emergency Service

---

#### Receiver App (SoSafe Guardian)

* Home Screen
  → Active Alerts
  → Alert Screen
  → Live Monitoring

---

### Navigation Rules

* **Authentication Required**:
  Not applicable (code-based identity system)

* **Redirect Logic**:

  * If session active → open Live Monitoring
  * If no session → show idle screen

* **Back Button Behavior**:

  * Live session persists in background
  * Navigation does not terminate session

---

## 4. Screen Inventory

---

### Screen: Dashboard (Sender)

* **Route**: `/dashboard`
* **Access**: Public (device-based identity)
* **Purpose**: Show readiness and contacts
* **Key Elements**:

  * User code
  * Contact list
  * Add contact button
* **Actions Available**:

  * Add Contact → Add Contact Screen
* **State Variants**:

  * Empty (no contacts)
  * Loaded

---

### Screen: Add Contact

* **Route**: `/add-contact`
* **Access**: Public
* **Purpose**: Link users
* **Key Elements**:

  * Code input
  * Submit button
* **Actions Available**:

  * Submit → Dashboard
* **State Variants**:

  * Error (invalid code)
  * Success

---

### Screen: Alert Screen (Receiver)

* **Route**: `/alert`
* **Access**: Linked users only
* **Purpose**: Confirm emergency
* **Key Elements**:

  * Alert message
  * View Live button
* **Actions Available**:

  * Open session → Live Dashboard

---

### Screen: Live Monitoring

* **Route**: `/live-session`
* **Access**: Linked users only
* **Purpose**: Monitor emergency
* **Key Elements**:

  * Map
  * Audio player
  * Status indicators
* **Actions Available**:

  * Exit session
* **State Variants**:

  * Loading
  * Active
  * Error

---

## 5. Interaction Patterns

### Pattern: Background Trigger

* Detection: Sensor + hardware events
* Feedback: Vibration
* Execution: Automatic

---

### Pattern: Data Streaming

* Location: Polling every few seconds
* Audio: Chunk upload
* Failure: Retry mechanism

---

### Pattern: Notification Handling

* Push → deep link into session
* SMS → external map or app

---

## 6. Decision Points

### Decision: Trigger Validation

* If gesture valid → start SOS
* Else → ignore

---

### Decision: Network Availability

* If online → full system
* If offline → SMS fallback

---

## 7. Error Handling Flows

### 404 Not Found

* Not applicable (no web routing)

---

### 500 Server Error

* Display: Generic error message
* Action: Retry
* Fallback: Continue partial functionality

---

### Network Offline

* Display: Minimal indication (if UI visible)
* Actions:

  * Send SMS fallback
  * Queue uploads
* Recovery:

  * Auto-resume when online

---

## 8. Responsive Behavior

### Mobile-Specific Flows

* Primary platform
* Gesture-based interaction
* Minimal UI dependency

---

### Desktop-Specific Flows

* Not applicable (MVP)

---

## 9. Animation & Transitions

### Page Transitions

* Navigation: Fast fade (200–300ms)
* Alert screen: Instant load (no delay)

---

### Micro-interactions

* Trigger feedback: Strong vibration
* Button press: Subtle scale
* Success states: Minimal animation (avoid distraction)

---

This flow prioritizes:

* **Zero friction**
* **Reliability under stress**
* **Minimal UI dependency**

