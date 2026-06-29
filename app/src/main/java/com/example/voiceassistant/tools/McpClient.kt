package com.example.voiceassistant.tools

import com.example.voiceassistant.data.McpServerEntity
import com.google.ai.edge.litertlm.OpenApiTool
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * A user-configured remote MCP (Model Context Protocol) server. On Android only the **Streamable
 * HTTP** transport is possible (no local stdio subprocess), so a server is just a URL plus optional
 * auth headers. When enabled, its tools are discovered and registered with the on-device model. See
 * `architecture.md` §5b.
 */
data class McpServer(
    val id: Long = 0,
    val name: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
) {
    fun toEntity() = McpServerEntity(
        id = id, name = name, url = url,
        headersJson = if (headers.isEmpty()) null else JSONObject(headers as Map<*, *>).toString(),
        enabled = enabled,
    )

    companion object {
        fun fromEntity(e: McpServerEntity) = McpServer(
            id = e.id, name = e.name, url = e.url,
            headers = parseHeaders(e.headersJson), enabled = e.enabled,
        )

        fun parseHeaders(json: String?): Map<String, String> {
            if (json.isNullOrBlank()) return emptyMap()
            return try {
                val o = JSONObject(json)
                o.keys().asSequence().associateWith { o.getString(it) }
            } catch (e: Exception) {
                emptyMap()
            }
        }
    }
}

/** UI-facing view of a server plus its last discovery result (tool count / error). */
data class McpServerInfo(
    val server: McpServer,
    val toolCount: Int = 0,
    val error: String? = null,
)

/** A tool advertised by an MCP server (its `inputSchema` is already JSON-Schema). */
data class McpToolDef(val name: String, val description: String, val inputSchema: JSONObject)

/**
 * Minimal MCP client over Streamable HTTP (JSON-RPC 2.0). One [McpConnection] = one short-lived
 * session: `initialize` → `notifications/initialized` → `tools/list` or `tools/call`. Synchronous,
 * short timeouts, fail-soft. Not a full implementation — enough to discover and invoke tools.
 */
class McpConnection(private val server: McpServer) {
    private var sessionId: String? = null
    private var nextId = 1

    fun initialize() {
        val params = JSONObject()
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "VoiceAssistant").put("version", "1.0"))
        rpc("initialize", params)
        // Required handshake step before tools/* on most servers.
        notify("notifications/initialized")
    }

    fun listTools(): List<McpToolDef> {
        val result = rpc("tools/list", JSONObject())
        val arr = result.optJSONArray("tools") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val t = arr.optJSONObject(i) ?: return@mapNotNull null
            McpToolDef(
                name = t.getString("name"),
                description = t.optString("description"),
                inputSchema = t.optJSONObject("inputSchema") ?: JSONObject().put("type", "object"),
            )
        }
    }

    /** Call a tool; returns the textual content of the result (joined). */
    fun callTool(name: String, argumentsJson: String): String {
        val args = if (argumentsJson.isBlank()) JSONObject() else JSONObject(argumentsJson)
        val result = rpc("tools/call", JSONObject().put("name", name).put("arguments", args))
        val content = result.optJSONArray("content") ?: return result.toString()
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val part = content.optJSONObject(i) ?: continue
            when (part.optString("type")) {
                "text" -> sb.append(part.optString("text"))
                else -> sb.append(part.toString())
            }
            sb.append('\n')
        }
        val text = sb.toString().trim()
        return if (text.length > 4000) text.substring(0, 4000) + "…(truncated)" else text
    }

    /** Send a JSON-RPC request and return its `result` object (throws on transport/JSON-RPC error). */
    private fun rpc(method: String, params: JSONObject): JSONObject {
        val body = JSONObject()
            .put("jsonrpc", "2.0").put("id", nextId++).put("method", method).put("params", params)
        val envelope = post(body.toString(), expectResponse = true)
            ?: throw IllegalStateException("No response from MCP server for $method")
        envelope.optJSONObject("error")?.let {
            throw IllegalStateException("MCP error: ${it.optString("message", it.toString())}")
        }
        return envelope.optJSONObject("result") ?: JSONObject()
    }

    private fun notify(method: String) {
        val body = JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", JSONObject())
        runCatching { post(body.toString(), expectResponse = false) }
    }

    private fun post(jsonBody: String, expectResponse: Boolean): JSONObject? {
        val conn = (URL(server.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 12000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            setRequestProperty("MCP-Protocol-Version", PROTOCOL_VERSION)
            sessionId?.let { setRequestProperty("Mcp-Session-Id", it) }
            server.headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        conn.outputStream.use { it.write(jsonBody.toByteArray()) }

        conn.getHeaderField("Mcp-Session-Id")?.let { sessionId = it }
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            throw IllegalStateException("HTTP $code from MCP server${if (err.isNotBlank()) ": ${err.take(200)}" else ""}")
        }
        if (!expectResponse) { conn.disconnect(); return null }

        val contentType = conn.contentType ?: ""
        val raw = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return parseResponse(raw, contentType)
    }

    /** Parse either a plain JSON-RPC body or an SSE stream; return the first envelope with result/error. */
    private fun parseResponse(raw: String, contentType: String): JSONObject? {
        if (contentType.contains("text/event-stream", ignoreCase = true) || raw.startsWith("event:") || raw.contains("\ndata:")) {
            for (line in raw.lineSequence()) {
                val trimmed = line.trimStart()
                if (!trimmed.startsWith("data:")) continue
                val payload = trimmed.removePrefix("data:").trim()
                if (payload.isEmpty() || payload == "[DONE]") continue
                runCatching { JSONObject(payload) }.getOrNull()?.let {
                    if (it.has("result") || it.has("error")) return it
                }
            }
            return null
        }
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    companion object {
        private const val PROTOCOL_VERSION = "2025-06-18"
    }
}

/**
 * Adapts one remote MCP tool to LiteRT-LM's [OpenApiTool] so the on-device model can call it. The
 * MCP `inputSchema` is already JSON-Schema, so it becomes the `parameters` block directly. `execute`
 * opens a short MCP session and runs `tools/call`; failures return an error string, never throw.
 */
class McpServerTool(
    private val server: McpServer,
    private val toolDef: McpToolDef,
    private val onToolUsed: OnToolUsed,
) : OpenApiTool {

    override fun getToolDescriptionJsonString(): String = JSONObject()
        .put("name", declaredName())
        .put("description", toolDef.description)
        .put("parameters", toolDef.inputSchema)
        .toString()

    override fun execute(input: String): String {
        return try {
            val conn = McpConnection(server)
            conn.initialize()
            val result = conn.callTool(toolDef.name, input)
            onToolUsed(server.name, "Called ${toolDef.name}")
            result.ifBlank { "(no content returned)" }
        } catch (e: Exception) {
            android.util.Log.e("McpClient", "tools/call ${toolDef.name} failed", e)
            "Error: couldn't run ${toolDef.name} (${e.message ?: "unknown error"})."
        }
    }

    /** Model-facing function name, sanitized to a safe snake_case identifier. */
    private fun declaredName(): String = toolDef.name.lowercase()
        .replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "mcp_tool" }
}
