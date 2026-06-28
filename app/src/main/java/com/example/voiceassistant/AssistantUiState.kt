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

/** One finalized, persisted turn shown in the conversation thread. */
data class Message(
    val id: Long = 0,
    val role: Role,
    val text: String,
    val attachment: Attachment? = null,
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
