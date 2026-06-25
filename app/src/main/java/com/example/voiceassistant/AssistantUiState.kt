package com.example.voiceassistant

data class AssistantUiState(
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
