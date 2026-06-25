# gemma4-multi-modal-assistant

A fully on-device voice assistant for Android. It listens to your speech, reasons
with a locally-run Gemma model, and talks back with a natural neural voice — no
request ever leaves the phone once the models are downloaded.

## Features

- **On-device LLM** — runs `gemma-4-E2B-it` via Google's
  [LiteRT-LM](https://github.com/google-ai-edge/litert) runtime, with automatic
  GPU acceleration and CPU fallback.
- **Speech-to-text** — Android's built-in `SpeechRecognizer`.
- **Neural text-to-speech** — offline [Kokoro](https://github.com/k2-fsa/sherpa-onnx)
  voices via sherpa-onnx, with graceful fallback to the system TTS engine.
- **Streaming, low-latency replies** — sentences are spoken as soon as the model
  produces them, and the on-screen transcript is revealed in lockstep with the voice.
- **Barge-in / interrupt** support and live speech-rate control.
- Built with **Jetpack Compose**.

## Requirements

- Android Studio (recent stable release)
- Android device/emulator on **API 28+** (`minSdk 28`, `targetSdk 35`)
- A [Hugging Face](https://huggingface.co/) access token to download the Gemma model
  on first launch

## Getting started

1. Clone the repo and open it in Android Studio.
2. Let Gradle sync, then run the `app` configuration on a device or emulator.
3. On first launch, paste your Hugging Face token to download the Gemma model
   (`gemma-4-E2B-it.litertlm`). Optionally download the Kokoro neural voice for
   higher-quality TTS.
4. Grant the microphone permission, tap **Start**, and speak.

The Gemma and Kokoro models are downloaded at runtime into app storage and are
**not** bundled in the repo. The lightweight `silero_vad.onnx` VAD model ships in
`app/src/main/assets/`.

## Permissions

- `RECORD_AUDIO` — speech recognition
- `INTERNET` — one-time model downloads

## Project layout

| Path | Description |
| --- | --- |
| `app/src/main/java/.../VoiceAssistant.kt` | Core orchestration: STT → LLM → TTS pipeline |
| `app/src/main/java/.../AssistantViewModel.kt` | UI state + model download management |
| `app/src/main/java/.../KokoroTts.kt` | Offline neural TTS wrapper (sherpa-onnx) |
| `app/src/main/java/.../AssistantScreen.kt` | Jetpack Compose UI |
| `app/libs/sherpa-onnx-*.aar` | Bundled sherpa-onnx native library |

## License

See repository for license details.
