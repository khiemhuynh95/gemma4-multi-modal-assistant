package com.example.voiceassistant.tools

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar

/**
 * On-device "skills" the model can invoke via LiteRT-LM native tool-calling.
 *
 * Each group implements [ToolSet]; every `@Tool`-annotated method becomes a callable function (the
 * name is auto snake_cased, e.g. `setTimer` -> `set_timer`). Methods return a JSON-serializable
 * [Map] that LiteRT feeds back to the model as the tool result. Keep descriptions short and
 * imperative — Gemma 4 E2B zero-shot function-calling is sensitive to schema clarity.
 *
 * Tools report what they did through [onToolUsed] so the UI can show a small status chip; this is
 * independent of the model's spoken answer (which still flows through the normal TTS pipeline).
 *
 * See `architecture.md` (tool-calling contract) and `CLAUDE.md` (scope). Network tools relax the
 * fully-offline guarantee — they degrade gracefully when there is no connection.
 */
typealias OnToolUsed = (name: String, summary: String) -> Unit

private const val TOOLS_TAG = "AssistantTools"

private fun ok(summary: String, extra: Map<String, Any?> = emptyMap()): Map<String, Any?> =
    mapOf("status" to "success", "summary" to summary) + extra

private fun fail(reason: String): Map<String, Any?> =
    mapOf("status" to "error", "summary" to reason)

/** Resolve + start [intent] (logging the outcome), reporting success via [onToolUsed]. Shared by the
 *  intent-based tool sets. Returns a result map for the model; never throws across the LiteRT boundary. */
private fun launchIntent(
    context: Context,
    intent: Intent,
    summary: String,
    chip: String,
    noHandlerMessage: String,
    onToolUsed: OnToolUsed,
): Map<String, Any?> {
    android.util.Log.i(TOOLS_TAG, "$chip tool invoked: $summary (action=${intent.action})")
    if (intent.resolveActivity(context.packageManager) == null) {
        android.util.Log.w(TOOLS_TAG, "$chip: no activity handles ${intent.action}")
        return fail(noHandlerMessage)
    }
    return try {
        context.startActivity(intent)
        onToolUsed(chip, summary)
        ok(summary)
    } catch (e: Exception) {
        android.util.Log.e(TOOLS_TAG, "$chip: startActivity failed", e)
        fail("Couldn't complete the action: ${e.message}")
    }
}

/** Device actions: timers, alarms, flashlight, volume, calendar. All fully on-device. */
class DeviceActionsTools(
    private val context: Context,
    private val onToolUsed: OnToolUsed,
) : ToolSet {

    @Tool(description = "Start a countdown timer for the given number of seconds.")
    fun setTimer(
        @ToolParam(description = "Timer length in seconds.") seconds: Int,
    ): Map<String, Any?> {
        if (seconds <= 0) return fail("Timer length must be positive.")
        val intent = Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launch(intent, "Set a ${seconds}s timer", "Timer", "No clock app can set a timer.")
    }

    @Tool(description = "Set an alarm at the given 24-hour time.")
    fun setAlarm(
        @ToolParam(description = "Hour in 24-hour format, 0 to 23.") hour: Int,
        @ToolParam(description = "Minute, 0 to 59.") minute: Int,
    ): Map<String, Any?> {
        if (hour !in 0..23 || minute !in 0..59) return fail("Invalid time.")
        val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
            putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val label = "%02d:%02d".format(hour, minute)
        return launch(intent, "Set an alarm for $label", "Alarm", "No clock app can set an alarm.")
    }

    @Tool(description = "Turn the device flashlight (torch) on or off.")
    fun setFlashlight(
        @ToolParam(description = "true to turn on, false to turn off.") on: Boolean,
    ): Map<String, Any?> {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val camId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return fail("This device has no flashlight.")
            cm.setTorchMode(camId, on)
            val summary = if (on) "Turned the flashlight on" else "Turned the flashlight off"
            onToolUsed("Flashlight", summary)
            ok(summary)
        } catch (e: Exception) {
            fail("Couldn't control the flashlight: ${e.message}")
        }
    }

    @Tool(description = "Set the media volume to a percentage from 0 to 100.")
    fun setMediaVolume(
        @ToolParam(description = "Volume percentage, 0 to 100.") percent: Int,
    ): Map<String, Any?> {
        val clamped = percent.coerceIn(0, 100)
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = Math.round(max * clamped / 100f)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            val summary = "Set media volume to $clamped%"
            onToolUsed("Volume", summary)
            ok(summary)
        } catch (e: Exception) {
            fail("Couldn't change the volume: ${e.message}")
        }
    }

    @Tool(
        description = "Create a calendar event. Use get_current_date_time first to resolve relative " +
            "dates like tomorrow into an absolute year, month and day."
    )
    fun createCalendarEvent(
        @ToolParam(description = "Event title.") title: String,
        @ToolParam(description = "Year, e.g. 2026.") year: Int,
        @ToolParam(description = "Month, 1 to 12.") month: Int,
        @ToolParam(description = "Day of month, 1 to 31.") day: Int,
        @ToolParam(description = "Start hour in 24-hour format, 0 to 23.") hour: Int,
        @ToolParam(description = "Start minute, 0 to 59.") minute: Int,
        @ToolParam(description = "Duration in minutes.") durationMinutes: Int,
    ): Map<String, Any?> {
        return try {
            val start = Calendar.getInstance().apply {
                set(year, month - 1, day, hour, minute, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val end = start + durationMinutes.coerceAtLeast(0) * 60_000L
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = android.provider.CalendarContract.Events.CONTENT_URI
                putExtra(android.provider.CalendarContract.Events.TITLE, title)
                putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, end)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val label = "%04d-%02d-%02d %02d:%02d".format(year, month, day, hour, minute)
            launch(intent, "Created event \"$title\" on $label", "Calendar", "No calendar app found.")
        } catch (e: Exception) {
            fail("Couldn't create the event: ${e.message}")
        }
    }

    /** Start [intent] if something can handle it; report the result through [onToolUsed]. */
    private fun launch(intent: Intent, summary: String, chip: String, noHandlerMessage: String) =
        launchIntent(context, intent, summary, chip, noHandlerMessage, onToolUsed)
}

/** Read-only device info: date/time, battery, connectivity. No permissions required. */
class InfoTools(
    private val context: Context,
    private val onToolUsed: OnToolUsed,
) : ToolSet {

    @Tool(description = "Get the current local date and time.")
    fun getCurrentDateTime(): Map<String, Any?> {
        val now = Calendar.getInstance()
        val iso = "%04d-%02d-%02dT%02d:%02d".format(
            now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH),
            now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE),
        )
        onToolUsed("Clock", "Checked the date and time")
        return ok("The current date and time is $iso", mapOf("datetime" to iso))
    }

    @Tool(description = "Get the current battery level and whether the device is charging.")
    fun getBatteryStatus(): Map<String, Any?> {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            onToolUsed("Battery", "Checked the battery")
            ok("Battery is at $level percent" + if (charging) " and charging" else "",
                mapOf("percent" to level, "charging" to charging))
        } catch (e: Exception) {
            fail("Couldn't read the battery status: ${e.message}")
        }
    }

    @Tool(description = "Check whether the device currently has an internet connection.")
    fun getConnectivityStatus(): Map<String, Any?> {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val online = caps?.hasCapability(
                android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
            ) == true
            onToolUsed("Network", "Checked connectivity")
            ok(if (online) "The device is online" else "The device is offline",
                mapOf("online" to online))
        } catch (e: Exception) {
            fail("Couldn't check connectivity: ${e.message}")
        }
    }
}

/**
 * Network skills (web search, weather) using keyless public APIs. These require connectivity and so
 * relax the otherwise fully-offline guarantee; when offline they return a graceful error instead of
 * throwing. Calls block with a short timeout and run on the engine's generation thread.
 */
class NetworkTools(
    private val onToolUsed: OnToolUsed,
) : ToolSet {

    @Tool(description = "Search the web for a short factual answer to a query.")
    fun webSearch(
        @ToolParam(description = "The search query.") query: String,
    ): Map<String, Any?> {
        val url = "https://api.duckduckgo.com/?q=${enc(query)}&format=json&no_html=1&skip_disambig=1"
        val body = httpGet(url) ?: return fail("I can't reach the network right now.")
        return try {
            val json = JSONObject(body)
            val answer = json.optString("AbstractText").ifBlank { json.optString("Answer") }
                .ifBlank {
                    json.optJSONArray("RelatedTopics")?.optJSONObject(0)?.optString("Text").orEmpty()
                }
            onToolUsed("Web search", "Searched the web for \"$query\"")
            if (answer.isBlank()) ok("No concise answer was found for that search.")
            else ok(answer, mapOf("result" to answer))
        } catch (e: Exception) {
            fail("Couldn't read the search result: ${e.message}")
        }
    }

    @Tool(description = "Get the current weather for a city or place.")
    fun getWeather(
        @ToolParam(description = "City or place name, e.g. Tokyo.") location: String,
    ): Map<String, Any?> {
        val geo = httpGet("https://geocoding-api.open-meteo.com/v1/search?name=${enc(location)}&count=1")
            ?: return fail("I can't reach the network right now.")
        return try {
            val results = JSONObject(geo).optJSONArray("results")
            if (results == null || results.length() == 0) return fail("I couldn't find that place.")
            val place = results.getJSONObject(0)
            val lat = place.getDouble("latitude")
            val lon = place.getDouble("longitude")
            val name = place.optString("name", location)
            val wx = httpGet(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current_weather=true"
            ) ?: return fail("I can't reach the network right now.")
            val current = JSONObject(wx).getJSONObject("current_weather")
            val temp = current.getDouble("temperature")
            val wind = current.getDouble("windspeed")
            onToolUsed("Weather", "Checked the weather in $name")
            ok("It's $temp degrees Celsius in $name with winds around $wind kilometers per hour.",
                mapOf("temperatureC" to temp, "windKph" to wind, "place" to name))
        } catch (e: Exception) {
            fail("Couldn't read the weather: ${e.message}")
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Blocking GET with a short timeout; returns null on any failure (e.g. offline). */
    private fun httpGet(url: String): String? {
        return try {
            (URL(url).openConnection() as HttpURLConnection).run {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "VoiceAssistant/1.0")
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    disconnect()
                    return null
                }
                val text = inputStream.bufferedReader().use { it.readText() }
                disconnect()
                text
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Phone/app/system skills that hand off to other apps via intents. All on-device. Calls use the
 * dialer (ACTION_DIAL) and SMS uses a prefilled compose screen, so no CALL_PHONE/SEND_SMS runtime
 * permissions are needed. System toggles (Wi-Fi/Bluetooth/DND) can't be flipped directly on modern
 * Android, so these open the relevant Settings panel for the user to confirm. Requires matching
 * `<queries>` entries in the manifest for package visibility.
 */
class SystemTools(
    private val context: Context,
    private val onToolUsed: OnToolUsed,
) : ToolSet {

    @Tool(description = "Open an installed app by its display name, e.g. \"Spotify\".")
    fun openApp(
        @ToolParam(description = "The app's name as shown on the device.") appName: String,
    ): Map<String, Any?> {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        val query = appName.trim().lowercase()
        val match = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == query }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(query) }
            ?: return fail("I couldn't find an app called \"$appName\".")
        val pkg = match.activityInfo.packageName
        val launch = pm.getLaunchIntentForPackage(pkg)
            ?: return fail("I couldn't open \"$appName\".")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launch)
            val label = match.loadLabel(pm).toString()
            onToolUsed("Open app", "Opened $label")
            ok("Opened $label")
        } catch (e: Exception) {
            android.util.Log.e(TOOLS_TAG, "openApp failed", e)
            fail("Couldn't open \"$appName\": ${e.message}")
        }
    }

    @Tool(description = "Open the phone dialer with a number ready to call.")
    fun dialNumber(
        @ToolParam(description = "Phone number to dial.") number: String,
    ): Map<String, Any?> {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${number.trim()}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchIntent(context, intent, "Dialing $number", "Phone", "No dialer app found.", onToolUsed)
    }

    @Tool(description = "Open a text message prefilled with a recipient and body.")
    fun sendSms(
        @ToolParam(description = "Recipient phone number.") number: String,
        @ToolParam(description = "Message text.") message: String,
    ): Map<String, Any?> {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${number.trim()}"))
            .putExtra("sms_body", message)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchIntent(context, intent, "Texting $number", "SMS", "No messaging app found.", onToolUsed)
    }

    @Tool(description = "Open the Wi-Fi settings so the user can turn Wi-Fi on or off.")
    fun openWifiSettings(): Map<String, Any?> {
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI
        else Settings.ACTION_WIFI_SETTINGS
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchIntent(context, intent, "Opened Wi-Fi settings", "Wi-Fi", "Can't open Wi-Fi settings.", onToolUsed)
    }

    @Tool(description = "Open Bluetooth settings so the user can turn Bluetooth on or off.")
    fun openBluetoothSettings(): Map<String, Any?> {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchIntent(context, intent, "Opened Bluetooth settings", "Bluetooth", "Can't open Bluetooth settings.", onToolUsed)
    }

    @Tool(description = "Open Do Not Disturb settings.")
    fun openDoNotDisturbSettings(): Map<String, Any?> {
        // ZEN_MODE_SETTINGS is the DND screen; fall back to sound settings if a device lacks it.
        val intent = Intent("android.settings.ZEN_MODE_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) {
            val fallback = Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return launchIntent(context, fallback, "Opened sound settings", "Do Not Disturb", "Can't open settings.", onToolUsed)
        }
        return launchIntent(context, intent, "Opened Do Not Disturb settings", "Do Not Disturb", "Can't open settings.", onToolUsed)
    }
}
