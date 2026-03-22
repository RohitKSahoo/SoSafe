# Frontend Guidelines (SoSafe)

---

## 1. Design Principles

### Core Principles

* **Clarity**: Every UI element must justify its existence (no decorative noise)
* **Consistency**: Same actions = same visual patterns across both apps
* **Efficiency**: Zero-friction flows; user interaction minimized
* **Accessibility**: WCAG 2.1 Level AA compliance
* **Discretion First**: UI must not expose that SOS is active (sender side)
* **Latency Awareness**: UI must reflect real-time updates without lag ambiguity

---

## 2. Design Tokens

*(Adopted and slightly reinterpreted for SoSafe context)* 

---

### Color Palette

#### Primary Colors

* Used sparingly → only for actionable UI
* **Main Brand Color**: `#3b82f6`

#### Neutral Colors

* Primary UI foundation
* Dark mode recommended for safety context

#### Semantic Colors

* **Success**: System active / safe state
* **Warning**: Network issues / delayed updates
* **Error (CRITICAL)**: Emergency state (used carefully in receiver app)

---

### Usage Rules (Modified for SoSafe)

* Sender app:

  * Avoid red unless necessary (prevents panic)
* Receiver app:

  * Red = emergency context indicator
* Keep UI **low-stimulation**

---

### Typography

#### Font Families

* Primary: `Inter` (clean, readable)

#### Usage Rules

* Headings: bold, high contrast
* Body: minimal, readable
* Labels: compact (small size, medium weight)

---

### Spacing System

* Tight spacing preferred (mobile-first)
* Reduce vertical clutter
* Key rule:

  > “Everything visible in one glance”

---

### Border Radius

* Use `rounded-lg` (8px) consistently
* Avoid overly soft UI (keep it functional)

---

### Shadows

* Minimal usage
* Only for:

  * Cards
  * Floating alerts

---

## 3. Layout System

### Grid System

* Mobile-first (single column)
* Avoid complex layouts

---

### Layout Patterns

#### Sender App

* Single-screen dominance
* Minimal navigation
* Focus = readiness, not interaction

---

#### Receiver App

* Map-first layout
* Overlay controls
* Priority hierarchy:

  1. Location
  2. Audio
  3. Status

---

## 4. Component Library

---

### Buttons

#### Rules (Critical Override)

* Sender app:

  * Avoid primary buttons on main screen
  * No visible “SOS button” (gesture-based system)

* Receiver app:

  * Primary button = “View Live”
  * Must be large, obvious

---

### Input Fields

* Only used in:

  * Contact linking (code input)

#### Rules:

* Keep forms ultra-minimal
* No multi-field forms

---

### Cards

* Used for:

  * Contact list
  * Alert preview

---

### Modals

* Use sparingly
* Only for:

  * Critical confirmations
  * Error states

---

## 5. Screen-Specific Guidelines

---

### Sender App (SoSafe)

#### Dashboard

* Minimal UI:

  * User code
  * Contacts
  * System status

#### Rules:

* No distractions
* No unnecessary animations
* No heavy colors

---

### Receiver App (SoSafe Guardian)

#### Alert Screen

* High contrast
* Clear emergency messaging

#### Live Monitoring Screen

* Map dominates screen
* Audio controls secondary
* Status indicators subtle but visible

---

## 6. Interaction Patterns

---

### Pattern: Zero-UI Trigger

* No UI interaction required
* Feedback = vibration only

---

### Pattern: Passive Monitoring UI

* Data updates automatically
* No refresh button
* No manual sync

---

### Pattern: Alert Priority

* Notifications override normal UI flow
* Deep link directly into session

---

## 7. UX Rules (Non-Negotiable)

* **No cognitive load during emergencies**
* **No multi-step actions**
* **No confirmations after trigger**
* **Everything must work with screen OFF**

---

## 8. Error Handling UX

---

### Sender App

* Errors should be:

  * Silent during SOS
  * Logged in background

---

### Receiver App

* Show:

  * “Last updated X seconds ago”
  * “Connection unstable”

---

## 9. Responsive Behavior

### Mobile (Primary)

* Full optimization required
* One-hand usability

---

### Tablet/Desktop

* Not required for MVP

---

## 10. Animation & Transitions

### Rules

* Keep animations **minimal and functional**

---

### Allowed Animations

* Vibration (primary feedback)
* Subtle fade transitions
* Loading indicators

---

### Avoid

* Fancy transitions
* Delays
* Over-animation

---

## 11. Anti-Patterns (Avoid These)

* Bright, flashy UI
* Complex navigation
* Multi-step workflows
* Overuse of modals
* Gamification elements

---

## Final Design Philosophy

> This is not a “nice app.”
> This is a **silent emergency system UI**.

Design for:

* Stress
* Panic
* Zero attention

Not:

* Exploration
* Engagement
* Aesthetics


