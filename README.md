# NoTrace Messenger

Privacy-first, server-minimized, peer-to-peer encrypted Android messenger.
See `PHASE_0_REQUIREMENTS_AND_THREAT_MODEL.md` for the locked decisions,
data inventory, and threat model this build follows.

## Status
- Phase 0 — Requirements & threat model: **done**
- Phase 1 — Android foundation: **done**
- Phase 2 — Secure local storage: **done**
- Phase 3 — Identity and contacts: **done**
- Phase 4 — Secure one-to-one messaging (Signal Protocol via libsignal-android): **done**
- Phase 5 — Signaling and P2P (WebRTC + authenticated signaling server): **done**
- Phase 6 — Offline delivery (temporary ciphertext relay, expiry/ack/dedup/rate limits): **done**
- Phase 7 — Media (encrypted images/files/audio/video, chunking, integrity checks, thumbnails, cleanup): **done**
- Phase 8 — Voice notes and calls (recording, WebRTC audio, call state machine, reconnection): **done**
- Phase 9 — Video calls (camera, adaptive quality, controls, background behavior): **done**
- Phase 10 — Groups and advanced features (Sender Key group crypto, admin/membership, key rotation on removal): **done**
- Phase 11 — Self-destruct hardening (message timers, conversation destruction, panic wipe with process restart, destruction tests): **done**
- Phase 12 — Performance and reliability (signaling reconnection backoff, network-restore callback, WorkManager maintenance job, low-storage handling): **done** (this commit)
- Phase 13 — Security and release review: **in progress**. Code-level
  review complete (see `PHASE_13_SECURITY_REVIEW.md`); fixes applied so
  far: WebRTC bumped off the ~2.5-year-old M114 build to a current
  release, `security-crypto` moved off an alpha to the stable `1.1.0`,
  and the signaling server gained per-IP connection/message-rate limits
  and a total-identity-binding ceiling (none of that existed before this
  pass). Still open: a real dependency/CVE scan once this builds, a
  decision on debug logging, and the rest of Section 22's Play Store
  checklist (signing keys, Data Safety declarations, support/incident-
  response docs).

**Two components now:** the Android app (`app/`) and a small signaling
server (`signaling-server/`) that must be deployed separately (see its
README) for automatic P2P messaging to work. Without it, the app still
works via Phase 4's manual bundle/ciphertext copy-paste.

**License note:** as of Phase 4 this project links an AGPLv3 library.
See `LICENSE_NOTES.md` before distributing the app to anyone.

## Build
CI builds a debug APK automatically on every push via GitHub Actions
(`.github/workflows/android-build.yml`) — no signing keys required.
Download the artifact from the Actions tab after a run completes.

To build locally: open in Android Studio (Jellyfish+), or run
`./gradlew assembleDebug` with JDK 17 installed.

## Note on the Gradle wrapper
`gradlew` / `gradle-wrapper.jar` are not included (a compiled binary
can't be produced without network access in this environment). To
generate them locally after downloading the project, run:
```
gradle wrapper --gradle-version 8.7
```
(with any local Gradle install), or simply open the project in Android
Studio, which regenerates the wrapper automatically. CI does not need
this step — it provisions Gradle directly.
