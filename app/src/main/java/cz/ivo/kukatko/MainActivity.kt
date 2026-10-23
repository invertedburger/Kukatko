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
        state = TvState(Tv(this), player, lifecycleScope)

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

class TvState(private val tv: Tv, private val player: ExoPlayer, private val scope: CoroutineScope) {
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

    private var playJob: Job? = null
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
                .onSuccess { loggedIn = true; resume() }
                .onFailure { message = it.message ?: "Přihlášení selhalo" }
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

    private fun neighbour(channel: Channel, step: Int): Channel? {
        if (channels.isEmpty()) return null
        val index = channels.indexOfFirst { it.id == channel.id }.coerceAtLeast(0)
        return channels[(index + step).mod(channels.size)]
    }
}
