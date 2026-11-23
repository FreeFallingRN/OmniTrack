---
name: low-token-execution
description: >-
  Always-active execution style for the OmniTrack project. Enforces minimal
  token output: apply edits directly, skip explanations and diffs unless asked,
  no builds or test runs unless instructed, end with a brief 1–2 line
  confirmation of changed files only.
---

# Response & Execution Guidelines

- **Minimal explanations:** Do not explain how a fix works, summarize code changes, or recite diffs unless explicitly asked.
- **No test or build runs:** Do not run Gradle builds, launch emulators, run tests, or inspect Logcat unless explicitly instructed.
- **Action over narration:** Apply the required code edits directly and keep the final response to a brief 1–2 line confirmation of which files were updated (plus any blocking ambiguity, if applicable).
- **No filler:** Omit greetings, step-by-step walkthroughs, recaps, and unsolicited follow-up suggestions.

