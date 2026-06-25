package com.example.voiceassistant

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun AssistantScreen(viewModel: AssistantViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text("Voice Assistant") },
                actions = {
                    if (uiState.isModelAvailable) {
                        IconButton(onClick = { viewModel.checkModel() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh Engine")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Model/Download Status
            if (!uiState.isModelAvailable) {
                ModelDownloadCard(uiState, viewModel)
            }

            // TTS Voice Data Status & Configuration
            if (!uiState.isTtsVoiceDataAvailable) {
                TtsVoiceDataCard(viewModel, isMissing = true)
            }

            // Neural voice (Kokoro) download / status
            KokoroVoiceCard(uiState, viewModel)

            // Error Display
            if (uiState.error != null) {
                ErrorMessage(uiState.error!!)
            }

            // Main interaction area
            ResponseArea(
                label = "You said:",
                text = uiState.lastUserUtterance,
                placeholder = "Wait for \"Listening...\" then speak"
            )

            ResponseArea(
                label = "Assistant:",
                text = uiState.assistantResponse,
                placeholder = "..."
            )

            // Voice Selector Dropdown
            VoiceSelector(uiState, viewModel)

            // Speech speed slider (Kokoro)
            SpeedSelector(uiState, viewModel)

            Spacer(modifier = Modifier.weight(1f))

            // State Indicators (Pulsing Circle)
            InteractionStatus(uiState)

            // Control Buttons
            if (uiState.isModelAvailable && uiState.isPermissionGranted) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (uiState.isSpeaking) {
                        Button(
                            onClick = { viewModel.interrupt() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Interrupt / Speak Now")
                        }
                    }

                    Button(
                        onClick = {
                            if (uiState.isActive) viewModel.stopAssistant()
                            else viewModel.startAssistant()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (uiState.isActive) ButtonDefaults.outlinedButtonColors() else ButtonDefaults.buttonColors()
                    ) {
                        Text(if (uiState.isActive) "Stop Assistant Loop" else "Start Assistant Loop")
                    }
                }
            }
        }
    }
}

@Composable
fun InteractionStatus(uiState: AssistantUiState) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulsing")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (uiState.isListening) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
            )
            Text(
                "Listening...",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )
        } else if (uiState.isSpeaking) {
            CircularProgressIndicator(modifier = Modifier.size(48.dp))
            Text(
                "Speaking...",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
fun ResponseArea(label: String, text: String, placeholder: String) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Text(
                text = text.ifEmpty { placeholder },
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Start
            )
        }
    }
}

@Composable
fun ModelDownloadCard(uiState: AssistantUiState, viewModel: AssistantViewModel) {
    var hfToken by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Gemma 4 E2B Model Needed",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Enter your Hugging Face token to download, or push manually:",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )

            if (!uiState.isDownloading) {
                OutlinedTextField(
                    value = hfToken,
                    onValueChange = { hfToken = it },
                    label = { Text("Hugging Face Token (optional if public)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall
                )
            }
            
            Surface(
                color = Color.Black.copy(alpha = 0.1f),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = "adb push model.litertlm /data/user/0/com.example.voiceassistant/files/",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(8.dp),
                    textAlign = TextAlign.Center
                )
            }

            if (uiState.isDownloading) {
                LinearProgressIndicator(
                    progress = { uiState.downloadProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.downloadModel(hfToken) },
                        enabled = true
                    ) {
                        Text("Download")
                    }
                    OutlinedButton(onClick = { viewModel.checkModel() }) {
                        Text("Check File")
                    }
                }
            }
        }
    }
}

@Composable
fun ErrorMessage(message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun TtsVoiceDataCard(viewModel: AssistantViewModel, isMissing: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isMissing) MaterialTheme.colorScheme.errorContainer 
                             else MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (isMissing) "High-Quality Voice Data Missing" else "Optimize Voice Naturalness",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isMissing) MaterialTheme.colorScheme.onErrorContainer 
                        else MaterialTheme.colorScheme.onTertiaryContainer
            )
            Text(
                text = if (isMissing) 
                    "TTS voice data for your language is not installed on this device. Install it for the voice assistant to speak."
                    else "To make the assistant sound fully human, ensure high-quality/neural voice data is installed in your device's system settings.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = if (isMissing) MaterialTheme.colorScheme.onErrorContainer 
                        else MaterialTheme.colorScheme.onTertiaryContainer
            )
            Button(
                onClick = { viewModel.installVoiceData() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isMissing) MaterialTheme.colorScheme.error 
                                     else MaterialTheme.colorScheme.tertiary
                )
            ) {
                Text("Install / Configure Speech Voices")
            }
        }
    }
}

@Composable
fun KokoroVoiceCard(uiState: AssistantUiState, viewModel: AssistantViewModel) {
    val active = uiState.isKokoroAvailable
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.tertiaryContainer
                             else MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Neural Voice (Kokoro)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            when {
                active -> Text(
                    text = "Active — high-quality offline neural voice with ${uiState.availableVoices.size} speakers. " +
                        "Pick one in the selector below.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
                uiState.isKokoroDownloading -> {
                    Text(
                        text = "Downloading & extracting voice model…",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                    LinearProgressIndicator(
                        progress = { uiState.kokoroDownloadProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                else -> {
                    Text(
                        text = "Replace the robotic system voice with a natural neural voice. " +
                            "~370 MB one-time download, then runs fully offline.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                    Button(onClick = { viewModel.downloadKokoro() }) {
                        Text("Download Neural Voice")
                    }
                }
            }
        }
    }
}

@Composable
fun SpeedSelector(uiState: AssistantUiState, viewModel: AssistantViewModel) {
    if (!uiState.isKokoroAvailable) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Voice Speed",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = String.format("%.2fx", uiState.kokoroSpeed),
                    style = MaterialTheme.typography.titleSmall
                )
            }
            Slider(
                value = uiState.kokoroSpeed,
                onValueChange = { viewModel.setSpeechSpeed(it) },
                valueRange = 0.5f..2.5f
            )
            Text(
                text = "Takes effect on the next spoken sentence.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun VoiceSelector(uiState: AssistantUiState, viewModel: AssistantViewModel) {
    if (uiState.availableVoices.isNotEmpty()) {
        var expanded by remember { mutableStateOf(false) }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Speech Voice Selector",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { expanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = uiState.selectedVoiceName.ifEmpty { "Select a voice..." },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Show Voices"
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.fillMaxWidth(0.9f)
                    ) {
                        uiState.availableVoices.forEach { voiceName ->
                            val isSelected = voiceName == uiState.selectedVoiceName
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = voiceName,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                },
                                onClick = {
                                    viewModel.selectVoice(voiceName)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
