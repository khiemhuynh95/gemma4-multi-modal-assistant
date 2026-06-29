# Code standards

A short reminder, not a rulebook: keep the codebase clean, reusable, and modular. When you establish a new convention or pattern, record it here (see `CLAUDE.md` → "Keeping docs in sync").

## Clean
- Small, single-purpose functions and composables. If something does two things, split it.
- Names state intent (`ensureVisionModelLoaded`, not `loadStuff`). No abbreviations that need a comment to decode.
- No magic numbers or strings — pull visual token budgets, model names, timeouts, and system prompts into named constants or config.
- Delete dead code and commented-out blocks; git remembers them.
- A brief KDoc on anything non-obvious (the LiteRT-LM session lifecycle, the prompt-windowing rule); nothing on the obvious.
- Lint must pass before a commit (ktlint / detekt, whatever the project settles on).

## Reusable
- Don't repeat yourself. The second time you copy a block, extract it.
- Prefer pure functions for logic that doesn't touch Android (`buildPrompt`, `configFor`, summary windowing) — they're trivial to reuse and to test.
- Pass behaviour in via parameters instead of hardcoding it: a composable takes its state and callbacks; the engine takes its `InferenceConfig`.
- One source of truth per concept. Intent → config lives only in `configFor`; design tokens live only in the theme. Don't scatter the same decision across call sites.

## Modular
- Respect the layers: UI (Compose) ← ViewModel (state) ← orchestrator / engine ← data (store). Dependencies point one direction; UI never calls the model or DB directly.
- Program to interfaces for anything swappable. `ConversationStore` is already an interface — keep persistence behind it. Wrap the model runtime behind `MultimodalEngine` so LiteRT-LM can be replaced without touching the UI or ViewModel.
- Single responsibility per class/file: the orchestrator coordinates, the engine infers, the store persists, composables render. Keep `VoiceAssistant.kt` thin — it wires the pieces together; it doesn't hold prompt logic, persistence, or UI.
- **Tools (agent skills):** one `ToolSet` per cohesive group of skills in `tools/AssistantTools.kt`, grouped by capability and side-effect profile (on-device device actions / read-only info / network). A `@Tool` method does one thing, returns a JSON-serializable map (`ok(...)` / `fail(...)` helpers), and never throws across the LiteRT boundary — network/IO skills must fail soft. Inject collaborators (`Context`, the `OnToolUsed` callback) via the constructor; `VoiceAssistant` only registers them in `conversationConfig()`. Adding a built-in skill should mean adding a method, not editing the orchestrator.
- **Guard native constructors with completeness checks.** A native lib (sherpa-onnx, LiteRT-LM) can return an object with a null handle when given an incomplete/corrupt model; the next JNI call then SIGSEGVs — a crash Kotlin `try/catch` cannot catch. Before constructing, verify every required model file exists and is non-empty (e.g. `KokoroTts.isModelComplete`), and use the *same* check to decide whether a download is already present (so a partial install re-downloads rather than crash-looping).
- **Two kinds of skill, kept distinct.** *Built-in tools* are compiled `@Tool` methods the model calls (executable). *Instruction skills* (`tools/InstructionSkill.kt`, persisted as `InstructionSkillEntity`) are user-authored markdown injected into the system prompt — behaviour, not code, and they make no calls. Map entity↔model in one place (`InstructionSkill.toEntity`/`fromEntity`); keep persistence, model, and UI (`SkillsScreen.kt`) separate.
- **MCP tools are remote, data-driven tools.** An MCP server's tools are discovered at runtime and each is wrapped as an `OpenApiTool` (`McpServerTool`) whose `parameters` is the server's `inputSchema` and whose `execute` runs `tools/call`. Keep the transport client (`McpConnection`), the model, and the UI separate; discovery and calls are network + fail-soft (return an error string, never throw across the LiteRT boundary). On Android, only the HTTP transport exists — never assume stdio.
- **Intent-based tools: declare visibility + permissions.** Any tool that launches another app via an implicit `Intent` needs a matching `<queries>` entry (Android 11+ hides other packages otherwise — `resolveActivity` returns null) and any action-specific permission (e.g. alarms need `com.android.alarm.permission.SET_ALARM`, not `android.permission.SET_ALARM`). Route these through the shared `launchIntent(...)` helper so resolution, logging, and fail-soft behaviour stay consistent.
- Inject dependencies (constructor params / a DI setup) rather than constructing collaborators inside a class or reaching for singletons — it keeps things decoupled and testable.
- Composables: small and stateless where possible; hoist state to the ViewModel; a screen is a few focused composables, not one giant function.

## The bar
Could another part of the app reuse this without copy-paste? Could you swap the model runtime or the storage backend without editing the UI? Could you unit-test the logic without an emulator? If all three are "yes," it's clean, reusable, and modular enough.
