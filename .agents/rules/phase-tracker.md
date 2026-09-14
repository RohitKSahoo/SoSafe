# 📌 Phase Tracker Always-Active Project Rule: Documentation & Progress Synchronization

> **Architect & Lead**: [Rohit K Sahoo (@RohitKSahoo)](https://github.com/RohitKSahoo)  
> **Repository**: [SoSafe](https://github.com/RohitKSahoo/SoSafe)

Enforce strict 1:1 synchronization between code implementation progress, test suite verification, and the master phase study documentation in `study_docs/`:

## 1. ALWAYS-ACTIVE MANDATORY RULE
Whenever **ANY** task, bugfix, refactor, UI change, or feature modification is performed in this project:
- You **MUST** automatically activate and adhere to the [`phase-tracker`](file:///d:/Projects/SoSafe/.agents/skills/phase-tracker/SKILL.md) skill instructions.
- Keep documentation in lockstep with the actual state of the codebase after every modification.

## 2. Active Phase Document Synchronization (`study_docs/`)
- Identify and update the corresponding phase document in [`study_docs/phase-n.md`](file:///d:/Projects/SoSafe/study_docs/).
- Check off completed deliverables (`[x]`).
- Update the phase status badge (`⚪ Planned`, `🟡 In Progress`, `🟢 Completed`, `🔴 Blocked`).
- Update the `Last Updated: YYYY-MM-DD` timestamp to the current date.

## 3. Quality & Verification Gate
- **Never** mark a phase as `🟢 Completed` unless all associated Android build verification checks pass (`./gradlew assembleDebug`) and deliverables are fully verified.
- If a regression or blocking issue occurs during development, immediately flag the phase as `🔴 Blocked` or `🟡 In Progress`.

## 4. Log Change Notes
- In `study_docs/phase-n.md`, append an entry under `## 📝 Phase Completion & Change Notes` noting what was created, tested, or modified, with date and author attribution ([@RohitKSahoo](https://github.com/RohitKSahoo)).

## 5. Master Index Synchronization
- Keep [`study_docs/README.md`](file:///d:/Projects/SoSafe/study_docs/README.md) updated with matching status badges and deliverable counts whenever a phase's state or scope changes.
