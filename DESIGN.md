# DESIGN.md — "Secure Tonal Interface"

Design tokens for the on-device voice assistant. This is the source of truth for the
Compose Material 3 theme (`ui/theme/`). The HTML mockups (`design/conversation.html`,
`design/cameras.html`) are visual references derived from these same tokens.

**Direction:** Material 3 (Material You), Deep Teal seed. Privacy / reliability / calm —
"local-first confidence," a safe-harbour feel. No high-energy motion, no cloud-sync metaphors.
Tonal elevation via `surfaceContainer` levels, not heavy shadows. Light + dark; dark uses deep
"ink" surfaces, never pure black.

## Color roles

### Light
| Role | Hex | Role | Hex |
| --- | --- | --- | --- |
| primary | `#004f52` | onPrimary | `#ffffff` |
| primaryContainer | `#00696d` | onPrimaryContainer | `#96e5ea` |
| secondary | `#4a6363` | onSecondary | `#ffffff` |
| secondaryContainer | `#cce8e7` | onSecondaryContainer | `#506969` |
| tertiary | `#334863` | onTertiary | `#ffffff` |
| tertiaryContainer | `#4b607c` | onTertiaryContainer | `#c5dbfb` |
| error | `#ba1a1a` | onError | `#ffffff` |
| errorContainer | `#ffdad6` | onErrorContainer | `#93000a` |
| background / surface | `#f7faf9` | onBackground / onSurface | `#181c1c` |
| surfaceVariant | `#e0e3e3` | onSurfaceVariant | `#3e4949` |
| outline | `#6f7979` | outlineVariant | `#bec9c9` |
| inverseSurface | `#2d3131` | inverseOnSurface | `#eef1f1` |
| inversePrimary | `#85d3d7` | surfaceTint | `#00696d` |
| surfaceContainerLowest | `#ffffff` | surfaceContainerLow | `#f1f4f4` |
| surfaceContainer | `#ebeeee` | surfaceContainerHigh | `#e6e9e8` |
| surfaceContainerHighest | `#e0e3e3` | surfaceDim | `#d7dbda` |

### Dark (deep ink, M3 Deep Teal dark tones)
| Role | Hex | Role | Hex |
| --- | --- | --- | --- |
| primary | `#85d3d7` | onPrimary | `#00363a` |
| primaryContainer | `#004f52` | onPrimaryContainer | `#a1f0f4` |
| secondary | `#b1cccb` | onSecondary | `#1b3534` |
| secondaryContainer | `#324b4b` | onSecondaryContainer | `#cce8e7` |
| tertiary | `#b2c8e8` | onTertiary | `#1b3148` |
| tertiaryContainer | `#334863` | onTertiaryContainer | `#d2e4ff` |
| error | `#ffb4ab` | onError | `#690005` |
| errorContainer | `#93000a` | onErrorContainer | `#ffdad6` |
| background / surface | `#181c1c` | onBackground / onSurface | `#e0e3e3` |
| surfaceVariant | `#3f4948` | onSurfaceVariant | `#bec9c9` |
| outline | `#899392` | outlineVariant | `#3f4948` |
| inverseSurface | `#e0e3e3` | inverseOnSurface | `#2d3131` |
| inversePrimary | `#00696d` | surfaceTint | `#85d3d7` |
| surfaceContainerLowest | `#0b0f0f` | surfaceContainerLow | `#181c1c` |
| surfaceContainer | `#1c2120` | surfaceContainerHigh | `#272b2b` |
| surfaceContainerHighest | `#313635` | surfaceDim | `#101414` |

## Typography — Roboto Flex (system Roboto; offline-safe, no downloadable font)
| Style | Size / line | Weight | Letter-spacing |
| --- | --- | --- | --- |
| displayLarge | 57 / 64 | 400 | -0.25 |
| headlineLarge | 32 / 40 | 400 | 0 |
| titleLarge | 22 / 28 | 400 | 0 |
| titleMedium | 16 / 24 | 500 | 0.15 |
| bodyLarge | 16 / 24 | 400 | 0.5 |
| bodyMedium | 14 / 20 | 400 | 0.25 |
| labelLarge | 14 / 20 | 500 | 0.1 |
| labelSmall | 11 / 16 | 500 | 0.5 |

## Shape (extra-large family)
| M3 token | Radius | Used for |
| --- | --- | --- |
| extraSmall | 4dp | bubble tail corner |
| small | 8dp | chips background, code blocks |
| medium | 12dp | image thumbnails |
| large | 20dp | message bubbles, text input |
| extraLarge | 28dp | cards, FABs, bottom sheets |

Buttons / chips / mic FAB: pill (`CircleShape` / fully rounded).

## Spacing & layout
8dp grid · 16dp side margins · message bubbles ≤ 85% width.

## Icons
Material Symbols Rounded (Compose `Icons.Rounded.*`). Pair privacy/status with `Lock` and
`CloudOff`. The private/offline affordance is reassurance only — it never disables features.
