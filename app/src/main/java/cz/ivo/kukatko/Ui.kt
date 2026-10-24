package cz.ivo.kukatko

import android.view.KeyEvent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemBorder
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.LocalContentColor
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

/**
 * Same look as the simplified, elderly-friendly Jellyfin fork "Dželi Beli"
 * (SimpleComponents.kt): large type, yellow focus with black text, a slight grow and a pulsing white
 * frame on the focused row, and key hints on screen.
 */
private object Look {
    val Panel = Color(0xF2111111)
    val Text = Color.White
    val Dim = Color(0xFFBBBBBB)
    val Disabled = Color(0xFF8A8A8A)
    val Focus = Color(0xFFFFD400)
    val OnFocus = Color.Black
    val Selected = Color(0xFF333333)
    /** Favourite channels and shows (picked automatically from what is watched). */
    val Favourite = Color(0xFF1B5E20)
    val FavouriteSelected = Color(0xFF2E7D32)
    val FavouriteDim = Color(0xFF12361A)
    val Live = Color(0xFFFF4D4D)
    val Progress = Color(0xFF42A5F5)
    val Track = Color(0x66FFFFFF)
    val Shape = RoundedCornerShape(12.dp)

    val Huge = 44.sp
    val Big = 30.sp
    val Name = 28.sp
    val Body = 22.sp
    val Hint = 20.sp
}

/** [favourite] rows are green; focus is yellow on every row. */
@Composable
private fun rowColors(favourite: Boolean = false) = ListItemDefaults.colors(
    containerColor = if (favourite) Look.Favourite else Color.Transparent,
    contentColor = Look.Text,
    focusedContainerColor = Look.Focus,
    focusedContentColor = Look.OnFocus,
    selectedContainerColor = if (favourite) Look.FavouriteSelected else Look.Selected,
    selectedContentColor = Look.Text,
    focusedSelectedContainerColor = Look.Focus,
    focusedSelectedContentColor = Look.OnFocus,
    disabledContainerColor = if (favourite) Look.FavouriteDim else Color.Transparent,
    disabledContentColor = Look.Disabled,
)

@Composable
private fun rowScale() = ListItemDefaults.scale(focusedScale = 1.03f, focusedSelectedScale = 1.03f)

@Composable
private fun rowShape() = ListItemDefaults.shape(shape = Look.Shape)

/** Pulsing white frame so the focused row is found at a glance; only the focused row animates. */
@Composable
private fun rowBorder(focused: Boolean): ListItemBorder {
    val alpha = if (focused) {
        val pulse by rememberInfiniteTransition(label = "focus pulse").animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "focus frame",
        )
        pulse
    } else 0f
    val frame = Border(BorderStroke(4.dp, Color.White.copy(alpha = alpha)), shape = Look.Shape)
    return ListItemDefaults.border(focusedBorder = frame, focusedSelectedBorder = frame)
}

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
            .pointerInput(Unit) { detectTapGestures { state.openList() } }
            .onPreviewKeyEvent { event ->
                val code = event.nativeKeyEvent.keyCode
                // CH+/− switch live channels from anywhere and close whatever is open: always the way back to live TV.
                if (code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_CHANNEL_DOWN) {
                    if (event.type == KeyEventType.KeyDown) {
                        state.closeGuide()
                        state.searchOpen = false
                        state.listOpen = false
                        state.zap(if (code == KeyEvent.KEYCODE_CHANNEL_UP) 1 else -1)
                    }
                    return@onPreviewKeyEvent true
                }
                if (state.listOpen) return@onPreviewKeyEvent false
                // OK opens the list on key *up*: opening it on key down would hand the key-up to the
                // freshly focused channel row, which "clicks" it and closes the list again.
                if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_GUIDE) {
                    if (event.type == KeyEventType.KeyDown) okPressed = true
                    else if (event.type == KeyEventType.KeyUp && okPressed) {
                        okPressed = false
                        state.openList()
                    }
                    return@onPreviewKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> state.zap(1)
                    KeyEvent.KEYCODE_DPAD_DOWN -> state.zap(-1)
                    // In a recording Left/Right skip 30 s. On live TV they only show the banner: no screen opens by surprise.
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
                fontSize = 80.sp,
                fontWeight = FontWeight.Bold,
                color = Look.OnFocus,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(40.dp)
                    .background(Look.Focus, RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp),
            )
        }
        state.message?.let {
            Text(
                it,
                color = Color.White,
                fontSize = Look.Body,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(32.dp)
                    .background(Color(0xFFB00000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        if (state.listOpen && state.searchOpen) {
            SearchScreen(state)
        } else if (state.listOpen) {
            val guideOpen = state.guideChannel != null
            Row(Modifier.align(Alignment.CenterStart).fillMaxHeight().then(if (guideOpen) Modifier.fillMaxWidth() else Modifier)) {
                ChannelList(state, Modifier.width(if (guideOpen) 500.dp else 820.dp))
                state.guideChannel?.let { GuidePanel(state, it, Modifier.weight(1f)) }
            }
        }
    }
    // Back from an archive recording returns to live TV on the same channel.
    androidx.activity.compose.BackHandler(enabled = state.archive != null && !state.listOpen) {
        state.current?.let { state.play(it) }
    }
    // On live TV Back never leaves the app (an elderly viewer would end up on the launcher); Home still does.
    androidx.activity.compose.BackHandler(enabled = state.archive == null && !state.listOpen) {
        state.showBanner()
    }
    // Take focus back for zapping whenever the list closes.
    LaunchedEffect(state.listOpen) { if (!state.listOpen) focus.requestFocus() }
}

@Composable
private fun ChannelList(state: TvState, modifier: Modifier) {
    // The channel that should hold focus: the current one, or the one whose guide was last opened.
    var focusId by remember { mutableStateOf(state.guideChannel?.id ?: state.current?.id) }
    // "Hledat pořad" first, then "Moje kanály" (picked by watch time) and the rest; one plain list until there are any.
    val rows: List<Any> = remember(state.channels, state.favourites) {
        val mine = state.channels.filter { it.id in state.favourites }
        listOf(SearchEntry) + if (mine.isEmpty()) state.channels
        else listOf("Moje kanály") + mine + "Ostatní kanály" + state.channels.filter { it.id !in state.favourites }
    }
    val focusIndex = rows.indexOfFirst { it is Channel && it.id == focusId }
        .takeIf { it >= 0 } ?: rows.indexOfFirst { it is Channel }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (focusIndex - 2).coerceAtLeast(0))
    val currentFocus = remember { FocusRequester() }
    val now = System.currentTimeMillis() / 1000
    val guideOpen = state.guideChannel != null

    androidx.activity.compose.BackHandler {
        if (state.guideChannel != null) state.closeGuide() else state.listOpen = false
    }
    Column(
        modifier
            .fillMaxHeight()
            .background(Look.Panel)
            .onPreviewKeyEvent {
                // Menu/guide closes the list too; Back is handled by BackHandler.
                if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_GUIDE) {
                    state.listOpen = false; true
                } else false
            },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 16.dp, horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(rows, key = { _, row -> if (row is Channel) row.id else "header $row" }) { index, row ->
                if (row == SearchEntry) {
                    var focused by remember { mutableStateOf(false) }
                    ListItem(
                        selected = false,
                        onClick = { state.openSearch() },
                        colors = rowColors(),
                        shape = rowShape(),
                        border = rowBorder(focused),
                        scale = rowScale(),
                        modifier = Modifier.onFocusChanged { focused = it.isFocused },
                        headlineContent = { Text("🔍  Hledat pořad", fontSize = Look.Name, fontWeight = FontWeight.Bold) },
                    )
                } else if (row is String) {
                    Text(row, fontSize = Look.Name, fontWeight = FontWeight.Bold, color = Look.Focus, modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp))
                } else if (row is Channel) {
                    ChannelRow(
                        state, row, compact = guideOpen, now = now,
                        modifier = if (index == focusIndex) Modifier.focusRequester(currentFocus) else Modifier,
                        openGuide = { focusId = row.id; state.openGuide(row) },
                    )
                }
            }
        }
        if (!guideOpen) HintBar("OK" to "Sledovat", "→" to "Další volby", "Zpět" to "Zavřít")
    }
    // On open, and again when the guide closes, focus goes back to the channel row.
    LaunchedEffect(state.guideChannel) { if (state.guideChannel == null) runCatching { currentFocus.requestFocus() } }
}

/**
 * A channel in the list: OK on the row plays it live. The highlighted row shows its other choices as
 * buttons beside it (Right reaches them), so nothing is hidden behind a direction or a long press.
 * [compact] is the list next to an open guide: the row looks the same, but has no buttons and Right shows
 * this channel's programmes.
 */
@Composable
private fun ChannelRow(state: TvState, channel: Channel, compact: Boolean, now: Long, modifier: Modifier, openGuide: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var focusWithin by remember { mutableStateOf(false) }
    val rowFocus = remember { FocusRequester() }
    val programme = state.nowPlaying[channel.id]
    Row(
        Modifier.onFocusChanged { focusWithin = it.hasFocus },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ListItem(
            selected = channel.id == state.current?.id,
            onClick = {
                state.listOpen = false
                state.closeGuide()
                if (channel.id != state.current?.id || state.archive != null) state.play(channel)
            },
            colors = rowColors(favourite = channel.id in state.favourites),
            shape = rowShape(),
            border = rowBorder(focused),
            scale = rowScale(),
            modifier = modifier
                .weight(1f)
                .focusRequester(rowFocus)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent {
                    if (compact && it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        openGuide(); true
                    } else false
                },
            leadingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${channel.number}", fontSize = Look.Name, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp))
                    AsyncImage(model = channel.logo, contentDescription = null, modifier = Modifier.size(width = 72.dp, height = 44.dp))
                }
            },
            headlineContent = {
                Text(channel.name, fontSize = Look.Name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = programme?.let {
                {
                    Column {
                        Text((if (it.title in state.favouriteShows) "★ " else "") + it.title, fontSize = Look.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Progress(it.progress(now), LocalContentColor.current)
                    }
                }
            },
        )
        if (focusWithin && !compact) {
            if (programme?.payload != null) {
                RowButton("⏮ Od začátku") {
                    state.listOpen = false
                    state.startOver(channel)
                }
            }
            RowButton("Zmeškané pořady") {
                // Hand focus back to the row before the buttons disappear; the guide then takes it.
                runCatching { rowFocus.requestFocus() }
                openGuide()
            }
        }
    }
}

@Composable
private fun buttonColors() = ButtonDefaults.colors(
    containerColor = Look.Selected,
    contentColor = Look.Text,
    focusedContainerColor = Look.Focus,
    focusedContentColor = Look.OnFocus,
)

@Composable
private fun RowButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = ButtonDefaults.shape(shape = Look.Shape),
        colors = buttonColors(),
        scale = ButtonDefaults.scale(focusedScale = 1.05f),
    ) {
        Text(label, fontSize = Look.Body, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}

/** Marks the "Hledat pořad" row on top of the channel list. */
private object SearchEntry

/**
 * "Hledat pořad" without a keyboard: big letters picked with the arrows and OK (Oneplay's search needs no
 * diacritics, so A–Z is enough), results on the right after 2 letters, recent searches while nothing is typed.
 * The remote's digits and a real keyboard type too. Back returns to the channel list.
 */
@Composable
private fun SearchScreen(state: TvState) {
    val firstKey = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }
    // After picking a recent search, focus moves to its first result once it arrives.
    var jumpToResults by remember { mutableStateOf(false) }
    // The OK that opened the search lets go on the "A" key; ignore key-ups until a key goes down here.
    var armed by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis() / 1000

    androidx.activity.compose.BackHandler { state.searchOpen = false }
    Column(
        Modifier
            .fillMaxSize()
            .background(Look.Panel)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent !armed
                armed = true
                val code = event.nativeKeyEvent.keyCode
                when (code) {
                    in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> state.search(state.query + ('A' + (code - KeyEvent.KEYCODE_A)))
                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> state.search(state.query + (code - KeyEvent.KEYCODE_0))
                    KeyEvent.KEYCODE_SPACE -> state.search(state.query + " ")
                    KeyEvent.KEYCODE_DEL -> state.search(state.query.dropLast(1))
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 40.dp, end = 40.dp, top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Hledat pořad:", fontSize = Look.Big, fontWeight = FontWeight.Bold, color = Look.Focus)
            Spacer(Modifier.width(20.dp))
            Text(
                state.query + "▏",
                fontSize = Look.Huge,
                fontWeight = FontWeight.Bold,
                color = Look.Text,
                maxLines = 1,
                modifier = Modifier.weight(1f).background(Color.Black, Look.Shape).padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }
        Row(Modifier.weight(1f).padding(horizontal = 30.dp), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
            LetterGrid(state, firstKey)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                val results = state.results
                when {
                    state.query.trim().length < 2 && state.recentSearches.isNotEmpty() -> {
                        SearchNote("Naposledy hledané", Look.Focus)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(state.recentSearches) { recent ->
                                var focused by remember { mutableStateOf(false) }
                                ListItem(
                                    selected = false,
                                    onClick = { jumpToResults = true; state.search(recent, now = true) },
                                    colors = rowColors(),
                                    shape = rowShape(),
                                    border = rowBorder(focused),
                                    scale = rowScale(),
                                    modifier = Modifier.onFocusChanged { focused = it.isFocused },
                                    headlineContent = { Text(recent, fontSize = Look.Name, fontWeight = FontWeight.Bold) },
                                )
                            }
                        }
                    }
                    state.query.trim().length < 2 -> SearchNote("Vyberte aspoň 2 písmena názvu, třeba U L I pro Ulici.", Look.Text)
                    results == null -> SearchNote("Hledám…", Look.Text)
                    results.isEmpty() && !state.searching -> SearchNote("Nic nenalezeno v archivu. Zkuste jiná písmena.", Look.Text)
                    else -> {
                        SearchNote(if (state.searching) "Hledám…" else "Nalezeno v archivu", Look.Focus)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                            itemsIndexed(results) { index, hit ->
                                SearchResultRow(state, hit, now, if (index == 0) Modifier.focusRequester(firstResult) else Modifier)
                            }
                        }
                    }
                }
            }
        }
        HintBar("OK" to "Napsat písmeno", "→" to "Výsledky", "Zpět" to "Zavřít")
    }
    LaunchedEffect(Unit) { runCatching { firstKey.requestFocus() } }
    LaunchedEffect(state.results) {
        if (jumpToResults && !state.results.isNullOrEmpty()) {
            jumpToResults = false
            runCatching { firstResult.requestFocus() }
        }
    }
}

@Composable
private fun SearchNote(text: String, color: Color) {
    Text(text, fontSize = Look.Name, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 8.dp))
}

/** A–Z in rows of six, then space and delete; big enough to hit with the arrows. */
@Composable
private fun LetterGrid(state: TvState, firstKey: FocusRequester) {
    val rows = ('A'..'Z').map { it.toString() }.chunked(6)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEachIndexed { r, letters ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                letters.forEachIndexed { c, letter ->
                    KeyButton(letter, Modifier.size(68.dp).then(if (r == 0 && c == 0) Modifier.focusRequester(firstKey) else Modifier)) {
                        state.search(state.query + letter)
                    }
                }
                if (r == rows.lastIndex) {
                    KeyButton("mezera", Modifier.size(width = 146.dp, height = 68.dp)) { state.search(state.query + " ") }
                    KeyButton("⌫ smazat", Modifier.size(width = 146.dp, height = 68.dp)) { state.search(state.query.dropLast(1)) }
                }
            }
        }
    }
}

@Composable
private fun KeyButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(0.dp),
        shape = ButtonDefaults.shape(shape = Look.Shape),
        colors = buttonColors(),
        scale = ButtonDefaults.scale(focusedScale = 1.1f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, fontSize = if (label.length == 1) 34.sp else Look.Body, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        }
    }
}

/** A found broadcast, laid out like a channel row: logo, title, then episode · channel · when. Green for a favourite show. */
@Composable
private fun SearchResultRow(state: TvState, hit: SearchHit, now: Long, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val channel = state.channels.firstOrNull { it.name.equals(hit.channelName, ignoreCase = true) }
    ListItem(
        selected = false,
        onClick = { state.playFound(hit) },
        colors = rowColors(favourite = hit.title in state.favouriteShows),
        shape = rowShape(),
        border = rowBorder(focused),
        scale = rowScale(),
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        leadingContent = {
            AsyncImage(model = channel?.logo, contentDescription = null, modifier = Modifier.size(width = 72.dp, height = 44.dp))
        },
        headlineContent = { Text(hit.title, fontSize = Look.Name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 32.sp) },
        supportingContent = {
            // The logo says the channel; its name only when there is no logo.
            val parts = listOfNotNull(hit.detail, hit.channelName.takeIf { channel?.logo == null })
            if (parts.isNotEmpty()) Text(parts.joinToString(" · "), fontSize = Look.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        // When it was on, big in its own column so it is never cut off: "dnes" over "08:35".
        trailingContent = hit.start.takeIf { it > 0 }?.let { start ->
            {
                val (day, time) = dayAndTime(start)
                Column(horizontalAlignment = Alignment.End) {
                    Text(day, fontSize = Look.Body, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    Text(time, fontSize = Look.Name, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
            }
        },
    )
}

/** "dnes 19:00", "včera 19:00", otherwise "čtvrtek 22. 10. 19:00". */
private fun whenText(epochSec: Long): String = dayAndTime(epochSec).let { (day, time) -> "$day $time" }

/** ("dnes", "19:00"), ("včera", "19:00"), otherwise ("čtvrtek 22. 10.", "19:00"). */
private fun dayAndTime(epochSec: Long): Pair<String, String> {
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochSecond(epochSec).atZone(zone)
    val today = LocalDate.now(zone)
    val day = when (at.toLocalDate()) {
        today -> "dnes"
        today.minusDays(1) -> "včera"
        else -> DateTimeFormatter.ofPattern("EEEE d. M.", Locale.forLanguageTag("cs")).format(at)
    }
    return day to DateTimeFormatter.ofPattern("HH:mm").format(at)
}

@Composable
private fun GuidePanel(state: TvState, channel: Channel, modifier: Modifier) {
    val items = state.guide
    val nowSec = System.currentTimeMillis() / 1000
    val archiveFrom = nowSec - 7 * 86_400
    val time = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Column(
        modifier
            .fillMaxHeight()
            .background(Color.Black)
            .border(2.dp, Look.Focus)
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    state.closeGuide(); true
                } else false
            },
    ) {
        Column(Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 8.dp)) {
            Text(channel.name, fontSize = Look.Big, fontWeight = FontWeight.Bold, color = Look.Text)
            Text("Archiv: pořady až 7 dní zpět", fontSize = Look.Body, color = Look.Dim)
        }
        when {
            items == null -> Text("Načítám program…", fontSize = Look.Name, color = Look.Text, modifier = Modifier.padding(20.dp).weight(1f))
            items.isEmpty() -> Text("Program není k dispozici", fontSize = Look.Name, color = Look.Text, modifier = Modifier.padding(20.dp).weight(1f))
            else -> {
                // Day headers between programmes; focus starts on what's on now.
                val rows = remember(items) { withDayHeaders(items) }
                val nowRow = rows.indexOfFirst { it is Programme && it.start <= nowSec && it.end > nowSec }
                    .takeIf { it >= 0 } ?: rows.indexOfLast { it is Programme && it.end <= nowSec }.coerceAtLeast(0)
                val listState = rememberLazyListState((nowRow - 2).coerceAtLeast(0))
                val nowFocus = remember { FocusRequester() }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(rows) { index, row ->
                        if (row is String) {
                            Text(row, fontSize = Look.Name, fontWeight = FontWeight.Bold, color = Look.Focus, modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp))
                        } else if (row is Programme) {
                            val live = row.start <= nowSec && row.end > nowSec
                            val playable = live || (row.end <= nowSec && row.start >= archiveFrom && row.payload != null)
                            var focused by remember { mutableStateOf(false) }
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
                                colors = rowColors(favourite = row.title in state.favouriteShows),
                                shape = rowShape(),
                                border = rowBorder(focused),
                                scale = rowScale(),
                                modifier = (if (index == nowRow) Modifier.focusRequester(nowFocus) else Modifier)
                                    .onFocusChanged { focused = it.isFocused },
                                leadingContent = {
                                    Text(time.format(Date(row.start * 1000)), fontSize = Look.Name, fontWeight = FontWeight.Bold, modifier = Modifier.width(80.dp))
                                },
                                headlineContent = {
                                    Text(row.title, fontSize = Look.Name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = if (live) {
                                    { Progress(row.progress(nowSec), LocalContentColor.current) }
                                } else null,
                                trailingContent = when {
                                    live -> { { Text("● ŽIVĚ", fontSize = Look.Body, fontWeight = FontWeight.Bold, color = if (focused) Look.OnFocus else Look.Live) } }
                                    focused && playable -> { { Text("▶ Přehrát", fontSize = Look.Body, fontWeight = FontWeight.Bold) } }
                                    else -> null
                                },
                            )
                        }
                    }
                }
                LaunchedEffect(items) { runCatching { nowFocus.requestFocus() } }
            }
        }
        HintBar("OK" to "Přehrát", "←" to "Kanály", "Zpět" to "Zavřít")
    }
}

/** A row of key caps with what they do, at the bottom of a panel or banner. */
@Composable
private fun HintBar(vararg hints: Pair<String, String>, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        hints.forEach { (key, label) -> KeyHint(key, label) }
    }
}

@Composable
private fun KeyHint(key: String, label: String, size: TextUnit = Look.Hint) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            key,
            fontSize = size,
            fontWeight = FontWeight.Bold,
            color = Look.OnFocus,
            modifier = Modifier
                .background(Look.Focus, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 2.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = size, color = Look.Text, maxLines = 1, softWrap = false)
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

/** Shared frame for the bottom banners: solid black so text stays readable over any picture. */
@Composable
private fun BannerFrame(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(24.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Look.Panel))))
        Row(
            Modifier
                .fillMaxWidth()
                .background(Look.Panel)
                .padding(horizontal = 40.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
private fun Banner(channel: Channel, programme: Programme?, modifier: Modifier) {
    val time = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    BannerFrame(modifier) {
        Text("${channel.number}", fontSize = Look.Huge, fontWeight = FontWeight.Bold, color = Look.Focus)
        Spacer(Modifier.width(24.dp))
        AsyncImage(model = channel.logo, contentDescription = null, modifier = Modifier.size(width = 110.dp, height = 66.dp))
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(channel.name, fontSize = Look.Big, fontWeight = FontWeight.Bold, color = Look.Text)
            if (programme != null) {
                Text(programme.title, fontSize = Look.Name, color = Look.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(time.format(Date(programme.start * 1000)), fontSize = Look.Body, color = Look.Text)
                    Box(Modifier.weight(1f).padding(horizontal = 16.dp)) { Progress(programme.progress(System.currentTimeMillis() / 1000)) }
                    Text(time.format(Date(programme.end * 1000)), fontSize = Look.Body, color = Look.Text)
                }
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                KeyHint("↑ ↓", "Přepnout")
                KeyHint("OK", "Kanály a zmeškané pořady")
            }
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
    val date = remember(programme) { if (programme.start > 0) " · " + whenText(programme.start) else "" }
    BannerFrame(modifier) {
        AsyncImage(model = channel.logo, contentDescription = null, modifier = Modifier.size(width = 110.dp, height = 66.dp))
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            val onAir = programme.end > System.currentTimeMillis() / 1000
            Text("${if (onAir) "OD ZAČÁTKU" else "ZE ZÁZNAMU"} · ${channel.name}$date", fontSize = Look.Body, fontWeight = FontWeight.Bold, color = Look.Focus)
            Text((if (paused) "⏸  POZASTAVENO: " else "") + programme.title, fontSize = Look.Big, fontWeight = FontWeight.Bold, color = Look.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(clock(position), fontSize = Look.Body, color = Look.Text)
                Box(Modifier.weight(1f).padding(horizontal = 16.dp)) { Progress(if (duration > 0) position.toFloat() / duration else 0f) }
                Text(clock(duration), fontSize = Look.Body, color = Look.Text)
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                KeyHint("← →", "30 s")
                KeyHint("⏯", "Pauza")
                KeyHint("Zpět", "Živé vysílání")
                KeyHint("OK", "Kanály")
            }
        }
    }
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun Progress(fraction: Float, color: Color = Look.Progress) {
    Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(6.dp).background(Look.Track, RoundedCornerShape(3.dp))) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(color, RoundedCornerShape(3.dp)))
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
    Box(Modifier.fillMaxSize().background(Color.Black).padding(top = 32.dp), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Kukátko", fontSize = Look.Huge, fontWeight = FontWeight.Bold, color = Look.Text)
            Text("Přihlášení účtem Oneplay (dřív O2 TV)", fontSize = Look.Body, color = Look.Text)
            state.message?.let { Text(it, fontSize = Look.Body, color = Look.Live) }
            Field("E-mail", user, { user = it }, Modifier.focusRequester(first), onDone = { second.requestFocus() })
            Field("Heslo", password, { password = it }, Modifier.focusRequester(second), password = true,
                onDone = { if (canLogin) state.login(user.trim(), password) })
            Button(onClick = { state.login(user.trim(), password) }, enabled = canLogin) {
                Text(if (state.busy) "Přihlašuji…" else "Přihlásit", fontSize = Look.Name)
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
        Text(label, color = Look.Text, fontSize = Look.Body)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Look.Text, fontSize = Look.Name),
            cursorBrush = SolidColor(Look.Focus),
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
                .border(3.dp, if (focused) Look.Focus else Look.Disabled, RoundedCornerShape(8.dp))
                .padding(14.dp),
        )
    }
}
