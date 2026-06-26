package com.example.voiceassistant

enum class Role { USER, ASSISTANT }

/** One finalized, persisted turn shown in the conversation thread. */
data class Message(
    val id: Long = 0,
    val role: Role,
    val text: String,
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
