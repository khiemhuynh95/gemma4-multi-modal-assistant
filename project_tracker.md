# Project tracker

Phase 1 — on-device multimodal showcase. Keep this current: tick items as done, add discovered work, note blockers (see `CLAUDE.md` → "Keeping docs in sync").

Status legend: `[ ]` todo · `[~]` in progress · `[x]` done · `[!]` blocked

Current status: **M0 done; M1 UI shell in place; image/audio file attachments shipped (Photo Picker + audio document picker → on-device multimodal turn, persisted). Camera (M2) still pending; video unsupported by the runtime.**

## M0 — Theme & project setup
- [x] Compose Material 3 theme from `DESIGN.md`: `ColorScheme` (light + dark), `Typography`, `Shapes` (28 / 20 / pill) in `ui/theme/`. `DESIGN.md` authored from the mockup tokens.
- [x] Fonts: system Roboto (offline-safe; a downloadable Google Font would need network, contradicting on-device) + `material-icons-extended` (`Icons.Rounded.*`) for Material Symbols Rounded. No bundled Roboto Flex / symbol font.
- [ ] Add CameraX and Room dependencies; Gradle builds clean. *(material-icons-extended added; CameraX/Room still pending — they land with M2/M5.)*

Acceptance: app launches with the themed Conversation screen in both light and dark. ✅ builds clean (`assembleDebug`).

## M1 — Conversation screen (hub)
- [x] Top app bar (`lock` + "Assistant" + `cloud_off`; added a settings action that opens the config bottom sheet).
- [~] Message thread: assistant + user bubbles done (greeting + turn). User image thumbnail pending (no image state until M2/M3).
- [~] Input bar: camera button, text field, mic FAB rendered. Mic drives the voice loop; **text field is wired to inference** — typing reveals a Send button (and IME Send action) that runs a one-off text turn via `submitText`. Camera is still a placeholder snackbar (Camera screen = M2).
- [~] States: idle ✅, listening (waveform + pulse sheet) ✅. Loading-vision pending (no vision sub-model yet).
- [~] Speaking (in-bubble "speaking · captions" indicator) ✅; error → snackbar ✅.

Config (Kokoro download, system TTS data, voice selector, speed) moved into a Settings `ModalBottomSheet`; the model-download gate stays inline in the thread.

Acceptance: all states render to the mockup; a text turn round-trips through the model. *(visual states render; text/voice round-trip via the existing loop — full text-input path is M4.)*

## M2 — Camera screen
- [ ] CameraX live preview; close + flash; gallery + flip.
- [ ] Intent chips (Identify default; Read / Translate / Summarize).
- [ ] Shutter → captured state (frozen frame, Retake / Use, "Ask about this…", On-device pill).
- [ ] On Use → return to Conversation with image + intent, auto-run.

Acceptance: capture an image and hand it to the Conversation turn with the chosen intent.

## M3 — Multimodal engine
- [~] LiteRT-LM image-input API confirmed (0.13.1: `Content.ImageFile`/`AudioFile`, `Contents.of(...)`, `sendMessageAsync(Contents): Flow`). Wired directly in `VoiceAssistant.handleTurn` for the file-attach path; a standalone `MultimodalEngine` wrapper is still optional.
- [~] Vision/audio sub-models enabled via `EngineConfig(visionBackend, audioBackend, maxNumImages)`; they map in on first use. No separate `ensureVisionModelLoaded()` API exists in 0.13.1.
- [x] Media before text — `Contents.of(media, Content.Text(prompt))`.
- [ ] `InferenceConfig` + `configFor()` — visual budget + thinking per intent (still todo).
- [x] `runInference()` token stream — reuses the existing streaming/sentence-handoff path.
- **Video:** unsupported — the runtime has no video `Content` type (frame-sampling deferred).

Acceptance: an image **or audio** attachment + question produces a streamed on-device answer. ✅ (image/audio file path)

## M4 — Orchestration
- [ ] Wire inputs (voice via `SpeechRecognizer`, camera, text) → `TurnInput` → `buildPrompt` → `runInference`.
- [ ] Stream tokens to the transcript and feed sentences to `KokoroTts` (reuse handoff).
- [ ] Barge-in via VAD on the live bubble.

Acceptance: voice, text, and image turns all stream answer + speech; interrupt works.

## M5 — Persistence
- [x] `ConversationStore` (Room v3): `conversations` + `turns` tables (multi-conversation; v1→v2 backfills the old single thread). v2→v3 adds `mediaPath`/`mediaKind` so image/audio attachments persist and restore in the thread; `saveMedia` copies picked Uris into `filesDir/media/`.
- [x] Save each turn; restore on launch. In-memory `history` drives the UI (flicker-free finalize); Room is the durable backing.
- [x] **Multiple conversations** via navigation drawer: new chat, switch, delete; auto-title from first message; session reset on switch (`resetModelSession`). `clearHistory()` empties the active chat ("Clear conversation" in settings).
- [ ] **Restore model context on switch** — opening an old chat shows the transcript but doesn't replay it into the session (model has no memory of earlier turns). Folds into the windowed/summarized prompt work below.
- [ ] Windowed + summarized prompt assembly (not full history). *(Currently the full thread lives in the LiteRT-LM `Conversation`; prompt windowing/summary still todo.)*
- [ ] Optional: SQLCipher / encrypted store.

Acceptance: the conversation survives an app restart; the prompt stays bounded on long threads. *(Restart-persistence ✅; prompt bounding still pending.)*

Turns are finalized once **both** generation and speech have drained (`maybeFinalizeTurn`), so a row is written exactly once; barge-in persists the partial reply.

## M6 — Polish
- [ ] Offline / on-device indicator wired to connectivity (display only).
- [ ] Accessibility: content descriptions, touch targets, caption sync.
- [ ] Error handling + retry; first-load UX (model download, vision load).

## M7 — Demo validation
- [ ] Airplane-mode end-to-end: camera "what is this / read this" → spoken + captioned answer.
- [ ] Airplane-mode voice + text Q&A.
- [ ] Confirm nothing touches the network (no cloud calls anywhere).

Acceptance: the Definition of Done in `CLAUDE.md` passes on a real device.

## Notes / open questions
- LiteRT-LM image-input + session API: ✅ confirmed (0.13.1 — Text/Image/Audio `Content`, no video; `sendMessageAsync(Contents): Flow`).
- Native audio (E2B ASR) is a fast-follow to replace `SpeechRecognizer` — not in Phase 1.
- `conversation.html` ships idle / listening / loading-vision only; speaking + error states still need design.
- Decide a default visual token budget per intent and the summary cadence for long-thread prompt assembly.
