# 🔊 Phase 4: Emergency Siren Override & Lockscreen Alert

> **Status**: `🟢 Completed`  
> **Last Updated**: 2026-09-14  
> **Lead Architect**: [Rohit K Sahoo (@RohitKSahoo)](https://github.com/RohitKSahoo)

---

## 📋 Objectives & Scope
Ensure incoming emergency alerts sound out loud on Guardian devices regardless of Silent, Vibrate, or Do Not Disturb (DND) phone profiles.

## 🎯 Deliverables
- [x] **Alarm Stream Audio Routing**: Configured `AudioAttributes.USAGE_ALARM` in `SOSIncomingActivity.kt` to route siren audio through physical Alarm hardware channels.
- [x] **Max Volume Override**: Programmatically set `STREAM_ALARM` volume to maximum during active emergency incoming calls without affecting global ringer preferences.
- [x] **Lockscreen Window Flags**: Enabled `setShowWhenLocked(true)`, `setTurnScreenOn(true)`, `requestDismissKeyguard()`, and `FLAG_KEEP_SCREEN_ON` on `SOSIncomingActivity`.
- [x] **Continuous Siren Looping**: Removed 3-second auto-stop timer, enabling continuous alarm looping until Guardian dismissal.

---

## 📝 Phase Completion & Change Notes
- **[2026-09-14]** ([@RohitKSahoo](https://github.com/RohitKSahoo)): Updated `SOSIncomingActivity.kt` with continuous `MediaPlayer` looping and hardware `STREAM_ALARM` routing to bypass Silent/DND natively without requiring `ACCESS_NOTIFICATION_POLICY` permissions.
