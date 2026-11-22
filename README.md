<div align="center">
  <img src="docs/chesslens-mark.svg" alt="ChessLens icon" width="112" height="112">
  <h1>ChessLens</h1>
  <p><strong>A local-first chess training companion for Android.</strong></p>
  <p>
    <img alt="Android" src="https://img.shields.io/badge/Android-API%2029%2B-3DDC84?logo=android&logoColor=white">
    <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white">
    <img alt="Training only" src="https://img.shields.io/badge/Use-training%20only-f97316">
    <img alt="Engine" src="https://img.shields.io/badge/Engine-Stockfish%20ARM64-2563eb">
  </p>
  <p>
    <a href="#getting-started">Get started</a> ·
    <a href="#how-it-works">How it works</a> ·
    <a href="#safety--privacy">Safety & privacy</a> ·
    <a href="#known-limitations">Limitations</a> ·
    <a href="https://github.com/yangmetastasi104/ChessLens/releases">Releases</a>
  </p>
</div>

---

ChessLens is an **experimental training tool**. With your explicit training selection and Android’s screen-capture consent, it processes frames on-device, checks observed changes against legal chess moves, and can show a Stockfish suggestion in an overlay.

> <img src="docs/icons/shield-check.svg" width="18" height="18" alt=""> **Training only.** Do not use ChessLens during rated games, tournaments, or any activity where outside assistance is prohibited. Suggestions are disabled unless you affirm that the session is for permitted training.

## What it does

- <img src="docs/icons/camera.svg" width="18" height="18" alt=""> **Consent-gated capture.** Captures the screen only after Android’s per-session MediaProjection consent.
- <img src="docs/icons/board.svg" width="18" height="18" alt=""> **Legal-move tracking.** Checks candidate board changes against a trusted, complete FEN and legal chess continuations.
- <img src="docs/icons/cpu.svg" width="18" height="18" alt=""> **Bundled engine.** Runs Stockfish with matching NNUE networks on supported ARM64 devices.
- <img src="docs/icons/target.svg" width="18" height="18" alt=""> **Validated suggestions.** Accepts an engine move only after it passes the app’s legal-move generator.
- <img src="docs/icons/lock.svg" width="18" height="18" alt=""> **Local by design.** Processes frames in memory; nothing is uploaded or saved.
- <img src="docs/icons/alert.svg" width="18" height="18" alt=""> **Truthful failure.** Reports uncertainty or engine unavailability instead of inventing a position or evaluation.

## Getting started

### Build

Requirements: JDK 17, Android SDK platform 35, and Android Build Tools 35.0.0.

```sh
./gradlew clean testDebugUnitTest assembleDebug
```

The debug APK is created at `app/build/outputs/apk/debug/app-debug.apk`.

### Use for a training session

1. Open ChessLens and select **Training**.
2. Enter the complete six-field FEN for the board on screen.
3. Confirm that the visible board matches that FEN and select the board orientation.
4. Grant overlay permission if Android requests it, then approve the screen-capture prompt.
5. Keep the board stable while ChessLens calibrates. A suggestion appears only after a position is accepted and the engine’s move passes legal-move validation.

Select **Not training** or stop/revoke screen capture to end the session. ChessLens does not identify which app is visible and cannot verify that a venue permits assistance.

## How it works

```text
Explicit training choice + FEN confirmation
                    │
          Android capture consent
                    │
       On-device board recognition
                    │
    Legal continuation / uncertainty gate
                    │
       Stockfish UCI (ARM64 only)
                    │
        Legal-move validation
                    │
        Optional screen overlay
```

The entered FEN supplies side-to-move, castling rights, en-passant square, and move counters. ChessLens cannot infer missing move history from pixels. If the game has been reset or the initial position was entered incorrectly, stop and begin again with a correct FEN.

## Platform and engine

The bundled engine is the portable ARM64 (`stockfish_nosimd`) UCI executable from F-Droid DroidFish 1.90. Its corresponding source revision, Android build files, provenance, and GPL notice are under [`third_party/stockfish-droidfish-1.90/`](third_party/stockfish-droidfish-1.90/). The matching NNUE networks are included in the app assets.

**Supported engine ABI:** `arm64-v8a`. Other ABIs report that the engine is unavailable; ChessLens does not substitute a fabricated evaluation or move.

## Safety & privacy

- Suggestions are intended only for permitted training.
- Both an explicit in-app training confirmation and Android’s screen-capture consent are required.
- The app does not bypass platform controls or inspect app identity.
- Captured frames are processed locally in memory and are not uploaded or written to storage.
- Board recognition is uncertain by nature; a legal-move gate reduces some errors but does not guarantee correct visual interpretation.

## Known limitations

- Board detection assumes an axis-aligned board; perspective and tilt correction are not implemented.
- Piece recognition uses visual templates calibrated from the supplied position and may fail across themes, animations, occlusion, or unusual board colors.
- Special-move state depends on the supplied FEN and subsequent correctly tracked moves.
- The prototype has unit-test coverage but has **not** been validated for recognition accuracy, latency, or engine startup on a physical device.
- The bundled Stockfish engine is ARM64-only.

## Development

```sh
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

## Third-party licensing

Stockfish is licensed under **GPL-3.0-or-later**. The applicable license, corresponding engine source, build files, and binary provenance are included in this repository. See [`third_party/stockfish-droidfish-1.90/README.md`](third_party/stockfish-droidfish-1.90/README.md) and [`Copying.txt`](third_party/stockfish-droidfish-1.90/Copying.txt).
