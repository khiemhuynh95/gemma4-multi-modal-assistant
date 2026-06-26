package com.example.voiceassistant

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private val BubbleMaxWidth = 280.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(viewModel: AssistantViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }

    // Surface transient errors as a snackbar rather than a persistent card (see ui-context.md).
    LaunchedEffect(uiState.error) {
        uiState.error?.let { snackbarHostState.showSnackbar(it) }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ConversationDrawer(
                uiState = uiState,
                onNewChat = {
                    scope.launch { drawerState.close() }
                    viewModel.newConversation()
                },
                onSelectConversation = { id ->
                    scope.launch { drawerState.close() }
                    viewModel.switchConversation(id)
                },
                onDeleteConversation = { id -> viewModel.deleteConversation(id) }
            )
        }
    ) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AssistantTopBar(
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onOpenSettings = { showSettings = true }
            )
        },
        bottomBar = {
            // The listening state replaces the input bar with a bottom sheet overlay.
            if (!uiState.isListening) {
                InputBar(
                    uiState = uiState,
                    onMicClick = {
                        when {
                            uiState.isSpeaking -> viewModel.interrupt()
                            uiState.isActive -> viewModel.stopAssistant()
                            else -> viewModel.startAssistant()
                        }
                    },
                    onSendText = { viewModel.submitText(it) },
                    onCameraClick = {
                        scope.launch {
                            snackbarHostState.showSnackbar("Camera capture is coming soon.")
                        }
                    }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            MessageThread(
                uiState = uiState,
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize()
            )

            if (uiState.isListening) {
                ListeningSheet(
                    onStop = { viewModel.stopAssistant() },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
    }

    if (showSettings) {
        SettingsSheet(
            uiState = uiState,
            viewModel = viewModel,
            onDismiss = { showSettings = false }
        )
    }
}

@Composable
private fun ConversationDrawer(
    uiState: AssistantUiState,
    onNewChat: () -> Unit,
    onSelectConversation: (Long) -> Unit,
    onDeleteConversation: (Long) -> Unit,
) {
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(modifier = Modifier.fillMaxHeight()) {
            Text(
                text = "Conversations",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 28.dp, top = 24.dp, bottom = 8.dp)
            )
            NavigationDrawerItem(
                label = { Text("New chat") },
                selected = false,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                onClick = onNewChat,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(uiState.conversations, key = { it.id }) { convo ->
                    NavigationDrawerItem(
                        label = {
                            Text(
                                text = convo.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        selected = convo.id == uiState.activeConversationId,
                        icon = { Icon(Icons.Rounded.ChatBubbleOutline, contentDescription = null) },
                        badge = {
                            IconButton(onClick = { onDeleteConversation(convo.id) }) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = "Delete conversation",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = { onSelectConversation(convo.id) },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssistantTopBar(onOpenDrawer: () -> Unit, onOpenSettings: () -> Unit) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        ),
        navigationIcon = {
            IconButton(onClick = onOpenDrawer) {
                Icon(
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = "Conversations",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Assistant",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        actions = {
            // On-device / offline affordance — reassurance only, never gates features.
            Icon(
                imageVector = Icons.Rounded.CloudOff,
                contentDescription = "On-device · offline",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp)
            )
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

@Composable
private fun MessageThread(
    uiState: AssistantUiState,
    viewModel: AssistantViewModel,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    // Keep the latest turn in view as history grows and the live reply streams in.
    LaunchedEffect(uiState.messages.size, uiState.assistantResponse) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Hard gate: the model must be present before any turn can run.
        if (!uiState.isModelAvailable) {
            ModelDownloadCard(uiState, viewModel)
        }

        // Opening line only on a fresh, empty thread.
        if (uiState.messages.isEmpty() && uiState.assistantResponse.isEmpty()) {
            AssistantBubble(text = "How can I help you today?")
        }

        // Persisted conversation history.
        uiState.messages.forEach { message ->
            when (message.role) {
                Role.USER -> UserBubble(text = message.text)
                Role.ASSISTANT -> AssistantBubble(text = message.text)
            }
        }

        // Live, in-flight assistant reply (thinking placeholder → lockstep captions).
        if (uiState.assistantResponse.isNotEmpty()) {
            AssistantBubble(
                text = uiState.assistantResponse,
                speaking = uiState.isSpeaking
            )
        }

        // Leave room so the last bubble clears the input bar / listening sheet.
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AssistantBubble(text: String, speaking: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp),
            tonalElevation = 1.dp,
            modifier = Modifier.widthIn(max = BubbleMaxWidth)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = text, style = MaterialTheme.typography.bodyLarge)
                if (speaking) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "speaking · captions",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 4.dp, bottomEnd = 20.dp, bottomStart = 20.dp),
            modifier = Modifier.widthIn(max = BubbleMaxWidth)
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun InputBar(
    uiState: AssistantUiState,
    onMicClick: () -> Unit,
    onSendText: (String) -> Unit,
    onCameraClick: () -> Unit
) {
    val controlsEnabled = uiState.isModelAvailable && uiState.isPermissionGranted
    var draft by remember { mutableStateOf("") }
    val hasText = draft.isNotBlank()

    fun send() {
        if (hasText && controlsEnabled) {
            onSendText(draft)
            draft = ""
        }
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = onCameraClick,
                enabled = controlsEnabled
            ) {
                Icon(
                    imageVector = Icons.Rounded.PhotoCamera,
                    contentDescription = "Camera",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp),
                placeholder = { Text("Type or say something…") },
                shape = RoundedCornerShape(28.dp),
                singleLine = false,
                maxLines = 4,
                enabled = controlsEnabled,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(onSend = { send() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    disabledContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )

            // Text present → send; otherwise the mic drives the voice loop (matches the mockup).
            if (hasText) {
                SendButton(enabled = controlsEnabled, onClick = { send() })
            } else {
                MicButton(
                    isActive = uiState.isActive,
                    enabled = controlsEnabled,
                    onClick = onMicClick
                )
            }
        }
    }
}

@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = if (enabled) 3.dp else 0.dp,
        modifier = Modifier.size(56.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Send,
                contentDescription = "Send"
            )
        }
    }
}

@Composable
private fun MicButton(isActive: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val container = if (enabled) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest
    val content = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer
                  else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(28.dp),
        color = container,
        contentColor = content,
        shadowElevation = if (enabled) 3.dp else 0.dp,
        modifier = Modifier.size(56.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (isActive) Icons.Rounded.Stop else Icons.Rounded.Mic,
                contentDescription = if (isActive) "Stop" else "Speak"
            )
        }
    }
}

@Composable
private fun ListeningSheet(onStop: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Waveform()
            Text(
                text = "Listening…",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            PulsingMic(onClick = onStop)
        }
    }
}

@Composable
private fun Waveform() {
    val transition = rememberInfiniteTransition(label = "waveform")
    val factors = listOf(0f, 0.2f, 0.4f, 0.6f).map { delay ->
        transition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(600, delayMillis = (delay * 1000).toInt(), easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar"
        )
    }
    Row(
        modifier = Modifier.height(32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        factors.forEach { f ->
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .fillMaxHeight(f.value)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
private fun PulsingMic(onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 6.dp,
        modifier = Modifier
            .size(64.dp)
            .scale(pulse)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Rounded.Mic,
                contentDescription = "Stop listening",
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    uiState: AssistantUiState,
    viewModel: AssistantViewModel,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            if (uiState.isModelAvailable) {
                OutlinedButton(
                    onClick = { viewModel.checkModel() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Refresh engine")
                }
            }

            if (uiState.messages.isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        viewModel.clearHistory()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Clear conversation")
                }
            }

            // Neural voice (Kokoro) download / status
            KokoroVoiceCard(uiState, viewModel)

            // High-quality system voice data (only when missing)
            if (!uiState.isTtsVoiceDataAvailable) {
                TtsVoiceDataCard(viewModel, isMissing = true)
            }

            VoiceSelector(uiState, viewModel)
            SpeedSelector(uiState, viewModel)
        }
    }
}

@Composable
fun ModelDownloadCard(uiState: AssistantUiState, viewModel: AssistantViewModel) {
    var hfToken by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
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
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )

            if (!uiState.isDownloading) {
                OutlinedTextField(
                    value = hfToken,
                    onValueChange = { hfToken = it },
                    label = { Text("Hugging Face Token (optional if public)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium
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
                    Button(onClick = { viewModel.downloadModel(hfToken) }) {
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
fun TtsVoiceDataCard(viewModel: AssistantViewModel, isMissing: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
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
                style = MaterialTheme.typography.bodyMedium,
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
        shape = MaterialTheme.shapes.extraLarge,
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
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                uiState.isKokoroDownloading -> {
                    Text(
                        text = "Downloading & extracting voice model…",
                        style = MaterialTheme.typography.bodyMedium,
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
                        style = MaterialTheme.typography.bodyMedium,
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
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
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
    if (uiState.availableVoices.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
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
                            imageVector = Icons.Rounded.ArrowDropDown,
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
