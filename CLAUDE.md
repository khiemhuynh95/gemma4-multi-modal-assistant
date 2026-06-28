# CLAUDE.md

Guidance for Claude Code working in this repository. Read this first, then `architecture.md`, `ui-context.md`, `code-standards.md`, and `project_tracker.md`.

## What this project is

An on-device, fully private multimodal voice assistant for Android. The model runs entirely on the phone — once models are downloaded, no request ever leaves the device. The user can speak, type, or point the camera and get an answer with no network connection.

**Current focus — Phase 1: the on-device showcase.** Prove that the on-device model (Gemma 4 E2B) handles *simple* multimodal task requests privately and offline: identify or read with the camera, ask by voice or text. This is the only thing we are building right now.

## Scope (Phase 1) — and hard non-goals

In scope:
- On-device inference with Gemma 4 E2B via LiteRT-LM (text + image + audio file input; live audio via existing speech-to-text).
- Three input modalities: voice (`SpeechRecognizer`), camera (CameraX, new), text.
- **Media file attachments:** attach an image (Android Photo Picker) or an audio file (document picker) to a turn; the model answers about it on-device. **No video** — LiteRT-LM 0.13.1 exposes only Text / Image / Audio `Content` types, so video is unsupported (frame-sampling a clip into images is deferred, not built or stubbed).
- Two screens: Conversation (hub) and Camera capture.
- Local-first persistence of the conversation.
- Streaming responses with text-to-speech + live captions.

Explicitly OUT of scope for Phase 1 — do not build, do not stub:
- No backend, no cloud, no remote API of any kind.
- No RAG, no external tools / function-calling to external systems, no web access.
- No accounts, login, or sync.

These are deferred to later phases (see Roadmap in `architecture.md`). The offline / no-cloud constraint is the product's whole point — treat any cloud dependency as a bug.

## Stack

- Language: Kotlin. UI: Jetpack Compose (Material 3).
- On-device LLM: `gemma-4-E2B-it` (`.litertlm`) via Google LiteRT-LM (GPU acceleration, CPU fallback). Multimodal vision/audio sub-models load on demand.
- Speech-to-text: Android `SpeechRecognizer`. Text-to-speech: Kokoro via sherpa-onnx (offline), system-TTS fallback. VAD: `silero_vad.onnx` (bundled in `assets/`) for barge-in.
- Camera: CameraX (new in Phase 1).
- Persistence: Room (SQLite), new in Phase 1.
- `minSdk 28`, `targetSdk 35`. Models download at runtime (Hugging Face token on first launch); not bundled in the repo.

## Existing code — extend, don't rewrite

- `VoiceAssistant.kt` — core orchestration (STT → LLM → TTS). Extend for multimodal prompt assembly and inference config.
- `AssistantViewModel.kt` — UI state and model download. Extend for image state, vision-model load, and persistence.
- `KokoroTts.kt` — offline TTS wrapper. Reuse the streaming sentence handoff.
- `AssistantScreen.kt` — Compose UI. Becomes the Conversation screen.
- New: `CameraScreen.kt` and a camera controller, a `MultimodalEngine` (E2B wrapper), and a `ConversationStore` (Room).

## Conventions

- **Media before text.** When a prompt includes an image, place the image content before the instruction text (model-card requirement) or quality drops.
- **Don't overthink simple tasks.** The per-turn `InferenceConfig` (visual token budget + thinking on/off) is the on-device "router": simple intents run with a low visual budget and thinking off for low latency; only heavier intents spend compute. See `configFor` in `architecture.md`.
- **Local-first data.** Persist the raw conversation locally (Room). Store captured images in app-private storage and reference them from the DB — never put bitmaps in SQLite. The prompt sent to the model is a recent window plus a running summary, not the whole history.
- **Offline is not an error.** The offline / on-device indicator reflects connectivity but never gates functionality.
- **Design fidelity.** Build to `ui-context.md` and the mockups (`conversation.html`, `cameras.html`) using the tokens in `DESIGN.md`. The HTML mockups are visual references only — implement in Compose with a Material 3 theme derived from `DESIGN.md`.
- **Clean, reusable, modular.** Small single-purpose functions, shared logic instead of duplication, and decoupled layers behind interfaces. See `code-standards.md`.

## Build & run

- Open in Android Studio (recent stable), let Gradle sync, run the `app` config on an API 28+ device or emulator (a physical device is recommended for camera and GPU).
- `./gradlew assembleDebug` to build, `./gradlew installDebug` to install, `./gradlew lint` and `./gradlew test` for checks.
- First launch: paste a Hugging Face token to download the Gemma model; optionally download the Kokoro voice.
- Update these commands here if the project's actual Gradle setup differs.

## Definition of done (Phase 1)

With the device in airplane mode, the user can (a) capture a photo and ask "what is this?" / "read this" and get a spoken and captioned answer, and (b) ask a question by voice or text and get an answer — all on-device, with the conversation persisted across app restarts.

## Keeping docs in sync — IMPORTANT

This file is the source of truth for scope and conventions; three companion docs hold the detail. Whenever you change something, update the doc that owns it **in the same commit**. Do not let docs drift from code.

| If you change… | Update… |
| --- | --- |
| Components, data flow, models, persistence, function contracts, or dependencies | `architecture.md` |
| Screens, screen states, components, navigation, or design tokens / theme | `ui-context.md` |
| Task status, milestone scope, or you add / remove / re-prioritise work | `project_tracker.md` |
| Coding conventions, shared patterns, or standards you establish | `code-standards.md` |
| Project scope, stack, build commands, or these conventions | `CLAUDE.md` (this file) |

- Code and docs change together. If a commit changes behaviour described in a doc, edit that doc in the same commit.
- `project_tracker.md` must reflect reality: tick items done, add discovered tasks, record blockers.
- If a change spans multiple docs, update all of them.
- When something moves from "future / roadmap" to "now", update this file first, then cascade to the others.