package com.example.voiceassistant.tools

import com.example.voiceassistant.data.InstructionSkillEntity
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

/**
 * A user-defined "instruction" skill: a name, a one-line [description], and a free-form markdown
 * [instructions] body. To keep prompts small, only the name + description are listed in the system
 * prompt; the model loads the full [instructions] on demand via the `get_skill` tool ([SkillTools])
 * when a skill is relevant. Skills steer behaviour and how the built-in tools are used; no external calls.
 */
data class InstructionSkill(
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val instructions: String,
    val enabled: Boolean = true,
) {
    fun toEntity(): InstructionSkillEntity =
        InstructionSkillEntity(id = id, name = name, description = description, instructions = instructions, enabled = enabled)

    companion object {
        fun fromEntity(e: InstructionSkillEntity) =
            InstructionSkill(id = e.id, name = e.name, description = e.description, instructions = e.instructions, enabled = e.enabled)
    }
}

/**
 * Exposes a single `get_skill` tool that returns the full instructions for one of the available
 * (enabled) skills. This is the "load on invoke" half of progressive disclosure: the system prompt
 * carries only each skill's name + short description, and the model calls this when it needs the body.
 * [enabledSkills] is read fresh on each call so toggles/edits take effect without re-registering tools.
 */
class SkillTools(
    private val enabledSkills: () -> List<InstructionSkill>,
    private val onToolUsed: OnToolUsed,
) : ToolSet {

    @Tool(description = "Load the full instructions for one of the available custom skills, by its exact name. Call this before following a skill.")
    fun getSkill(
        @ToolParam(description = "The skill's exact name, as listed in the available skills.") name: String,
    ): Map<String, Any?> {
        val skills = enabledSkills()
        val q = name.trim().lowercase()
        val s = skills.firstOrNull { it.name.lowercase() == q }
            ?: skills.firstOrNull { it.name.lowercase().contains(q) || q.contains(it.name.lowercase()) }
            ?: return mapOf("status" to "error", "summary" to "No skill named \"$name\". Available: ${skills.joinToString { it.name }}")
        onToolUsed("Skill: ${s.name}", "Loaded skill ${s.name}")
        return mapOf("status" to "success", "name" to s.name, "instructions" to s.instructions)
    }
}

/** A compiled built-in tool, listed read-only on the Skills screen. */
data class BuiltInSkill(val name: String, val description: String, val group: String)

/** Catalog mirroring the compiled ToolSets in `AssistantTools.kt`, for read-only display. */
object BuiltInSkills {
    val ALL: List<BuiltInSkill> = listOf(
        BuiltInSkill("Set timer", "Start a countdown timer for a number of seconds.", "Device"),
        BuiltInSkill("Set alarm", "Set an alarm at a 24-hour time.", "Device"),
        BuiltInSkill("Flashlight", "Turn the device flashlight on or off.", "Device"),
        BuiltInSkill("Media volume", "Set the media volume to a percentage.", "Device"),
        BuiltInSkill("Calendar event", "Create a calendar event.", "Device"),
        BuiltInSkill("Open app", "Launch an installed app by name.", "Device"),
        BuiltInSkill("Phone call", "Open the dialer with a phone number.", "Device"),
        BuiltInSkill("Send SMS", "Open a prefilled text message.", "Device"),
        BuiltInSkill("Wi-Fi settings", "Open the Wi-Fi settings panel.", "Device"),
        BuiltInSkill("Bluetooth settings", "Open Bluetooth settings.", "Device"),
        BuiltInSkill("Do Not Disturb", "Open Do-Not-Disturb settings.", "Device"),
        BuiltInSkill("Date & time", "Get the current local date and time.", "Info"),
        BuiltInSkill("Battery status", "Get battery level and charging state.", "Info"),
        BuiltInSkill("Connectivity", "Check whether the device is online.", "Info"),
        BuiltInSkill("Web search", "Search the web for a short factual answer.", "Network"),
        BuiltInSkill("Weather", "Get the current weather for a place.", "Network"),
    )
}
