package com.example.voiceassistant

enum class Role { USER, ASSISTANT }

/** The media kinds the on-device runtime accepts (LiteRT-LM has no video Content type). */
enum class AttachmentKind { IMAGE, AUDIO }

/**
 * A media file attached to a user turn. [path] is an app-private file (never a `content://` Uri and
 * never bytes in the DB — see `architecture.md` §5) that the runtime reads via
 * `Content.ImageFile` / `Content.AudioFile`.
 */
data class Attachment(
    val path: String,
    val kind: AttachmentKind,
)

/**
 * Per-turn observability shown under an assistant reply: which tools/skills the model invoked, how
 * long the turn took, and token usage (from LiteRT-LM's `BenchmarkInfo`). Transient — not persisted,
 * so it appears for turns generated this session, not reloaded history.
 */
data class TurnMetrics(
    val toolsInvoked: List<String> = emptyList(),
    val latencyMs: Long = 0,
    val ttftSec: Double = 0.0,        // time to first token
    val promptTokens: Int = 0,        // prefill (input) tokens
    val outputTokens: Int = 0,        // decode (generated) tokens
    val decodeTokensPerSec: Double = 0.0,
)

/** One finalized, persisted turn shown in the conversation thread. */
data class Message(
    val id: Long = 0,
    val role: Role,
    val text: String,
    val attachment: Attachment? = null,
    val metrics: TurnMetrics? = null,
)

/** A conversation entry for the navigation drawer. */
data class ConversationSummary(
    val id: Long,
    val title: String,
)

data class AssistantUiState(
    // All conversations (most-recent first) and which one is open — drives the drawer.
    val conversations: List<ConversationSummary> = emptyList(),
    val activeConversationId: Long = 0L,
    // Finalized history of the active conversation (restored from ConversationStore on launch).
    val messages: List<Message> = emptyList(),
    // Media selected by the user, copied to app-private storage, awaiting send with the next turn.
    val pendingAttachment: Attachment? = null,
    val lastUserUtterance: String = "",
    val assistantResponse: String = "",
    // Short label of the on-device skill/tool the model just invoked this turn (e.g. "Flashlight"),
    // or null when no tool ran. Cleared when the turn finalizes. Drives the tool-status chip.
    val activeTool: String? = null,
    // User-defined markdown "instruction" skills (managed on the Skills screen). Built-in tools are
    // compiled and listed read-only from BuiltInSkills.ALL.
    val instructionSkills: List<com.example.voiceassistant.tools.InstructionSkill> = emptyList(),
    // Configured remote MCP servers + their last discovery status (tool count / error).
    val mcpServers: List<com.example.voiceassistant.tools.McpServerInfo> = emptyList(),
    val isSpeaking: Boolean = false,
    val isListening: Boolean = false,
    val isActive: Boolean = false,
    val error: String? = null,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val isModelAvailable: Boolean = false,
    val isPermissionGranted: Boolean = false,
    val isTtsVoiceDataAvailable: Boolean = true,
    val availableVoices: List<String> = emptyList(),
    val selectedVoiceName: String = "",
    val ttsEngine: String = "System",
    val isKokoroAvailable: Boolean = false,
    val isKokoroDownloading: Boolean = false,
    val kokoroDownloadProgress: Float = 0f,
    val kokoroSpeed: Float = 1.5f
)
