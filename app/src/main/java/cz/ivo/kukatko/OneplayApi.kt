package cz.ivo.kukatko

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** [index] is the channel's position in Oneplay's channel set, which the EPG viewport is addressed by. */
@Serializable
data class Channel(val id: String, val number: Int, val name: String, val logo: String?, val index: Int = -1)

/** A guide entry; [payload] opens it from the archive (`page.content.display`). */
data class Programme(val channelId: String, val title: String, val start: Long, val end: Long, val payload: JsonObject? = null) {
    fun progress(nowSec: Long) = ((nowSec - start).toFloat() / (end - start).coerceAtLeast(1)).coerceIn(0f, 1f)
}

/**
 * A broadcast found by Oneplay's search ("Z TV programu"): [detail] is e.g. "Díl 5214, 22. série",
 * [start] is 0 when the tile has no date; [payload] opens it from the archive like a guide entry.
 */
data class SearchHit(val title: String, val detail: String?, val channelName: String?, val start: Long, val payload: JsonObject)

data class Session(val token: String, val profileId: String, val created: Long)

/** A playable stream. Widevine streams carry O2/Oneplay's licence URL and the token it expects. */
data class Stream(val url: String, val mimeType: String, val licenseUrl: String?, val licenseToken: String?)

/**
 * Oneplay (where O2 TV customers were moved in 2025). Flow worked out from
 * github.com/waladir/plugin.video.oneplay: every call is a POST to http.cms.jyxo.cz,
 * and calls answered with "OkAsync" deliver their result over a websocket that we keep open.
 */
class OneplayApi(private val http: OkHttpClient, var version: String) {
    class ApiException(message: String) : Exception(message)

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0"
        /**
         * The name this device gets in the account. With the model in it, the TV and the emulator each keep
         * their own entry: a login removes only older entries with the same name (and old plain "Kukátko" ones).
         */
        val DEVICE_NAME = "Kukátko (${android.os.Build.MODEL})"
        private const val OLD_DEVICE_NAME = "Kukátko"
        private const val API = "https://http.cms.jyxo.cz/api"
        private const val SOCKET = "wss://ws.cms.jyxo.cz/websocket"
        private val JSON_TYPE = "application/json;charset=UTF-8".toMediaType()
        private val PLAYBACK = obj("protocols" to listOf("dash", "hls"), "drm" to listOf("widevine"), "altTransfer" to "Unicast", "multipleAudio" to false)
    }

    var token: String? = null

    private val socketLock = Mutex()
    @Volatile private var socket: WebSocket? = null
    private var clientId = ""
    private var serverId = ""
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()

    suspend fun login(user: String, password: String): Session {
        token = null
        var step = call("user.login.step", obj("command" to obj("schema" to "LoginWithCredentialsCommand", "email" to user, "password" to password)))
            .jsonObject["step"]!!.jsonObject
        if (step.str("schema") == "ShowAccountChooserStep") {
            // Several accounts behind one login: take the first active one.
            val account = step["groups"]!!.jsonArray.flatMap { it.jsonObject["accounts"]?.jsonArray.orEmpty() }.map { it.jsonObject }
                .first { it.str("extId") != null || it["isActive"]?.let { a -> (a as JsonPrimitive).booleanOrNull } == true }
            step = call("user.login.step", obj("command" to obj("schema" to "LoginWithAccountCommand", "accountId" to account.str("accountId"), "authCode" to step.str("authToken"))))
                .jsonObject["step"]!!.jsonObject
        }
        token = step.str("bearerToken") ?: throw ApiException("Přihlášení selhalo")

        // Every login registers a device on the account. Name ours and drop our older entries.
        val loggedUser = (step["currentUser"] ?: step["user"]?.jsonObject?.get("loggedUser"))?.jsonObject
        loggedUser?.get("currentDevice")?.jsonObject?.str("id")?.let { deviceId -> runCatching { tidyDevices(deviceId) } }

        val profileId = call("user.profiles.display", obj("mode" to "change"))["availableProfiles"]!!.jsonObject["profiles"]!!.jsonArray
            .first().jsonObject["profile"]!!.jsonObject.str("id")!!
        selectProfile(profileId)
        return Session(token!!, profileId, System.currentTimeMillis())
    }

    suspend fun selectProfile(profileId: String) {
        call("user.profile.select", obj("profileId" to profileId)).str("bearerToken")?.let { token = it }
    }

    private suspend fun tidyDevices(deviceId: String) {
        call("user.device.change", obj("id" to deviceId, "name" to DEVICE_NAME))
        val blocks = call("setting.display", obj("screen" to "devices"))["screen"]!!.jsonObject["blocks"]!!.jsonArray.map { it.jsonObject }
        val devices = blocks.firstOrNull { it.str("schema") == "SettingUserDevicesBlock" }
            ?.get("devices")?.jsonObject?.get("devices")?.jsonArray?.map { it.jsonObject }.orEmpty()
        for (device in devices) {
            if (device.str("id") != deviceId && device.str("name") in setOf(DEVICE_NAME, OLD_DEVICE_NAME)) {
                call("user.device.remove", obj("criteria" to obj("schema" to "UserDeviceIdCriteria", "id" to device.str("id"))))
            }
        }
    }

    suspend fun channels(profileId: String): List<Channel> =
        call("epg.channels.display", obj("profileId" to profileId))["channelList"]!!.jsonArray.map { it.jsonObject }
            .mapIndexedNotNull { index, channel ->
                val upsell = channel["upsell"]?.let { (it as? JsonPrimitive)?.booleanOrNull } == true ||
                    channel["flags"]?.toString()?.contains("upsell") == true
                if (upsell) return@mapIndexedNotNull null
                Channel(
                    id = channel.str("id")!!,
                    number = (channel["order"] as? JsonPrimitive)?.intOrNull ?: 9999,
                    name = channel.str("name").orEmpty(),
                    // The image CDN's "n" mode crops to fill (wide logos lose their ends); "f" fits the whole logo.
                    logo = channel.str("logo")?.takeIf { it.length > 1 }
                        ?.replace("{HEIGHT}n/", "{HEIGHT}f/")?.replace("{WIDTH}", "390")?.replace("{HEIGHT}", "234"),
                    index = index,
                )
            }
            .sortedBy { it.number }

    /** What is on air right now, keyed by channel id. */
    suspend fun nowPlaying(): Map<String, Programme> {
        val now = Instant.now()
        val nowSec = now.epochSecond
        return epg(now, now.plusSeconds(60), 0, 300).mapNotNull { (_, items) ->
            items.firstOrNull { it.start <= nowSec && it.end > nowSec }
        }.associateBy { it.channelId }
    }

    /** One channel's guide between [from] and [to]; asks for just its row of the EPG grid. */
    suspend fun guide(channel: Channel, from: Instant, to: Instant): List<Programme> {
        val row = if (channel.index >= 0) epg(from, to, channel.index, channel.index + 1)[channel.id] else null
        return (row ?: epg(from, to, 0, 300)[channel.id].orEmpty()).sortedBy { it.start }
    }

    private suspend fun epg(from: Instant, to: Instant, firstChannel: Int, lastChannel: Int): Map<String, List<Programme>> = call(
        "epg.display",
        obj(
            "criteria" to obj(
                "channelSetId" to "channel_list.1",
                "viewport" to obj(
                    "channelRange" to obj("from" to firstChannel, "to" to lastChannel),
                    "timeRange" to obj("from" to from.toString(), "to" to to.toString()),
                    "schema" to "EpgViewportAbsolute",
                ),
            ),
            "requestedOutput" to obj("channelList" to "none", "datePicker" to false, "channelSets" to false),
        ),
    )["schedule"]?.jsonArray.orEmpty().mapNotNull { entry ->
        val channel = entry.jsonObject
        val channelId = channel.str("channelId") ?: return@mapNotNull null
        channelId to channel["items"]?.jsonArray.orEmpty().mapNotNull { element ->
            val item = element.jsonObject
            val action = item["actions"]?.jsonArray?.firstOrNull()?.jsonObject
            Programme(
                channelId = channelId,
                title = item.str("title").orEmpty(),
                start = item.str("startAt")?.let { Instant.parse(it).epochSecond } ?: return@mapNotNull null,
                end = item.str("endAt")?.let { Instant.parse(it).epochSecond } ?: return@mapNotNull null,
                payload = action?.takeIf { it.str("call") == "page.content.display" }?.get("params")?.jsonObject?.get("payload") as? JsonObject,
            )
        }
    }.toMap()

    /**
     * Oneplay's own search (`page.search.display`, as in the Kodi add-on). It is fuzzy and copes without
     * diacritics ("udalosti" finds Události). Only the "Z TV programu" carousel is kept: past broadcasts
     * that play from the archive; each comes twice under two ids, so one per title, channel and time. Newest first.
     */
    suspend fun search(query: String): List<SearchHit> {
        val carousels = call("page.search.display", obj("query" to query))["layout"]?.jsonObject?.get("blocks")?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .filter { it.str("schema") == "TabBlock" }
            .flatMap { it["layout"]?.jsonObject?.get("blocks")?.jsonArray.orEmpty() }
            .flatMap { it.jsonObject["carousels"]?.jsonArray.orEmpty() }
            .map { it.jsonObject }
        val zone = ZoneId.of("Europe/Prague")
        val today = LocalDate.now(zone)
        return carousels.filter { it["tracking"]?.jsonObject?.str("title") == "Z TV programu" }
            .flatMap { it["tiles"]?.jsonArray.orEmpty() }
            .mapNotNull { element ->
                val tile = element.jsonObject
                val action = tile["action"]?.jsonObject?.takeIf { it.str("call") == "page.content.display" } ?: return@mapNotNull null
                // Date and time come as labels: "23.10." and "19:00".
                val labels = tile["additionalFragments"]?.jsonArray.orEmpty()
                    .flatMap { it.jsonObject["labels"]?.jsonArray.orEmpty() }
                    .mapNotNull { it.jsonObject.str("name") }
                val date = labels.firstNotNullOfOrNull { Regex("""(\d{1,2})\.(\d{1,2})\.""").matchEntire(it) }
                val time = labels.firstNotNullOfOrNull { Regex("""(\d{1,2}):(\d{2})""").matchEntire(it) }
                val start = if (date != null && time != null) runCatching {
                    val (d, m) = date.destructured
                    // No year in the label: the one that puts the day nearest to today (31.12. seen on 2.1. is last year).
                    val day = (-1..1).map { LocalDate.of(today.year + it, m.toInt(), d.toInt()) }
                        .minBy { Math.abs(it.toEpochDay() - today.toEpochDay()) }
                    val (h, min) = time.destructured
                    day.atTime(h.toInt(), min.toInt()).atZone(zone).toEpochSecond()
                }.getOrDefault(0L) else 0L
                SearchHit(
                    title = tile.str("title") ?: return@mapNotNull null,
                    detail = (tile.str("subTitle") ?: tile.str("description"))?.takeIf { it.isNotBlank() },
                    channelName = tile["image"]?.jsonObject?.get("labels")?.jsonArray?.firstOrNull()?.jsonObject?.str("name"),
                    start = start,
                    payload = action["params"]?.jsonObject?.get("payload") as? JsonObject ?: return@mapNotNull null,
                )
            }
            .distinctBy { Triple(it.title, it.channelName, it.start) }
            .sortedByDescending { it.start }
    }

    /** A past programme: its detail page carries the content.play action for the recording. */
    suspend fun archiveStream(payload: JsonObject): Stream {
        val blocks = call("page.content.display", payload)["layout"]?.jsonObject?.get("blocks")?.jsonArray?.map { it.jsonObject }.orEmpty()
        val action = blocks.firstOrNull { it.str("schema") == "ContentHeaderBlock" }
            ?.get("mainAction")?.jsonObject?.get("action")?.jsonObject
        if (action?.str("call") != "content.play") throw ApiException("Pořad už v archivu není")
        val playPayload = action["params"]!!.jsonObject["payload"]!!.jsonObject
        return parseStream(call("content.play", playPayload, "playbackCapabilities" to PLAYBACK))
    }

    suspend fun stream(channelId: String): Stream {
        var data = play(channelId, "start")
        // Channels without timeshift only play in "live" mode.
        val timeShift = data["playerControl"]?.jsonObject?.get("liveControl")?.jsonObject?.get("timeline")?.jsonObject
            ?.get("timeShift")?.jsonObject?.get("available")
        if ((timeShift as? JsonPrimitive)?.booleanOrNull == false) data = play(channelId, "live")
        return parseStream(data)
    }

    private fun parseStream(data: JsonObject): Stream {
        val assets = data["media"]?.jsonObject?.get("stream")?.jsonObject?.get("assets")?.jsonArray?.map { it.jsonObject }.orEmpty()
        assets.firstOrNull { it.str("protocol") == "dash" && it["drm"] == null }
            ?.let { return Stream(it.str("src")!!, "application/dash+xml", null, null) }
        assets.firstOrNull { it.str("protocol") == "dash" && it["drm"] != null }?.let { asset ->
            val drm = asset["drm"]!!.jsonArray.first().jsonObject
            return Stream(
                asset.str("src")!!,
                "application/dash+xml",
                drm.str("licenseAcquisitionURL"),
                drm["drmAuthorization"]?.jsonObject?.str("value"),
            )
        }
        assets.firstOrNull { it.str("protocol") == "hls" && it["drm"] == null }
            ?.let { return Stream(it.str("src")!!, "application/x-mpegURL", null, null) }
        throw ApiException("Kanál nejde přehrát")
    }

    private suspend fun play(channelId: String, startMode: String) = call(
        "content.play",
        obj("criteria" to obj("schema" to "ContentCriteria", "contentId" to "channel.$channelId"), "startMode" to startMode),
        "playbackCapabilities" to JsonObject(PLAYBACK + ("liveSpecificCapabilities" to PLAYBACK)),
    )

    private suspend fun call(method: String, payload: JsonObject, vararg extra: Pair<String, Any?>, retried: Boolean = false): JsonObject {
        val (client, server) = connect()
        val requestId = UUID.randomUUID().toString()
        val answer = CompletableDeferred<JsonObject>().also { pending[requestId] = it }
        try {
            val body = obj(
                "deviceInfo" to obj("deviceType" to "web", "appVersion" to "R11.33", "deviceManufacturer" to "Unknown", "deviceOs" to "Linux"),
                "capabilities" to obj("async" to "websockets"),
                "context" to obj("requestId" to requestId, "clientId" to client, "sessionId" to server, "serverId" to server),
                "payload" to payload,
                *extra,
            )
            val (code, text) = post("$API/$version/$method", body)
            if (code == 404 && !retried) {
                // Oneplay bumps the API version now and then; the old path starts answering 404.
                findVersion()
                return call(method, payload, *extra, retried = true)
            }
            var data = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse { throw ApiException("HTTP $code") }
            if (data["result"]?.jsonObject?.str("status") == "OkAsync") data = withTimeout(15_000) { answer.await() }
            val result = data["result"]?.jsonObject
            if (result?.str("status") != "Ok") throw ApiException(result?.str("message") ?: "Chyba Oneplay ($method)")
            return data["data"] as? JsonObject ?: JsonObject(emptyMap())
        } finally {
            pending.remove(requestId)
        }
    }

    private suspend fun post(url: String, body: JsonObject): Pair<Int, String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
    }

    private suspend fun findVersion() = withContext(Dispatchers.IO) {
        for (minor in (version.substringAfter('.').toIntOrNull() ?: 11)..60) {
            val candidate = "v1.%02d".format(minor)
            val request = Request.Builder().url("$API/$candidate/user.login.step").post("{}".toRequestBody(JSON_TYPE)).build()
            if (http.newCall(request).execute().use { it.code } != 404) {
                version = candidate
                return@withContext
            }
        }
    }

    /** One websocket for the whole session; async answers are matched to calls by requestId. */
    private suspend fun connect(): Pair<String, String> = socketLock.withLock {
        if (socket != null) return clientId to serverId
        val id = UUID.randomUUID().toString()
        val ready = CompletableDeferred<String>()
        val ws = http.newWebSocket(
            Request.Builder().url("$SOCKET/$id").header("User-Agent", USER_AGENT).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                    if (!ready.isCompleted) {
                        message["data"]?.jsonObject?.str("serverId")?.let { ready.complete(it); return }
                    }
                    val response = message["response"]?.jsonObject ?: return
                    val requestId = response["context"]?.jsonObject?.str("requestId") ?: return
                    pending.remove(requestId)?.complete(response)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = drop(webSocket, ready)
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = drop(webSocket, ready)
            },
        )
        serverId = try {
            withTimeout(10_000) { ready.await() }
        } catch (e: Exception) {
            ws.cancel()
            throw ApiException("Spojení s Oneplay selhalo")
        }
        clientId = id
        socket = ws
        id to serverId
    }

    private fun drop(webSocket: WebSocket, ready: CompletableDeferred<String>) {
        ready.completeExceptionally(ApiException("Spojení s Oneplay selhalo"))
        if (socket === webSocket) {
            socket = null
            pending.values.forEach { it.completeExceptionally(ApiException("Spojení s Oneplay se přerušilo")) }
        }
    }
}

private fun obj(vararg pairs: Pair<String, Any?>) = JsonObject(pairs.associate { (k, v) -> k to v.toJson() })

private fun Any?.toJson(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is List<*> -> JsonArray(map { it.toJson() })
    else -> error("Unsupported JSON value: $this")
}

private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
