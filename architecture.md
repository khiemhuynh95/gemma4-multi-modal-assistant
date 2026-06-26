# Architecture

On-device multimodal assistant — Phase 1. Source of truth for components, data flow, contracts, and persistence. Update whenever any of those change (see `CLAUDE.md` → "Keeping docs in sync").

## 1. Where Phase 1 sits

The full product is a two-tier cascade: a small on-device model (Gemma 4 E2B) for the easy, private, offline majority, and a larger backend model (Gemma 4 12B) for the hard minority — connected over a private Tailscale / Headscale mesh, never cloud. **Phase 1 builds only the on-device tier.** The backend, the router's "escalate" path, RAG, tools, and the distillation loop are all deferred (see Roadmap).

On-device, every request is answered by E2B from perception plus its own weights. A "simple task" is whatever E2B can answer without external knowledge or actions — that set is exactly what the future router will keep local, which is why nailing it now pays off twice.

## 2. On-device request flow

```
Voice / Camera / Text
   → assemble prompt (media before text)
   → E2B inference (config: visual token budget + thinking on/off)
   → stream tokens
   → text-to-speech (Kokoro) + live transcript
```

1. **Input** — voice via `SpeechRecognizer`, image via CameraX, or typed text. An `Intent` (Identify / Read / Translate / Summarize / Describe / Chat) is attached, chosen by a camera chip or defaulted.
2. **Assemble** — `buildPrompt` orders image content before the instruction text, with a system prompt selected by intent.
3. **Infer** — `runInference` runs E2B through LiteRT-LM with an `InferenceConfig` derived from the intent (`configFor`): low visual budget + thinking off for simple turns, more for heavier ones. Loads the vision sub-model on demand first.
4. **Stream** — tokens update the transcript and feed completed sentences to TTS as they arrive (reuse the existing handoff).
5. **Persist** — each turn is written to the local `ConversationStore`.

## 3. Components

| Component | File | Responsibility |
| --- | --- | --- |
| Orchestrator | `VoiceAssistant.kt` | Owns the turn lifecycle: STT → prompt → inference → TTS. |
| Multimodal engine | `MultimodalEngine` (new) | Wraps the LiteRT-LM E2B session; image + text in, token stream out; on-demand vision load. |
| ViewModel | `AssistantViewModel.kt` | UI state, model download, image state, persistence wiring. |
| Text-to-speech | `KokoroTts.kt` | Offline neural TTS; streaming sentence playback. |
| Camera | `CameraScreen.kt` + controller (new) | CameraX preview, capture, intent selection. |
| Persistence | `data/ConversationStore.kt` (Room) | Local-first storage of **multiple conversations** (`conversations` + `turns` tables, text turns). Image references + summary/window are M5. |
| UI | `AssistantScreen.kt` (Conversation), `CameraScreen.kt` | Compose screens — see `ui-context.md`. |

## 4. Core contracts

Designed to drop into the existing orchestrator (most logic in `VoiceAssistant.kt` or a new `MultimodalEngine.kt`; UI state in `AssistantViewModel.kt`).

```kotlin
// What the user wants from this turn — drives the system prompt + config.
enum class Intent { Chat, Identify, ReadText, Translate, Summarize, Describe }

// The on-device "don't overthink it" dial.
data class InferenceConfig(
    val visualTokenBudget: Int,   // 70 / 140 / 280 / 560 / 1120 — low = fast
    val thinking: Boolean,        // off for simple, on for reasoning
    val maxTokens: Int = 512,
)

// Identify / ReadText / Translate -> budget 140, thinking off (fast)
// Summarize / Describe            -> budget 560, thinking on
// Chat (no image)                 -> thinking off unless the query looks complex
fun configFor(intent: Intent, hasImage: Boolean): InferenceConfig

// One turn's raw inputs.
data class TurnInput(
    val image: Bitmap? = null,
    val spokenText: String? = null,   // SpeechRecognizer output (v1)
    val typedText: String? = null,
    val intent: Intent = Intent.Chat,
)

fun buildPrompt(input: TurnInput): ModelPrompt                                // image BEFORE text
fun runInference(prompt: ModelPrompt, config: InferenceConfig): Flow<String>  // token stream
suspend fun ensureVisionModelLoaded(): Result<Unit>                          // lazy, one-time

// UI state exposed to Compose.
data class AssistantUiState(
    val messages: List<Message>,
    val status: Status,          // Idle, Listening, Loading, Thinking, Speaking, Error
    val pendingImage: Bitmap?,   // captured, awaiting send
    val isOffline: Boolean,
    val activeIntent: Intent,
)

// Local-first persistence.
interface ConversationStore {
    fun observeTurns(): Flow<List<Turn>>
    suspend fun append(turn: Turn): Long
    suspend fun saveImage(bitmap: Bitmap): String        // returns app-private URI/path
    suspend fun recentWindow(limit: Int): List<Turn>     // for prompt assembly
    suspend fun summaryBefore(turnId: Long): String?     // running summary of older turns
}
```

Behavioural notes that aren't obvious from the signatures:
- `runInference` calls `ensureVisionModelLoaded()` whenever the prompt carries an image, flips status to `Loading` for that first map-in, then streams.
- `configFor` is the whole mini-router living on-device.
- `runInference` wraps the LiteRT-LM session's generate call (image + text + config). **Confirm the exact LiteRT-LM image-input / session API against the runtime version in use before implementing** — do not assume signatures.

## 5. Data & persistence

- The **raw conversations** are persisted locally in Room (DB version 2). Tables: `conversations` (`id`, `title`, `createdAt`, `updatedAt`) and `turns` (`id`, `conversationId`, `role` `"user"`/`"assistant"`, `text`, `createdAt`). `imageRef?` / `intent` columns and a `derived_memory` table are deferred to M5/M3. A v1→v2 migration backfills the old single thread into one conversation.
- **Multiple conversations:** the user manages chats from a navigation drawer (new / switch / delete). `VoiceAssistant` tracks `activeConversationId`; the most-recent chat is opened on launch (an empty one is created if none exist). A chat is auto-titled from its first user message; the drawer is ordered by `updatedAt`.
- **Session isolation:** switching or starting a chat recreates the LiteRT-LM `Conversation` (`resetModelSession`) so context doesn't bleed between chats. **Known gap:** opening an existing chat shows its full transcript but does *not* replay it into the model session, so the model has no memory of earlier turns on continuation — restoring context is part of the deferred windowed + summarized prompt assembly (M5).
- **Turn lifecycle:** `VoiceAssistant` keeps an in-memory `history` (the synchronous source for `AssistantUiState.messages`, so finalizing a turn is flicker-free) and writes through to `ConversationStore` for durability; history is restored from Room when a conversation is activated. A turn is persisted to the conversation it started in, exactly once — when both LLM generation and TTS playback have drained (`maybeFinalizeTurn`).
- **Captured images** are saved to app-private storage; the DB stores the path/URI, never the bytes.
- **At rest:** app-private storage is already sandboxed per app; optional SQLCipher or an encrypted DataStore for belt-and-suspenders on a privacy demo.
- **Stored history ≠ prompt.** Each turn feeds the model a recent window plus a running summary, not the full log. This keeps E2B's KV cache and latency bounded on long threads — summarize older turns rather than replaying them.

Separation to hold to as the product grows: the raw thread (episodic, verbatim, what the user scrolls) stays local-first forever; only a derived semantic layer (preferences, durable facts, summaries) ever syncs — and only to the private shared store once the backend exists, never to cloud.

## 6. Model & inference notes — Gemma 4 E2B via LiteRT-LM

- Native multimodal: text, image, audio, video. Native thinking mode (configurable). Native audio (ASR).
- Order: image content before the text in the prompt.
- Visual token budget: 70 / 140 / 280 / 560 / 1120 — lower is faster with less detail. Default low (140) for identify/read; raise for fine detail (small text, charts).
- Thinking: off for simple turns (latency), on for reasoning. This is the latency dial.
- Memory: text weights ~1.3–1.5 GB; vision/audio load on demand. Target devices ≥ 6 GB RAM. Expect roughly 10–25 tok/s on 2024–25 flagships, so stream output.

## 7. Roadmap — deferred, do not build in Phase 1

- **Phase 2:** backend tier — Gemma 4 12B served by LM Studio (dev) / vLLM (scale); the router's "escalate" path; SSE streaming between client and backend.
- **Transport:** Tailscale mesh (Headscale for full self-hosting). Clients and backend are peer nodes on a private tailnet — never cloud. Multi-client introduces multi-tenancy: per-client identity and memory isolation, request scheduling, and externalized shared state.
- **Phase 3+:** multimodal RAG over local documents, tools / MCP to on-prem systems, a multi-tenant agent-OS kernel (identity / scheduler / guardrails), a shared externalized memory store, and a distillation flywheel (backend traces → fine-tune E2B so the local model handles more over time).

Keep Phase 1 free of any of these dependencies.
