package cz.ivo.kukatko

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Account, session and caches. Everything lives in app-private prefs on the TV. */
class Tv(context: Context) {
    private val prefs = context.getSharedPreferences("kukatko", Context.MODE_PRIVATE)
    private val api = OneplayApi(
        OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build(),
        prefs.getString("apiVersion", null) ?: "v1.12",
    )
    private val loginLock = Mutex()
    private val streams = ConcurrentHashMap<String, Pair<Long, Stream>>()

    private var session: Session? = prefs.getString("token", null)?.let {
        Session(it, prefs.getString("profileId", "")!!, prefs.getLong("tokenTime", 0))
    }?.also { api.token = it.token }

    val hasAccount get() = prefs.contains("user")

    var lastChannel: String?
        get() = prefs.getString("lastChannel", null)
        set(value) = prefs.edit().putString("lastChannel", value).apply()

    /**
     * Minutes watched per channel, fading by half every 30 days: the most watched channels become
     * "Moje kanály" by themselves, and drop out again once nobody watches them.
     */
    fun addWatchTime(channelId: String, minutes: Double) {
        val scores = watchScores().toMutableMap()
        scores[channelId] = (scores[channelId] ?: 0.0) + minutes
        prefs.edit().putString("watch", Json.encodeToString(scores)).putLong("watchDay", today()).apply()
    }

    /** Up to 10 channels with at least 15 minutes watched (after fading), most watched first. */
    fun favourites(): List<String> =
        watchScores().filterValues { it >= 15 }.entries.sortedByDescending { it.value }.take(10).map { it.key }

    private fun watchScores(): Map<String, Double> {
        val scores = prefs.getString("watch", null)
            ?.let { runCatching { Json.decodeFromString<Map<String, Double>>(it) }.getOrNull() }.orEmpty()
        val fade = Math.pow(0.5, (today() - prefs.getLong("watchDay", today())) / 30.0)
        return scores.mapValues { it.value * fade }
    }

    /**
     * Minutes watched per programme title and day, live or from the archive. A show watched on 2 different
     * days for 10 minutes or more counts as a favourite; days older than 60 are forgotten.
     */
    fun addShowTime(title: String, minutes: Double) {
        val day = today()
        val shows = showDays()
            .mapValues { (_, days) -> days.filterKeys { it > day - 60 } }
            .filterValues { it.isNotEmpty() }
            .toMutableMap()
        val days = shows[title].orEmpty().toMutableMap()
        days[day] = (days[day] ?: 0.0) + minutes
        shows[title] = days
        prefs.edit().putString("shows", Json.encodeToString(shows)).apply()
    }

    fun favouriteShows(): Set<String> = showDays().filterValues { days -> days.count { it.value >= 10 } >= 2 }.keys

    private fun showDays(): Map<String, Map<Long, Double>> = prefs.getString("shows", null)
        ?.let { runCatching { Json.decodeFromString<Map<String, Map<Long, Double>>>(it) }.getOrNull() }.orEmpty()

    private fun today() = System.currentTimeMillis() / 86_400_000

    suspend fun login(user: String, password: String) {
        val newSession = api.login(user, password)
        prefs.edit().putString("user", user).putString("password", password).apply()
        save(newSession)
    }

    fun logout() {
        session = null
        api.token = null
        prefs.edit().remove("user").remove("password").remove("token").remove("channels").apply()
    }

    fun cachedChannels(): List<Channel> =
        prefs.getString("channels", null)?.let { runCatching { Json.decodeFromString<List<Channel>>(it) }.getOrNull() }.orEmpty()

    suspend fun channels(): List<Channel> = authed { api.channels(it.profileId) }
        .also { prefs.edit().putString("channels", Json.encodeToString(it)).apply() }

    suspend fun nowPlaying(): Map<String, Programme> = authed { api.nowPlaying() }

    /** The archive reaches 7 days back; a few hours ahead shows what's coming. */
    suspend fun guide(channel: Channel): List<Programme> {
        val now = Instant.now()
        return authed { api.guide(channel, now.minus(7, ChronoUnit.DAYS), now.plus(6, ChronoUnit.HOURS)) }
    }

    suspend fun search(query: String): List<SearchHit> = authed { api.search(query) }

    /** The last 6 searches that led to watching something, newest first. */
    fun recentSearches(): List<String> =
        prefs.getString("recentSearches", null)?.let { runCatching { Json.decodeFromString<List<String>>(it) }.getOrNull() }.orEmpty()

    fun rememberSearch(query: String) {
        val list = (listOf(query) + recentSearches().filter { !it.equals(query, ignoreCase = true) }).take(6)
        prefs.edit().putString("recentSearches", Json.encodeToString(list)).apply()
    }

    suspend fun archiveStream(programme: Programme): Stream =
        authed { api.archiveStream(programme.payload ?: throw OneplayApi.ApiException("Pořad nejde přehrát")) }

    /** Stream URLs are fetched ahead for neighbouring channels; a prefetched one is used once, within 30 s. */
    suspend fun stream(channelId: String): Stream {
        streams.remove(channelId)?.let { (time, stream) ->
            if (System.currentTimeMillis() - time < 30_000) return stream
        }
        return authed { api.stream(channelId) }
    }

    suspend fun prefetch(channelId: String) {
        if (streams.containsKey(channelId)) return
        runCatching { authed { api.stream(channelId) } }
            .onSuccess { streams[channelId] = System.currentTimeMillis() to it }
    }

    private suspend fun <T> authed(block: suspend (Session) -> T): T = try {
        block(session(renew = false))
    } catch (e: OneplayApi.ApiException) {
        // Expired token or "Kdo se dívá?" (profile not selected): log in again and retry once.
        block(session(renew = true))
    }

    private suspend fun session(renew: Boolean): Session = loginLock.withLock {
        val current = session
        // Tokens last about 4 hours (per the Kodi add-on); renew a bit earlier.
        if (!renew && current != null && System.currentTimeMillis() - current.created < 3 * 3600_000) return current
        val user = prefs.getString("user", null) ?: throw OneplayApi.ApiException("Nepřihlášeno")
        api.login(user, prefs.getString("password", "")!!).also { save(it) }
    }

    private fun save(newSession: Session) {
        session = newSession
        prefs.edit()
            .putString("token", newSession.token)
            .putString("profileId", newSession.profileId)
            .putLong("tokenTime", newSession.created)
            .putString("apiVersion", api.version)
            .apply()
    }
}
