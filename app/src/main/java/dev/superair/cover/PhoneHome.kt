package dev.superair.cover

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.ViewModule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import dev.superair.glasses.PAGE_MILLIS
import dev.superair.glasses.pageTurn
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.superair.Air
import dev.superair.glasses.FolderIcon
import dev.superair.glasses.Home
import dev.superair.glasses.Battery
import dev.superair.glasses.Notes
import androidx.compose.material.icons.rounded.Close
import dev.superair.glasses.RoundIcon
import dev.superair.glasses.Weather
import dev.superair.hud.HudTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Home, on the cover: the same pages as on the glasses, slot for slot, and the place to arrange
 * them. Tap opens. Swipe turns the page (on the glasses too). Hold an app and drag it: to an empty
 * slot it moves, onto another app it makes a folder, onto a folder it joins it, held at the side it
 * turns the page. Folders open on tap; their name is renamed on tap; hold an app in one to take it out.
 */
@Composable
fun PhoneHome(modifier: Modifier, opened: () -> Unit) {
    val shown = Home.shown()
    val pages = shown.size
    val page = Home.page.coerceIn(0, pages - 1)

    Box(modifier) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
            TopRow()
            if (Home.searching) SearchLine()
            if (Home.notificationsOpen) PhoneNotifications(Modifier.weight(1f).fillMaxWidth(), opened)
            else if (Home.settingsOpen) PhoneSettings() else {
                Grid(shown, page, Modifier.weight(1f).fillMaxWidth(), opened)
                Dots(pages, page)
            }
        }
        Home.openFolder?.let { id -> Home.folder(id)?.let { PhoneFolder(it, opened) } }
    }
}

// ------------------------------------------------------------------ the top

@Composable
private fun TopRow() {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val time = SimpleDateFormat(if (Home.clock24) "H:mm" else "h:mm", Locale.getDefault()).format(Date(now))
    val date = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(now))
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(time, style = HudTheme.mont(30f, FontWeight.Light))
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            BasicText(date, style = HudTheme.mont(10.5f, FontWeight.SemiBold, color = HudTheme.inkDim), maxLines = 1)
            Weather.now?.let { BasicText("${it.temperature}°  ${Weather.label(it.code)}",
                style = HudTheme.mont(10.5f, FontWeight.SemiBold, color = HudTheme.inkDim), maxLines = 1) }
        }
        Tool(Icons.Rounded.Search, lit = Home.searching) { if (Home.searching) Home.endSearch() else Home.startSearch() }
        Tool(when (Home.sort) {
            Home.Sort.CUSTOM -> Icons.Rounded.ViewModule
            Home.Sort.NAME -> Icons.Rounded.SortByAlpha
            Home.Sort.RECENT -> Icons.Rounded.History
            Home.Sort.MOST -> Icons.Rounded.TrendingUp
        }, lit = false) { Home.nextSort() }
        BatteryTool()
        Tool(Icons.Rounded.Settings, lit = Home.settingsOpen) { Home.toggleSettings() }
    }
}

@Composable
private fun Tool(icon: ImageVector, lit: Boolean, action: () -> Unit) {
    val view = LocalView.current
    Box(Modifier.padding(start = 6.dp).size(44.dp).clip(CircleShape)
        .background(if (lit) Color.White else Color.White.copy(alpha = 0.14f))
        .clickable { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); action() },
        contentAlignment = Alignment.Center) {
        Image(icon, null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(if (lit) Color.Black else Color.White))
    }
}

@Composable
private fun SearchLine() {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).height(38.dp).clip(RoundedCornerShape(19.dp))
        .background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Image(Icons.Rounded.Search, null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(HudTheme.inkDim))
        BasicText(Home.query.ifEmpty { "Type to search" },
            style = HudTheme.mont(13f, FontWeight.SemiBold, color = if (Home.query.isEmpty()) HudTheme.inkFaint else HudTheme.ink), maxLines = 1)
    }
}

// ------------------------------------------------------------------ the page

private class Lift(val fromPage: Int, val fromSlot: Int, val item: Home.Item) {
    var at by mutableStateOf(Offset.Zero)
}

@Composable
private fun Grid(shown: List<List<Home.Item?>>, page: Int, modifier: Modifier, opened: () -> Unit) {
    val view = LocalView.current
    var lift by remember { mutableStateOf<Lift?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val swipeX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(note) { if (note != null) { delay(2200); note = null } }

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val cellW = with(density) { maxWidth.toPx() } / Home.COLUMNS
        val cellH = with(density) { maxHeight.toPx() } / Home.ROWS
        val icon = minOf(maxWidth / Home.COLUMNS * 0.72f, maxHeight / Home.ROWS * 0.58f)
        val iconPx = with(density) { icon.toPx() }
        val edge = with(density) { 26.dp.toPx() }
        val swipe = with(density) { 56.dp.toPx() }

        /** Where a slot is drawn, in columns: rows of four sit half a column in, as on the glasses. */
        fun drawnAt(slot: Int): Float? {
            val rows = Home.shown().getOrNull(Home.page) ?: return (slot % Home.COLUMNS).toFloat()
            val r = slot / Home.COLUMNS
            return Home.rowPositions(rows.subList(r * Home.COLUMNS, (r + 1) * Home.COLUMNS))[slot % Home.COLUMNS]
        }
        /** The slot under a point, going by where things are drawn, not by plain columns. */
        fun slotAt(p: Offset): Int {
            val r = (p.y / cellH).toInt().coerceIn(0, Home.ROWS - 1)
            val rows = Home.shown().getOrNull(Home.page)
                ?: return r * Home.COLUMNS + (p.x / cellW).toInt().coerceIn(0, Home.COLUMNS - 1)
            val row = rows.subList(r * Home.COLUMNS, (r + 1) * Home.COLUMNS)
            val positions = Home.rowPositions(row)
            val column = p.x / cellW
            positions.withIndex().firstOrNull { (_, pos) -> pos != null && column >= pos && column < pos + 1 }
                ?.let { return r * Home.COLUMNS + it.index }
            // The half column at either end of a row of four: that row's empty slot.
            val empty = positions.indexOfFirst { it == null }
            return r * Home.COLUMNS + if (empty >= 0) empty else (column.toInt().coerceIn(0, Home.COLUMNS - 1))
        }
        /** Whether a point is on the icon of its slot (a drop there goes into it) rather than beside it. */
        fun onIcon(p: Offset): Boolean {
            val slot = slotAt(p)
            val pos = drawnAt(slot) ?: return false
            val cx = (pos + 0.5f) * cellW
            val cy = (slot / Home.COLUMNS) * cellH + iconPx / 2 + with(density) { 6.dp.toPx() }
            return hypot(p.x - cx, p.y - cy) < iconPx * 0.42f
        }

        val target = lift?.let { slotAt(it.at) }
        // The page under the finger while it swipes, then the turn takes it the rest of the way.
        val width = with(density) { maxWidth.toPx() }
        androidx.compose.animation.AnimatedContent(page, transitionSpec = { pageTurn() }, label = "page",
            modifier = Modifier.fillMaxSize().graphicsLayer {
                translationX = swipeX.value
                alpha = 1f - (abs(swipeX.value) / width).coerceIn(0f, 1f) * 0.7f
            }) { p ->
        Box(Modifier.fillMaxSize()) {
        val slots = shown.getOrElse(p) { shown.last() }
        val drawn = slots.chunked(Home.COLUMNS).flatMap { Home.rowPositions(it) }
        for ((i, item) in slots.withIndex()) {
            // A row of four's empty slot is not drawn at all; its icons sit half a column in.
            val pos = drawn[i] ?: continue
            val x = pos * cellW
            val y = (i / Home.COLUMNS) * cellH
            val isLifted = lift?.let { it.fromPage == p && it.fromSlot == i } == true
            val isTarget = target == i && !isLifted
            Column(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .size(with(density) { cellW.toDp() }, with(density) { cellH.toDp() })
                .alpha(if (isLifted) 0.25f else 1f),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.padding(top = 6.dp).size(icon + 6.dp)
                    .then(if (isTarget) Modifier.border(2.dp, HudTheme.accent, CircleShape) else Modifier),
                    contentAlignment = Alignment.Center) {
                    when (item) {
                        is Home.Item.App -> RoundIcon(Home.app(item.key), icon)
                        is Home.Item.Folder -> FolderIcon(item, icon)
                        null -> if (lift != null) Box(Modifier.size(icon).border(1.dp, HudTheme.edge, CircleShape))
                    }
                }
                if (Home.labels) item?.let {
                    val label = when (it) { is Home.Item.App -> Home.app(it.key)?.label ?: ""; is Home.Item.Folder -> it.name }
                    BasicText(label, style = HudTheme.mont(9.5f, FontWeight.SemiBold, color = HudTheme.ink).copy(textAlign = TextAlign.Center),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp))
                }
            }
        }

        }
        }

        // What is being carried, under the finger, a little larger.
        lift?.let { l ->
            Box(Modifier.offset { IntOffset((l.at.x - iconPx / 2).roundToInt(), (l.at.y - iconPx / 2).roundToInt()) }.scale(1.15f)) {
                when (val item = l.item) {
                    is Home.Item.App -> RoundIcon(Home.app(item.key), icon)
                    is Home.Item.Folder -> FolderIcon(item, icon)
                }
            }
        }
        note?.let {
            Box(Modifier.align(Alignment.Center).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.9f))
                .padding(horizontal = 14.dp, vertical = 8.dp)) {
                BasicText(it, style = HudTheme.mont(11f, tracking = 1.5f, color = HudTheme.accent))
            }
        }

        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val startPage = Home.page
                val startSlot = slotAt(down.position)
                val result: String? = withTimeoutOrNull(420L) {
                    var outcome = "up"
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        val travel = change.position - down.position
                        if (abs(travel.x) + abs(travel.y) > viewConfiguration.touchSlop) { outcome = "swipe"; break }
                    }
                    outcome
                }
                val item = Home.shown().getOrNull(startPage)?.getOrNull(startSlot)
                when (result) {
                    "up" -> {
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        when (item) {
                            is Home.Item.App -> Home.app(item.key)?.let { Home.launch(it); opened() }
                            is Home.Item.Folder -> Home.openFolder = item.id
                            null -> Unit
                        }
                    }
                    "swipe" -> {
                        val count = Home.shown().size
                        var last = down.position
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            last = change.position
                            val dx = last.x - down.position.x
                            // Past the first or last page it still moves, but grudgingly.
                            val edgeOfBook = (dx > 0 && startPage == 0) || (dx < 0 && startPage == count - 1)
                            scope.launch { swipeX.snapTo(if (edgeOfBook) dx * 0.25f else dx) }
                            change.consume()
                        }
                        val dx = last.x - down.position.x
                        if (dx < -swipe && startPage < count - 1) Home.page = startPage + 1
                        if (dx > swipe && startPage > 0) Home.page = startPage - 1
                        scope.launch { swipeX.animateTo(0f, tween(PAGE_MILLIS, easing = FastOutSlowInEasing)) }
                    }
                    else -> {
                        if (item == null) return@awaitEachGesture
                        if (!Home.arranged) { note = "SET ORDER TO CUSTOM TO ARRANGE"; return@awaitEachGesture }
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        val l = Lift(startPage, startSlot, item).also { it.at = down.position }
                        lift = l
                        var edgeSince = 0L
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            l.at = change.position
                            change.consume()
                            // Held at a side, the page turns; past the last page there is a new one.
                            val side = when { l.at.x < edge -> -1; l.at.x > size.width - edge -> 1; else -> 0 }
                            if (side == 0) edgeSince = 0L
                            else if (edgeSince == 0L) edgeSince = System.currentTimeMillis()
                            else if (System.currentTimeMillis() - edgeSince > 650) {
                                edgeSince = System.currentTimeMillis()
                                val next = Home.page + side
                                if (next >= 0) {
                                    if (next >= Home.pages.size) Home.addPage()
                                    Home.page = next
                                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                }
                            }
                        }
                        Home.drop(l.fromPage, l.fromSlot, Home.page, slotAt(l.at), onIcon(l.at))
                        lift = null
                    }
                }
            }
        })
    }
}

@Composable
private fun Dots(pages: Int, page: Int) {
    Row(Modifier.fillMaxWidth().height(26.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until pages) {
            Box(Modifier.size(22.dp).clickable { Home.page = i }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(if (i == page) 8.dp else 6.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = if (i == page) 0.95f else 0.32f)))
            }
        }
        if (Home.arranged) Box(Modifier.size(22.dp).clickable { Home.addPage(); Home.page = Home.pages.size - 1 }, contentAlignment = Alignment.Center) {
            Image(Icons.Rounded.Add, "New page", Modifier.size(14.dp), colorFilter = ColorFilter.tint(HudTheme.inkFaint))
        }
    }
}

// ------------------------------------------------------------------ a folder

@Composable
private fun PhoneFolder(folder: Home.Item.Folder, opened: () -> Unit) {
    val view = LocalView.current
    val renaming = Home.renaming == folder.id
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.78f))
        .clickable { Home.openFolder = null; Home.renaming = null }, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(14.dp).clip(RoundedCornerShape(26.dp)).background(Color(0xFF1C1C1C))
            .clickable(enabled = false) {}.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(folder.name + if (renaming) "|" else "",
                style = HudTheme.mont(16f, FontWeight.SemiBold, color = if (renaming) HudTheme.accent else HudTheme.ink),
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { Home.renaming = if (renaming) null else folder.id }
                    .padding(horizontal = 10.dp, vertical = 6.dp))
            BasicText(if (renaming) "Type the name · return when done" else "Tap the name to rename · hold an app to take it out",
                style = HudTheme.mont(9f, FontWeight.SemiBold, color = HudTheme.inkFaint), modifier = Modifier.padding(bottom = 10.dp))
            for (row in folder.apps.chunked(Home.COLUMNS)) {
                Row {
                    for (key in row) {
                        val app = Home.app(key) ?: continue
                        Column(Modifier.width(62.dp).pointerInput(key) {
                            awaitEachGesture {
                                awaitFirstDown()
                                val held = withTimeoutOrNull(420L) {
                                    while (true) { val c = awaitPointerEvent().changes.firstOrNull() ?: break; if (!c.pressed) break }
                                    false
                                } ?: true
                                if (held) {
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    Home.takeOut(folder.id, key)
                                } else {
                                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                    Home.launch(app); opened()
                                }
                            }
                        }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            RoundIcon(app, 44.dp)
                            BasicText(app.label, style = HudTheme.mont(9f, FontWeight.SemiBold, color = HudTheme.ink),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhoneSettings() {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.08f))) {
        Setting("Clock", if (Home.clock24) "24-HOUR" else "12-HOUR") { Home.toggleClock() }
        Setting("Temperature", if (Home.fahrenheit) "°F" else "°C") { Home.toggleUnits() }
        Setting("App names", if (Home.labels) "SHOWN" else "HIDDEN") { Home.toggleLabels() }
        Setting("Order", Home.sort.title) { Home.nextSort() }
        Setting("Default folders", "RESET") { Home.resetToDefault(); Home.settingsOpen = false }
    }
}

@Composable
private fun Setting(title: String, value: String, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).clickable { action() }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(title, style = HudTheme.mont(14f, FontWeight.SemiBold), modifier = Modifier.weight(1f))
        BasicText(value, style = HudTheme.mont(11f, tracking = 1.5f, color = HudTheme.accent))
    }
}

// ------------------------------------------------------------------ battery and notifications

/** The battery's percentage as a tool, gold while charging; it opens the notifications. */
@Composable
private fun BatteryTool() {
    val view = LocalView.current
    val lit = Home.notificationsOpen
    Box(Modifier.padding(start = 6.dp).size(44.dp)) {
        Box(Modifier.fillMaxSize().clip(CircleShape)
            .background(if (lit) Color.White else Color.White.copy(alpha = 0.14f))
            .clickable { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); Home.toggleNotifications() },
            contentAlignment = Alignment.Center) {
            BasicText(if (Battery.level >= 0) "${Battery.level}%" else "—", style = HudTheme.mont(11f,
                color = when { lit -> Color.Black; Battery.charging -> HudTheme.accent; else -> Color.White }))
        }
        if (Notes.list.isNotEmpty() && !lit) Box(Modifier.align(Alignment.TopEnd).padding(2.dp).size(8.dp)
            .clip(CircleShape).background(HudTheme.accent))
    }
}

@Composable
private fun PhoneNotifications(modifier: Modifier, opened: () -> Unit) {
    val view = LocalView.current
    val notes = Notes.list
    Column(modifier.padding(top = 6.dp, bottom = 8.dp).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.08f))) {
        Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("NOTIFICATIONS", style = HudTheme.mont(10f, tracking = 2f, color = HudTheme.inkDim), modifier = Modifier.weight(1f))
            if (notes.any { it.clearable }) BasicText("CLEAR ALL", style = HudTheme.mont(10f, tracking = 2f, color = HudTheme.accent),
                modifier = Modifier.clickable { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); Notes.dismissAll() }
                    .padding(vertical = 10.dp, horizontal = 4.dp))
        }
        if (notes.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                BasicText("Nothing new", style = HudTheme.mont(13f, FontWeight.SemiBold, color = HudTheme.inkDim))
            }
        } else {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(notes.size, key = { notes[it].key }) { i ->
                    val note = notes[i]
                    Row(Modifier.fillMaxWidth().height(58.dp)
                        .clickable {
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            val launchable = dev.superair.Air.apps.any { it.pkg == note.pkg }
                            Notes.open(note)
                            if (launchable) opened()
                        }
                        .padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RoundIcon(note.entry, 34.dp)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            BasicText("${note.app.uppercase()} · ${note.ago()}", style = HudTheme.mont(8.5f, tracking = 1f, color = HudTheme.inkFaint), maxLines = 1)
                            if (note.title.isNotEmpty()) BasicText(note.title, style = HudTheme.mont(12.5f, FontWeight.SemiBold),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (note.text.isNotEmpty()) BasicText(note.text, style = HudTheme.mont(11f, FontWeight.Normal, color = HudTheme.inkDim),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (note.clearable) Box(Modifier.size(40.dp).clip(CircleShape)
                            .clickable { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); Notes.dismiss(note) },
                            contentAlignment = Alignment.Center) {
                            Image(Icons.Rounded.Close, "Clear", Modifier.size(16.dp), colorFilter = ColorFilter.tint(HudTheme.inkDim))
                        }
                    }
                }
            }
        }
    }
}
