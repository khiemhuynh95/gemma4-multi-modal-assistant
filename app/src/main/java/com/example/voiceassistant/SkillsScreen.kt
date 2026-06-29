package com.example.voiceassistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.voiceassistant.tools.BuiltInSkill
import com.example.voiceassistant.tools.BuiltInSkills
import com.example.voiceassistant.tools.InstructionSkill
import com.example.voiceassistant.tools.McpServer
import com.example.voiceassistant.tools.McpServerInfo

/**
 * Skills management. Custom skills are free-form markdown "instructions" the user writes to teach the
 * assistant behaviours (injected into the model's context); built-in tools are compiled and shown
 * read-only. See `ui-context.md`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    uiState: AssistantUiState,
    onBack: () -> Unit,
    onSave: (InstructionSkill) -> Unit,
    onDelete: (Long) -> Unit,
    onSaveMcp: (McpServer) -> Unit,
    onDeleteMcp: (Long) -> Unit,
) {
    var editing by remember { mutableStateOf<InstructionSkill?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var editingMcp by remember { mutableStateOf<McpServer?>(null) }
    var showMcpEditor by remember { mutableStateOf(false) }

    if (showEditor) {
        SkillEditor(
            initial = editing,
            onCancel = { showEditor = false },
            onSave = {
                onSave(it)
                showEditor = false
            },
        )
        return
    }

    if (showMcpEditor) {
        McpServerEditor(
            initial = editingMcp,
            onCancel = { showMcpEditor = false },
            onSave = {
                onSaveMcp(it)
                showMcpEditor = false
            },
        )
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Skills", fontWeight = FontWeight.Bold) },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = null; showEditor = true },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("Add skill") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionHeader("Your skills (instructions)") }
            if (uiState.instructionSkills.isEmpty()) {
                item {
                    Text(
                        "No custom skills yet. Tap “Add skill” to write markdown instructions that teach " +
                            "the assistant a behaviour — for example, a bedtime routine that lowers the " +
                            "volume and sets an alarm.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            items(uiState.instructionSkills, key = { "skill_${it.id}" }) { skill ->
                InstructionSkillRow(
                    skill = skill,
                    onEdit = { editing = skill; showEditor = true },
                    onDelete = { onDelete(skill.id) },
                )
            }

            item {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader("MCP servers")
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { editingMcp = null; showMcpEditor = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Add server")
                    }
                }
                Text(
                    "Connect remote MCP servers (HTTP). Their tools become available to the assistant when enabled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            items(uiState.mcpServers, key = { "mcp_${it.server.id}" }) { info ->
                McpServerRow(
                    info = info,
                    onEdit = { editingMcp = info.server; showMcpEditor = true },
                    onDelete = { onDeleteMcp(info.server.id) },
                )
            }

            item {
                Spacer(Modifier.height(16.dp))
                SectionHeader("Built-in tools")
                Text(
                    "Always on. These run on-device (network tools need a connection).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            items(BuiltInSkills.ALL) { skill -> BuiltInSkillRow(skill) }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun InstructionSkillRow(skill: InstructionSkill, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(skill.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (!skill.enabled) {
                        Spacer(Modifier.width(8.dp))
                        AssistChip(onClick = {}, enabled = false, label = { Text("Off") })
                    }
                }
                Text(
                    skill.description.ifBlank {
                        skill.instructions.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, contentDescription = "Edit") }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun BuiltInSkillRow(skill: BuiltInSkill) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(skill.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AssistChip(onClick = {}, enabled = false, label = { Text(skill.group) })
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun McpServerRow(info: McpServerInfo, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(info.server.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (!info.server.enabled) {
                        Spacer(Modifier.width(8.dp))
                        AssistChip(onClick = {}, enabled = false, label = { Text("Off") })
                    }
                }
                Text(
                    info.server.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                val status = when {
                    !info.server.enabled -> "Disabled"
                    info.error != null -> "Error: ${info.error}"
                    else -> "${info.toolCount} tool${if (info.toolCount == 1) "" else "s"}"
                }
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (info.error != null && info.server.enabled) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, contentDescription = "Edit") }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Full-screen create/edit form for an MCP server. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpServerEditor(
    initial: McpServer?,
    onCancel: () -> Unit,
    onSave: (McpServer) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var headersText by remember {
        mutableStateOf(initial?.headers?.entries?.joinToString("\n") { "${it.key}: ${it.value}" } ?: "")
    }
    val canSave = name.isNotBlank() && url.isNotBlank()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, contentDescription = "Cancel") }
                },
                title = { Text(if (initial == null) "New MCP server" else "Edit MCP server", fontWeight = FontWeight.Bold) },
                actions = {
                    TextButton(
                        enabled = canSave,
                        onClick = {
                            val headers = headersText.lines().mapNotNull { line ->
                                val idx = line.indexOf(':')
                                if (idx <= 0) null
                                else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                            }.filter { it.first.isNotBlank() }.toMap()
                            onSave(
                                McpServer(
                                    id = initial?.id ?: 0,
                                    name = name.trim(),
                                    url = url.trim(),
                                    headers = headers,
                                    enabled = enabled,
                                )
                            )
                        },
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("Server URL (HTTP)") },
                supportingText = { Text("The MCP endpoint, e.g. https://example.com/mcp") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = headersText, onValueChange = { headersText = it },
                label = { Text("Headers (optional)") },
                supportingText = { Text("One per line, e.g. Authorization: Bearer <token>") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Enabled", modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Full-screen create/edit form for a markdown instruction skill. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillEditor(
    initial: InstructionSkill?,
    onCancel: () -> Unit,
    onSave: (InstructionSkill) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var instructions by remember { mutableStateOf(initial?.instructions ?: "") }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }

    val canSave = name.isNotBlank() && instructions.isNotBlank()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, contentDescription = "Cancel") }
                },
                title = { Text(if (initial == null) "New skill" else "Edit skill", fontWeight = FontWeight.Bold) },
                actions = {
                    TextButton(
                        enabled = canSave,
                        onClick = {
                            onSave(
                                InstructionSkill(
                                    id = initial?.id ?: 0,
                                    name = name.trim(),
                                    description = description.trim(),
                                    instructions = instructions.trim(),
                                    enabled = enabled,
                                )
                            )
                        },
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Name") },
                supportingText = { Text("A short label, e.g. \"Bedtime routine\".") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = description, onValueChange = { description = it },
                label = { Text("Short description") },
                supportingText = { Text("One line shown to the model so it knows when to load this skill.") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = instructions, onValueChange = { instructions = it },
                label = { Text("Instructions (markdown)") },
                supportingText = {
                    Text("Plain English / markdown. Describe what to do and when — you can refer to the built-in tools (timers, volume, calendar, etc.).")
                },
                minLines = 8,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Enabled", modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
