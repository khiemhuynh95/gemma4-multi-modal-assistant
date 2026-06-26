# UI context

Design reference for the Compose UI. Source of truth for screens, states, components, and theme mapping. Update whenever any screen, state, or token changes (see `CLAUDE.md` → "Keeping docs in sync").

## Design system

Tokens live in `DESIGN.md` ("Secure Tonal Interface") and are implemented as a Compose Material 3
theme in `ui/theme/` (`Color.kt`, `Type.kt`, `Shape.kt`, `Theme.kt` → `VoiceAssistantTheme`). Dynamic
color is intentionally off (the teal brand identity is part of the local-first feel). Fonts use system
Roboto (offline-safe) and `material-icons-extended` (`Icons.Rounded.*`) for Material Symbols Rounded.
Summary:

- **Direction:** Material 3 (Material You), Deep Teal seed. Privacy / reliability / calm — "local-first confidence," a safe-harbor feel. No high-energy motion or cloud-sync metaphors.
- **Color:** primary `#004f52`, primary-container `#00696d`, secondary-container `#cce8e7`, tertiary (`#334863`) for privacy accents. Tonal elevation via surface-container levels, not heavy shadows. Light + dark (dark uses deep "ink" surfaces, not pure black).
- **Type:** Roboto Flex, M3 scale (display / headline / title / body / label). `headline-lg` → `headline-lg-mobile` on phones.
- **Shape:** extra-large. Cards / FABs / sheets 28px; bubbles / inputs 20px; chips / buttons pill.
- **Spacing:** 8dp grid; 16px side margins; bubbles ≤ 85% width.
- **Icons:** Material Symbols Rounded. Pair privacy/status with `lock` and `cloud_off`.

Implement as a Compose Material 3 theme: build `ColorScheme` (light + dark) from the DESIGN.md roles, `Typography` from the Roboto Flex scale, `Shapes` from the radii. The HTML mockups (`conversation.html`, `cameras.html`) are visual references, **not** the implementation.

## Screen 1 — Conversation (`AssistantScreen.kt`)

Reference: `conversation.html`. The hub for voice / text / image turns and responses.

Components:
- **Navigation drawer (conversations):** opened by a leading hamburger (`menu`) in the top app bar. A `ModalNavigationDrawer` listing all chats (most-recent first) with a "New chat" item at top; each row shows the auto-generated title, highlights the active chat, and has a trailing delete. Tapping a row switches chats (closes the drawer). New/switch resets the model session; see the "Session isolation" gap in `architecture.md` §5.
- **Top app bar:** leading `menu` (opens drawer); `lock` (primary) + "Assistant" (title-lg); trailing `cloud_off` (on-surface-variant — the always-visible private / offline affordance) + `settings`.
- **Message thread:** assistant bubbles = secondary-container / on-secondary-container, 20px corners with a top-left tail; user bubbles = primary / on-primary, top-right tail, with an optional captured-image thumbnail (rounded, bordered) above the text.
- **Input bar** (surface-container): leading camera icon button; outlined text field ("Type or say something…", 28px radius, min 56dp); trailing mic FAB (primary-container, 56dp, 28px radius).

Config (Kokoro neural-voice download, system TTS-data install, voice selector, speed) lives in a
**Settings `ModalBottomSheet`** opened from a settings action in the top app bar — it is not part of
the thread. The model-download gate (`ModelDownloadCard`) stays inline at the top of the thread until
the model is present.

States (implemented in `AssistantScreen.kt`):
- **(a) Idle** ✅ — input bar enabled, mic ready.
- **(b) Listening** ✅ — input bar is replaced by a bottom sheet (`surfaceContainerHigh`, 28dp top corners): animated waveform bars, "Listening…", a large pulsing mic button (tap to stop). Auto-send on silence (VAD).
- **(c) Loading vision** — *pending*; arrives with the vision sub-model (M3). Spinner bubble + disabled input.
- **Speaking** ✅ — assistant bubble shows a `GraphicEq` + "speaking · captions" indicator; captions stream with TTS.
- **Error** ✅ — surfaced as a `Snackbar` (driven by `uiState.error`).

Mic button maps to the existing voice loop: idle → start; active → stop; speaking → barge-in/interrupt.
Typing reveals a **Send** button in place of the mic (and the keyboard's Send action) that runs a
one-off text turn via `submitText`. Camera button is currently a placeholder snackbar (Camera screen = M2).

The thread renders the persisted history (`uiState.messages` from `ConversationStore`) followed by the
live streaming reply (`assistantResponse`). The opening greeting shows only on an empty thread.
**Settings** sheet has a "Clear conversation" action (`clearHistory`) that wipes memory + storage.

Interactions: hold-to-talk → auto-send on silence; tap camera → Camera screen (pending); type + send (pending); tap mic while speaking to barge-in / stop (existing VAD interrupt).

## Screen 2 — Camera capture (`CameraScreen.kt`)

Reference: `cameras.html`. Dark, full-bleed.

Components and states:
- **Live preview:** full-screen CameraX feed; top overlay with close (left) + flash toggle (right); bottom overlay with intent filter chips (Identify selected = primary-container; Read / Translate / Summarize outlined), a large shutter (white ring), and gallery + flip-camera side buttons.
- **Captured:** frozen frame with a slight dark overlay; top bar shows close + an "On-device" pill (`lock` + label); a bottom sheet (surface-container-high) with an "Ask about this…" input (rounded, send arrow) and two actions — Retake (outlined) and Use (primary filled). On "Use" → return to Conversation with the image + chosen intent and auto-run the turn.

Principle: **one tap.** The default intent (Identify) means point-and-capture just works; chips only refine. Intents map to the system prompt and visual token budget in `configFor` (see `architecture.md`).

## Behaviour notes

- Captions reveal in lockstep with TTS playback (existing streaming behaviour).
- The private / offline affordance (`lock` + `cloud_off`, or the "On-device" pill) appears on both screens and is reassurance only — it never disables features.
- Both screens must work in light and dark; the mockups ship dark-first, so verify light explicitly.
