package cz.ivo.kukatko

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

@Composable
fun App(state: TvState, player: ExoPlayer) {
    if (state.loggedIn) WatchScreen(state, player) else LoginScreen(state)
}

@Composable
private fun WatchScreen(state: TvState, player: ExoPlayer) {
    val focus = remember { FocusRequester() }
    var okPressed by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            // Mouse / touch: a click on the picture opens the channel list.
            .pointerInput(Unit) { detectTapGestures { state.listOpen = true } }
            .onPreviewKeyEvent { event ->
                if (state.listOpen) return@onPreviewKeyEvent false
                val code = event.nativeKeyEvent.keyCode
                // OK opens the list on key *up*: opening it on key down would hand the key-up to the
                // freshly focused channel row, which "clicks" it and closes the list again.
                if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_GUIDE) {
                    if (event.type == KeyEventType.KeyDown) okPressed = true
                    else if (event.type == KeyEventType.KeyUp && okPressed) {
                        okPressed = false
                        state.listOpen = true
                    }
                    return@onPreviewKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> state.zap(1)
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> state.zap(-1)
                    // Left/Right skip 30 s in an archive recording; on live TV they just show the banner.
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (state.archive != null) state.seek(-30_000) else state.showBanner()
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (state.archive != null) state.seek(30_000) else state.showBanner()
                    KeyEvent.KEYCODE_MEDIA_REWIND -> state.seek(-300_000)
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> state.seek(300_000)
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
                    KeyEvent.KEYCODE_SPACE -> state.togglePause()
                    KeyEvent.KEYCODE_INFO -> state.showBanner()
                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> state.digit(code - KeyEvent.KEYCODE_0)
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
    ) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    isFocusable = false
                    keepScreenOn = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        val current = state.current
        if ((state.bannerVisible || state.paused) && current != null && !state.listOpen) {
            val archive = state.archive
            if (archive != null) {
                ArchiveBanner(current, archive, player, state.paused, Modifier.align(Alignment.BottomCenter))
            } else {
                Banner(current, state.nowPlaying[current.id], Modifier.align(Alignment.BottomCenter))
            }
        }
        if (state.digits.isNotEmpty()) {
            Text(
                state.digits,
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(48.dp),
            )
        }
        state.message?.let {
            Text(
                it,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(32.dp)
                    .background(Color(0xCC8B0000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (state.listOpen) {
            Row(Modifier.align(Alignment.CenterStart).fillMaxHeight()) {
                ChannelList(state, Modifier)
                state.guideChannel?.let { GuidePanel(state, it) }
            }
        }
    }
    // Back from an archive recording returns to live TV on the same channel.
    androidx.activity.compose.BackHandler(enabled = state.archive != null && !state.listOpen) {
        state.current?.let { state.play(it) }
    }
    // Take focus back for zapping whenever the list closes.
    LaunchedEffect(state.listOpen) { if (!state.listOpen) focus.requestFocus() }
}

@Composable
private fun ChannelList(state: TvState, modifier: Modifier) {
    // The channel that should hold focus: the current one, or the one whose guide was last opened.
    var focusId by remember { mutableStateOf(state.current?.id) }
    val focusIndex = state.channels.indexOfFirst { it.id == focusId }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (focusIndex - 3).coerceAtLeast(0))
    val currentFocus = remember { FocusRequester() }
    val now = System.currentTimeMillis() / 1000

    androidx.activity.compose.BackHandler {
        if (state.guideChannel != null) state.closeGuide() else state.listOpen = false
    }
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxHeight()
            .width(520.dp)
            .background(Brush.horizontalGradient(listOf(Color(0xF0101418), Color(0xC0101418))))
            .onPreviewKeyEvent {
                // Menu/guide closes the list too; Back is handled by BackHandler.
                if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_GUIDE) {
                    state.listOpen = false; true
                } else false
            },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 24.dp, horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(state.channels, key = { _, ch -> ch.id }) { index, channel ->
            val programme = state.nowPlaying[channel.id]
            ListItem(
                selected = channel.id == state.current?.id,
                onClick = {
                    state.listOpen = false
                    state.closeGuide()
                    if (channel.id != state.current?.id || state.archive != null) state.play(channel)
                },
                onLongClick = { focusId = channel.id; state.openGuide(channel) },
                modifier = (if (index == focusIndex) Modifier.focusRequester(currentFocus) else Modifier)
                    .onPreviewKeyEvent {
                        // Right opens this channel's guide (archive) next to the list.
                        if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                            focusId = channel.id
                            state.openGuide(channel)
                            true
                        } else false
                    },
                leadingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${channel.number}", modifier = Modifier.width(36.dp), style = MaterialTheme.typography.labelLarge)
                        AsyncImage(model = channel.logo, contentDescription = null, modifier = Modifier.size(width = 54.dp, height = 36.dp))
                    }
                },
                headlineContent = { Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = programme?.let {
                    {
                        Column {
                            Text(it.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Progress(it.progress(now))
                        }
                    }
                },
            )
        }
    }
    // On open, and again when the guide closes, focus goes back to the channel row.
    LaunchedEffect(state.guideChannel) { if (state.guideChannel == null) runCatching { currentFocus.requestFocus() } }
}

@Composable
private fun GuidePanel(state: TvState, channel: Channel) {
    val items = state.guide
    val nowSec = System.currentTimeMillis() / 1000
    val archiveFrom = nowSec - 7 * 86_400
    val time = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Column(
        Modifier
            .fillMaxHeight()
            .width(640.dp)
            .background(Color(0xF0161B21))
            .padding(vertical = 24.dp, horizontal = 12.dp)
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    state.closeGuide(); true
                } else false
            },
    ) {
        Text("${channel.name} · archiv 7 dní", fontSize = 20.sp, color = Color.White, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
        when {
            items == null -> Text("Načítám program…", color = Color.Gray, modifier = Modifier.padding(12.dp))
            items.isEmpty() -> Text("Program není k dispozici", color = Color.Gray, modifier = Modifier.padding(12.dp))
            else -> {
                // Day headers between programmes; focus starts on what's on now.
                val rows = remember(items) { withDayHeaders(items) }
                val nowRow = rows.indexOfFirst { it is Programme && it.start <= nowSec && it.end > nowSec }
                    .takeIf { it >= 0 } ?: rows.indexOfLast { it is Programme && it.end <= nowSec }.coerceAtLeast(0)
                val listState = rememberLazyListState((nowRow - 4).coerceAtLeast(0))
                val nowFocus = remember { FocusRequester() }
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(rows) { index, row ->
                        if (row is String) {
                            Text(row, color = Color(0xFF3D8BFF), fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp))
                        } else if (row is Programme) {
                            val live = row.start <= nowSec && row.end > nowSec
                            val playable = live || (row.end <= nowSec && row.start >= archiveFrom && row.payload != null)
                            ListItem(
                                selected = live,
                                enabled = playable,
                                onClick = {
                                    if (live) {
                                        state.listOpen = false
                                        state.closeGuide()
                                        state.play(channel)
                                    } else {
                                        state.playArchive(row)
                                    }
                                },
                                modifier = if (index == nowRow) Modifier.focusRequester(nowFocus) else Modifier,
                                leadingContent = { Text(time.format(Date(row.start * 1000)), modifier = Modifier.width(56.dp)) },
                                headlineContent = { Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = if (live) {
                                    { Progress(row.progress(nowSec)) }
                                } else null,
                            )
                        }
                    }
                }
                LaunchedEffect(items) { runCatching { nowFocus.requestFocus() } }
            }
        }
    }
}

private fun withDayHeaders(items: List<Programme>): List<Any> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val dayFormat = DateTimeFormatter.ofPattern("EEEE d. M.", Locale.forLanguageTag("cs"))
    var lastDay: LocalDate? = null
    return buildList {
        for (item in items) {
            val day = Instant.ofEpochSecond(item.start).atZone(zone).toLocalDate()
            if (day != lastDay) {
                add(
                    when (day) {
                        today -> "Dnes"
                        today.minusDays(1) -> "Včera"
                        today.plusDays(1) -> "Zítra"
                        else -> dayFormat.format(day).replaceFirstChar { it.uppercase() }
                    }
                )
                lastDay = day
            }
            add(item)
        }
    }
}

@Composable
private fun ArchiveBanner(channel: Channel, programme: Programme, player: ExoPlayer, paused: Boolean, modifier: Modifier) {
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0)
            delay(500)
        }
    }
    val date = remember(programme) {
        DateTimeFormatter.ofPattern("EEEE d. M. HH:mm", Locale.forLanguageTag("cs"))
            .format(Instant.ofEpochSecond(programme.start).atZone(ZoneId.systemDefault()))
    }
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE0000000))))
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(model = channel.logo, contentDescription = null, modifier = Modifier.size(width = 90.dp, height = 60.dp))
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text("${channel.name} · ze záznamu · $date", fontSize = 16.sp, color = Color(0xFFDDDDDD))
            Text((if (paused) "⏸  " else "") + programme.title, fontSize = 22.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(clock(position), fontSize = 14.sp, color = Color.Gray)
                Box(Modifier.weight(1f).padding(horizontal = 12.dp)) { Progress(if (duration > 0) position.toFloat() / duration else 0f) }
                Text(clock(duration), fontSize = 14.sp, color = Color.Gray)
            }
            Text("←/→ 30 s · mezerník pauza · Zpět = živě", fontSize = 12.sp, color = Color.Gray)
        }
    }
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun Banner(channel: Channel, programme: Programme?, modifier: Modifier) {
    val time = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE0000000))))
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${channel.number}", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.width(20.dp))
        AsyncImage(model = channel.logo, contentDescription = null, modifier = Modifier.size(width = 90.dp, height = 60.dp))
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(channel.name, fontSize = 22.sp, color = Color.White)
            if (programme != null) {
                Text(programme.title, fontSize = 18.sp, color = Color(0xFFDDDDDD), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(time.format(Date(programme.start * 1000)), fontSize = 14.sp, color = Color.Gray)
                    Box(Modifier.weight(1f).padding(horizontal = 12.dp)) { Progress(programme.progress(System.currentTimeMillis() / 1000)) }
                    Text(time.format(Date(programme.end * 1000)), fontSize = 14.sp, color = Color.Gray)
                }
            }
        }
    }
}

@Composable
private fun Progress(fraction: Float) {
    Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp).background(Color(0x40FFFFFF))) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Color(0xFF3D8BFF)))
    }
}

@Composable
private fun LoginScreen(state: TvState) {
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    val second = remember { FocusRequester() }
    val canLogin = !state.busy && user.isNotBlank() && password.isNotEmpty()
    // Form sits at the top: the TV's on-screen keyboard takes the bottom half.
    Box(Modifier.fillMaxSize().background(Color(0xFF101418)).padding(top = 48.dp), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Kukátko", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Přihlášení účtem Oneplay (dřív O2 TV)", color = Color.Gray)
            state.message?.let { Text(it, color = Color(0xFFFF6B6B)) }
            Field("E-mail / uživatel", user, { user = it }, Modifier.focusRequester(first), onDone = { second.requestFocus() })
            Field("Heslo", password, { password = it }, Modifier.focusRequester(second), password = true,
                onDone = { if (canLogin) state.login(user.trim(), password) })
            Button(onClick = { state.login(user.trim(), password) }, enabled = canLogin) {
                Text(if (state.busy) "Přihlašuji…" else "Přihlásit")
            }
        }
    }
    LaunchedEffect(Unit) { first.requestFocus() }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    onDone: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column {
        Text(label, color = Color.Gray, fontSize = 14.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
            cursorBrush = SolidColor(Color.White),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else KeyboardType.Email,
                imeAction = if (password) ImeAction.Done else ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onAny = { onDone() }),
            modifier = modifier
                .onPreviewKeyEvent {
                    // Hardware Enter (PC keyboard in the emulator, some remotes) acts like the IME action.
                    if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER) {
                        onDone(); true
                    } else false
                }
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .border(2.dp, if (focused) Color.White else Color(0xFF444444), RoundedCornerShape(8.dp))
                .padding(12.dp),
        )
    }
}
