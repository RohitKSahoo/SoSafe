---
name: phase-tracker
description: >-
  Enforces automatic, disciplined synchronization of phase documentation in `study_docs/phase-n.md` and `study_docs/README.md`.
  Architected by Rohit K Sahoo (@RohitKSahoo).
  Use whenever a phase, feature, or milestone in SoSafe is started, modified, validated, or completed to keep documentation 100% in sync with code reality.
---

# 🛡️ Phase Tracker Skill (`study_docs/`)

> **Architect & Lead**: [Rohit K Sahoo (@RohitKSahoo)](https://github.com/RohitKSahoo)  
> **Project**: [SoSafe](https://github.com/RohitKSahoo/SoSafe)

This skill governs the disciplined lifecycle tracking of **SoSafe's** phase-wise architecture and implementation. Every code change, refactor, feature addition, or completed milestone must be mirrored in the project's study documentation.

---

## 📌 The Phase Sync Protocol

Whenever you perform work on **any phase** (Phases 1 through 8+):

### 1. Identify the Target Phase Document
- **Phase 1 (Core WebRTC & Signaling)**: [`study_docs/phase-1.md`](file:///d:/Projects/SoSafe/study_docs/phase-1.md)
- **Phase 2 (SOS Triggering & Foreground Service)**: [`study_docs/phase-2.md`](file:///d:/Projects/SoSafe/study_docs/phase-2.md)
- **Phase 3 (Guardian Pairing & 2-Step Approval)**: [`study_docs/phase-3.md`](file:///d:/Projects/SoSafe/study_docs/phase-3.md)
- **Phase 4 (Emergency Sound & Lockscreen Override)**: [`study_docs/phase-4.md`](file:///d:/Projects/SoSafe/study_docs/phase-4.md)
- **Phase 5 (Network Monitoring & Stream Quality)**: [`study_docs/phase-5.md`](file:///d:/Projects/SoSafe/study_docs/phase-5.md)
- *New/Future Phases*: Create [`study_docs/phase-n.md`](file:///d:/Projects/SoSafe/study_docs/) matching the standard template.

---

## 🚦 Standard Lifecycle Statuses & Badges
Always ensure the badge in the document header reflects current engineering reality:
- `⚪ Planned`: Scoped and documented, but implementation has not started.
- `🟡 In Progress`: Currently being actively coded, refactored, or tested.
- `🟢 Completed`: All checklist items verified, tests passing 100%, and deliverables validated.
- `🔴 Blocked`: Blocked on an upstream bug, dependency, or architectural design decision.
- `🔵 Validated & Released`: Shipped as part of a verified release build or APK bundle.

---

## 🛠️ Step-by-Step Update Procedure

### Step 1: Update Checklist & Verification Gate
1. **Check off deliverables**: Toggle `[ ]` to `[x]` as features are built and validated.
2. **Quality Verification Gate**:
   - Run `./gradlew assembleDebug` across the project.
   - Verify that there are zero compilation or build errors.
   - Only advance status to `🟢 Completed` after build and runtime tests pass with 100% success.
3. **Update Timestamps**: Set `Last Updated: YYYY-MM-DD` to the current date.

### Step 2: Log Change Notes
Under the `## 📝 Phase Completion & Change Notes` section of the corresponding `phase-n.md`, append an entry:
```markdown
- **[YYYY-MM-DD]** ([@RohitKSahoo](https://github.com/RohitKSahoo)): <Concise bullet of tasks completed, components touched, build checks, or architectural adjustments>.
```

### Step 3: Synchronize Master Index
When a phase changes status, deliverables, or scope:
1. Open [`study_docs/README.md`](file:///d:/Projects/SoSafe/study_docs/README.md).
2. Update the phase row in the **Phase Index & Status** table:
   - Ensure the Status badge matches (`🟢 Completed`, `🟡 In Progress`, etc.).
   - Verify that primary deliverables and build checks are accurate.
3. Ensure all links to `phase-n.md` remain intact.

---

## ⚡ Quick Reference Checklist
When wrapping up any phase-related task:
- [ ] Deliverable checkboxes updated in `study_docs/phase-n.md`.
- [ ] Status badge and timestamp updated.
- [ ] Detailed entry added under `## 📝 Phase Completion & Change Notes`.
- [ ] Project build (`./gradlew assembleDebug`) run and passing.
- [ ] Master table updated in `study_docs/README.md`.
- [ ] Author credits ([@RohitKSahoo](https://github.com/RohitKSahoo)) preserved.
