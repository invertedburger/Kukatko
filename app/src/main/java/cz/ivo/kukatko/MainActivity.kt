package cz.ivo.kukatko

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var state: TvState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setUserAgent(OneplayApi.USER_AGENT)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(http)
                    .setDrmSessionManagerProvider(DefaultDrmSessionManagerProvider().apply { setDrmHttpDataSourceFactory(http) })
            )
            // Start playing after 1 s of buffer instead of the default 2.5 s: faster zapping.
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 30_000, 1_000, 2_000).build())
            .build()
        state = TvState(Tv(this), player, lifecycleScope) { lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                App(state, player)
            }
        }
        handleLoginExtra(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLoginExtra(intent)
    }

    /** `tools/login.ps1` sends the account as base64("user\npassword") so nobody has to type it with a remote. */
    private fun handleLoginExtra(intent: Intent) {
        val encoded = intent.getStringExtra("login") ?: return
        intent.removeExtra("login")
        val lines = runCatching { String(Base64.decode(encoded, Base64.DEFAULT)).split('\n', limit = 2) }.getOrNull()
        if (lines?.size == 2) state.login(lines[0].trim(), lines[1].trimEnd('\r'))
    }

    /** Every key press goes to the log (`adb logcat -s Kukatko:I`), so a real remote's buttons can be read off the TV. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            Log.i("Kukatko", "key ${KeyEvent.keyCodeToString(event.keyCode)} code=${event.keyCode} scan=${event.scanCode} device=${event.device?.name}")
        }
        // Back goes straight to the screens' BackHandlers. Compose would otherwise use the first Back to move
        // focus out of a list it was put into in code (results, guide), and Back would seem to do nothing.
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        state.resume()
    }

    override fun onStop() {
        super.onStop()
        player.stop()
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }
}

/** [onScreen] tells whether the app is visible: a login sent over adb while the TV sleeps must not start playing. */
class TvState(private val tv: Tv, private val player: ExoPlayer, private val scope: CoroutineScope, private val onScreen: () -> Boolean) {
    var loggedIn by mutableStateOf(tv.hasAccount)
    var channels by mutableStateOf(tv.cachedChannels())
    var nowPlaying by mutableStateOf(emptyMap<String, Programme>())
    var current by mutableStateOf<Channel?>(null)
    var listOpen by mutableStateOf(false)
    var bannerVisible by mutableStateOf(false)
    var digits by mutableStateOf("")
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)

    /** Channel whose 7-day guide is open next to the channel list, and its programmes once loaded. */
    var guideChannel by mutableStateOf<Channel?>(null)
    var guide by mutableStateOf<List<Programme>?>(null)
    /** Set while a past programme plays from the archive; null means live TV. */
    var archive by mutableStateOf<Programme?>(null)
    var paused by mutableStateOf(false)
    /**
     * The most watched channels lately, picked automatically. Recomputed when the channel list opens,
     * so rows don't jump around while it is on screen.
     */
    var favourites by mutableStateOf(tv.favourites().toSet())
    /** Programme titles watched on several days, also picked automatically; shown green in the guide. */
    var favouriteShows by mutableStateOf(tv.favouriteShows())

    /** "Hledat pořad": open over the channel list (Back returns to it); [results] is null until a search ran. */
    var searchOpen by mutableStateOf(false)
    var query by mutableStateOf("")
    var results by mutableStateOf<List<SearchHit>?>(null)
    var searching by mutableStateOf(false)
    var recentSearches by mutableStateOf(tv.recentSearches())

    private var playJob: Job? = null
    private var searchJob: Job? = null
    private var guideJob: Job? = null
    private var bannerJob: Job? = null
    private var digitsJob: Job? = null
    private var refreshJob: Job? = null
    private var retried = false

    init {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                } else if (!retried) {
                    // Most often an expired stream token: fetch a fresh URL once.
                    retried = true
                    archive?.let { playArchive(it) } ?: current?.let { play(it, showBanner = false) }
                } else {
                    message = "Přehrávání selhalo: ${error.errorCodeName}"
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                paused = !playWhenReady
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    retried = false
                    message = null
                }
            }
        })
        // Every minute of picture counts towards the channel's place in "Moje kanály" and towards the show.
        scope.launch {
            while (isActive) {
                delay(60_000)
                if (!player.isPlaying) continue
                current?.let { tv.addWatchTime(it.id, 1.0) }
                (archive?.title ?: current?.let { nowPlaying[it.id]?.title })?.takeIf { it.isNotBlank() }?.let { tv.addShowTime(it, 1.0) }
            }
        }
    }

    fun openList() {
        favourites = tv.favourites().toSet()
        favouriteShows = tv.favouriteShows()
        listOpen = true
    }

    fun openSearch() {
        recentSearches = tv.recentSearches()
        query = ""
        results = null
        searchOpen = true
    }

    /** Typing searches by itself once 2 letters are in, after a short pause; [now] skips the pause (a recent search). */
    fun search(text: String, now: Boolean = false) {
        query = text
        searchJob?.cancel()
        if (text.trim().length < 2) {
            results = null
            searching = false
            return
        }
        searching = true
        searchJob = scope.launch {
            if (!now) delay(700)
            results = runCatching { tv.search(text.trim()) }
                .onFailure { message = "Hledání: ${it.message}" }
                .getOrDefault(emptyList())
                // The same broadcast also comes on a channel's variants (e.g. NOVA AD with audio description):
                // keep the one on the lowest channel number.
                .groupBy { Triple(it.title, it.detail, it.start) }.values
                .map { same -> same.minBy { hit -> channels.firstOrNull { it.name.equals(hit.channelName, ignoreCase = true) }?.number ?: Int.MAX_VALUE } }
            searching = false
        }
    }

    fun playFound(hit: SearchHit) {
        tv.rememberSearch(query.trim())
        val channel = channels.firstOrNull { it.name.equals(hit.channelName, ignoreCase = true) }
        searchOpen = false
        listOpen = false
        // The end isn't known; a past one is enough for the banner ("ZE ZÁZNAMU").
        playArchive(Programme(channel?.id ?: current?.id.orEmpty(), hit.title, hit.start, hit.start + 1, hit.payload))
    }

    fun resume() {
        if (!loggedIn) return
        // Start from the cached list straight away; the fresh one arrives in the background.
        (current ?: channels.firstOrNull { it.id == tv.lastChannel })?.let { play(it) }
        refreshJob?.cancel()
        refreshJob = scope.launch {
            runCatching { channels = tv.channels() }.onFailure { if (channels.isEmpty()) message = it.message }
            if (current == null) (channels.firstOrNull { it.id == tv.lastChannel } ?: channels.firstOrNull())?.let { play(it) }
            while (isActive) {
                runCatching { nowPlaying = tv.nowPlaying() }
                delay(60_000)
            }
        }
    }

    fun login(user: String, password: String) {
        busy = true
        message = null
        scope.launch {
            runCatching { tv.login(user, password) }
                // In the log so a login over adb can be checked with the TV screen off (never the password).
                .onSuccess { Log.i("Kukatko", "login ok"); loggedIn = true; if (onScreen()) resume() }
                .onFailure { Log.i("Kukatko", "login failed: ${it.message}"); message = it.message ?: "Přihlášení selhalo" }
            busy = false
        }
    }

    fun logout() {
        player.stop()
        tv.logout()
        refreshJob?.cancel()
        current = null
        channels = emptyList()
        listOpen = false
        loggedIn = false
    }

    fun play(channel: Channel, showBanner: Boolean = true) {
        current = channel
        archive = null
        tv.lastChannel = channel.id
        if (showBanner) showBanner()
        playJob?.cancel()
        playJob = scope.launch {
            runCatching { tv.stream(channel.id) }
                .onSuccess { stream ->
                    start(stream)
                    // Prefetch the neighbours so the next zap skips the API round-trip.
                    neighbour(channel, 1)?.let { scope.launch { tv.prefetch(it.id) } }
                    neighbour(channel, -1)?.let { scope.launch { tv.prefetch(it.id) } }
                }
                .onFailure { message = "${channel.name}: ${it.message}" }
        }
    }

    fun playArchive(programme: Programme) {
        current = channels.firstOrNull { it.id == programme.channelId } ?: current
        archive = programme
        listOpen = false
        closeGuide()
        showBanner()
        playJob?.cancel()
        playJob = scope.launch {
            runCatching { tv.archiveStream(programme) }
                .onSuccess { start(it) }
                .onFailure { message = "${programme.title}: ${it.message}" }
        }
    }

    /** The programme on air now on [channel], from its beginning: Oneplay opens it like a recording. */
    fun startOver(channel: Channel) {
        val programme = nowPlaying[channel.id]?.takeIf { it.payload != null }
        if (programme == null) {
            message = "${channel.name}: tento pořad nejde pustit od začátku"
            return
        }
        playArchive(programme)
    }

    private fun start(stream: Stream) {
        val item = MediaItem.Builder().setUri(stream.url).setMimeType(stream.mimeType)
        stream.licenseUrl?.let { license ->
            item.setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .setLicenseUri(license)
                    .setLicenseRequestHeaders(stream.licenseToken?.let { mapOf("x-axdrm-message" to it) }.orEmpty())
                    .build()
            )
        }
        player.setMediaItem(item.build())
        player.prepare()
        player.play()
    }

    fun openGuide(channel: Channel) {
        guideChannel = channel
        guide = null
        guideJob?.cancel()
        guideJob = scope.launch {
            guide = runCatching { tv.guide(channel) }
                .onFailure { message = "Program ${channel.name}: ${it.message}" }
                .getOrDefault(emptyList())
        }
    }

    fun closeGuide() {
        guideJob?.cancel()
        guideChannel = null
        guide = null
    }

    /** Archive only: skip by [ms] within the recording. */
    fun seek(ms: Long) {
        if (archive == null) return
        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + ms).coerceIn(0, duration))
        showBanner()
    }

    fun togglePause() {
        if (player.isPlaying) player.pause() else player.play()
        showBanner()
    }

    fun zap(step: Int) {
        val target = current?.let { neighbour(it, step) } ?: channels.firstOrNull() ?: return
        play(target)
    }

    fun digit(d: Int) {
        digits = (digits + d).takeLast(3)
        digitsJob?.cancel()
        digitsJob = scope.launch {
            delay(1_500)
            val number = digits.toIntOrNull()
            digits = ""
            channels.firstOrNull { it.number == number }?.let { play(it) }
        }
    }

    fun showBanner() {
        bannerVisible = true
        bannerJob?.cancel()
        bannerJob = scope.launch {
            delay(4_000)
            bannerVisible = false
        }
    }

    /**
     * Zapping goes through "Moje kanály" once there are at least 3, in number order; the other channels
     * stay reachable from the list and by number. From a channel outside them, the nearest one in that direction.
     */
    private fun neighbour(channel: Channel, step: Int): Channel? {
        val list = channels.filter { it.id in favourites }.takeIf { it.size >= 3 } ?: channels
        if (list.isEmpty()) return null
        val index = list.indexOfFirst { it.id == channel.id }
        if (index < 0) {
            return if (step > 0) list.firstOrNull { it.number > channel.number } ?: list.first()
            else list.lastOrNull { it.number < channel.number } ?: list.last()
        }
        return list[(index + step).mod(list.size)]
    }
}
