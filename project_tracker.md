# Project tracker

Phase 1 — on-device multimodal showcase. Keep this current: tick items as done, add discovered work, note blockers (see `CLAUDE.md` → "Keeping docs in sync").

Status legend: `[ ]` todo · `[~]` in progress · `[x]` done · `[!]` blocked

Current status: **M0 done; M1 UI shell in place (visual states wired; text/image/vision turns pending engine work).**

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
- [ ] `MultimodalEngine` wrapping the LiteRT-LM E2B session (confirm image-input API first).
- [ ] `ensureVisionModelLoaded()` — lazy, one-time; drives the loading-vision state.
- [ ] `buildPrompt()` — media before text.
- [ ] `InferenceConfig` + `configFor()` — visual budget + thinking per intent.
- [ ] `runInference()` — token stream.

Acceptance: an image + question produces a streamed on-device answer.

## M4 — Orchestration
- [ ] Wire inputs (voice via `SpeechRecognizer`, camera, text) → `TurnInput` → `buildPrompt` → `runInference`.
- [ ] Stream tokens to the transcript and feed sentences to `KokoroTts` (reuse handoff).
- [ ] Barge-in via VAD on the live bubble.

Acceptance: voice, text, and image turns all stream answer + speech; interrupt works.

## M5 — Persistence
- [~] `ConversationStore` (Room v2): `conversations` + `turns` tables done for **text turns** (multi-conversation; v1→v2 migration backfills the old single thread). Image-ref handling still todo (lands with M2/M3 images).
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
- LiteRT-LM image-input + session API: confirm against the runtime version before M3.
- Native audio (E2B ASR) is a fast-follow to replace `SpeechRecognizer` — not in Phase 1.
- `conversation.html` ships idle / listening / loading-vision only; speaking + error states still need design.
- Decide a default visual token budget per intent and the summary cadence for long-thread prompt assembly.
