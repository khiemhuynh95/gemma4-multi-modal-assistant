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
- Inject dependencies (constructor params / a DI setup) rather than constructing collaborators inside a class or reaching for singletons — it keeps things decoupled and testable.
- Composables: small and stateless where possible; hoist state to the ViewModel; a screen is a few focused composables, not one giant function.

## The bar
Could another part of the app reuse this without copy-paste? Could you swap the model runtime or the storage backend without editing the UI? Could you unit-test the logic without an emulator? If all three are "yes," it's clean, reusable, and modular enough.
