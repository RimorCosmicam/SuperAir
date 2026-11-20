package dev.superair.glasses

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.material.icons.rounded.Thunderstorm
import androidx.compose.material.icons.rounded.Umbrella
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.WbCloudy
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.ViewModule
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.superair.Air
import dev.superair.AppEntry
import dev.superair.mont.Mont
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home: the clock and the weather, a row of round tools, and a calm page of round apps — ten at a
 * time, as Android XR does it, not a wall of them.
 *
 * Over open apps it lays a soft black layer, and black is what the glasses do not draw, so the
 * apps simply fade back into the room behind it rather than being covered.
 */
@Composable
fun LauncherSheet() {
    val shown = Home.shown()
    val pages = shown.size
    val page = Home.page.coerceIn(0, pages - 1)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = if (Air.panels.isEmpty()) 1f else 0.62f))
            // Anywhere that is not a button: scroll turns the page, a click goes back to the desk.
            .cursorTarget("home", Targets.SHEET, onScroll = { dy -> turn(if (dy < 0) 1 else -1, pages) }) {
                if (Air.panels.isNotEmpty()) close()
            },
    ) {
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ClockAndWeather()
            Spacer(Modifier.height(36.dp))
            ToolRow()
            Spacer(Modifier.height(if (Home.searching) 18.dp else 44.dp))
            if (Home.searching) { SearchField(); Spacer(Modifier.height(26.dp)) }
            if (Home.notificationsOpen) NotificationList()
            else if (Home.settingsOpen) Settings() else {
                androidx.compose.animation.AnimatedContent(page, transitionSpec = { pageTurn() }, label = "page") { p ->
                    AppPage(shown.getOrElse(p) { shown.last() })
                }
                Spacer(Modifier.height(22.dp))
                PageDots(pages)
            }
        }
        Home.openFolder?.let { id -> Home.folder(id)?.let { FolderCard(it) } }
    }
}

private fun close() {
    Air.launcherOpen = false
    Home.openFolder = null
    Home.endSearch()
    Home.settingsOpen = false
    Home.notificationsOpen = false
}

private fun turn(by: Int, pages: Int) {
    Home.page = (Home.page + by).coerceIn(0, pages - 1)
}

// ------------------------------------------------------------------ clock

@Composable
private fun ClockAndWeather() {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val date = remember(now / 60_000) { SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(now)) }
    val time = SimpleDateFormat(if (Home.clock24) "H:mm" else "h:mm", Locale.getDefault()).format(Date(now))

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(date, color = Color.White.copy(alpha = 0.78f), fontFamily = Mont, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
        Text(time, color = Color.White, fontFamily = Mont, fontWeight = FontWeight.Light, fontSize = 104.sp, lineHeight = 112.sp)
        Weather.now?.let { w ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(weatherIcon(w), null, Modifier.size(26.dp), colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.85f)))
                Text("${w.temperature}°  ${Weather.label(w.code)}", color = Color.White.copy(alpha = 0.78f),
                    fontFamily = Mont, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
            }
        }
    }
}

private fun weatherIcon(w: Weather.Now): ImageVector = when (w.code) {
    0, 1 -> if (w.day) Icons.Rounded.WbSunny else Icons.Rounded.DarkMode
    2 -> Icons.Rounded.WbCloudy
    3, 45, 48 -> Icons.Rounded.Cloud
    in 51..57 -> Icons.Rounded.Grain
    in 61..67, in 80..82 -> Icons.Rounded.Umbrella
    in 71..77, 85, 86 -> Icons.Rounded.AcUnit
    in 95..99 -> Icons.Rounded.Thunderstorm
    else -> Icons.Rounded.Cloud
}

// ------------------------------------------------------------------ tools

@Composable
private fun ToolRow() {
    Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
        Tool("tool:home", Icons.Rounded.Home, "Desk", enabled = Air.panels.isNotEmpty()) { close() }
        Tool("tool:search", Icons.Rounded.Search, "Search", lit = Home.searching) {
            if (Home.searching) Home.endSearch() else Home.startSearch()
        }
        Tool("tool:sort", when (Home.sort) {
            Home.Sort.CUSTOM -> Icons.Rounded.ViewModule
            Home.Sort.NAME -> Icons.Rounded.SortByAlpha
            Home.Sort.RECENT -> Icons.Rounded.History
            Home.Sort.MOST -> Icons.Rounded.TrendingUp
        }, Home.sort.title) { Home.nextSort() }
        BatteryTool()
        Tool("tool:settings", Icons.Rounded.Settings, "Settings", lit = Home.settingsOpen) { Home.toggleSettings() }
    }
}

@Composable
private fun Tool(key: String, icon: ImageVector, caption: String, lit: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val over = hovered(key)
    val grow by animateFloatAsState(if (over && enabled) 1.08f else 1f, label = "tool")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(78.dp).scale(grow).clip(CircleShape)
                .background(Color.White.copy(alpha = when { lit -> 0.92f; over -> 0.30f; else -> 0.16f }))
                .cursorTarget(key, Targets.SHEET_ITEM) { if (enabled) onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Image(icon, caption, Modifier.size(32.dp), colorFilter = ColorFilter.tint(when {
                lit -> Color.Black
                enabled -> Color.White
                else -> Color.White.copy(alpha = 0.35f)
            }))
        }
        // The caption only for the one under the cursor, so the row stays a row of circles.
        Text(if (over) caption.uppercase() else "", color = Color.White.copy(alpha = 0.8f), fontFamily = Mont,
            fontWeight = FontWeight.Black, fontSize = 13.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 8.dp).height(18.dp))
    }
}

@Composable
private fun SearchField() {
    var blink by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(530); blink++ } }
    Row(
        Modifier.width(560.dp).height(64.dp).clip(RoundedCornerShape(32.dp))
            .background(Color.White.copy(alpha = 0.14f)).border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(32.dp))
            .padding(horizontal = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Image(Icons.Rounded.Search, null, Modifier.size(26.dp), colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.7f)))
        val caret = if (blink % 2 == 0L) "|" else " "
        Text(
            if (Home.query.isEmpty()) "Type on the phone$caret" else Home.query + caret,
            color = Color.White.copy(alpha = if (Home.query.isEmpty()) 0.45f else 1f),
            fontFamily = Mont, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, maxLines = 1,
        )
    }
}

// ------------------------------------------------------------------ apps

/** One page, slot for slot: an empty slot stays empty, because where things are is the point. */
@Composable
private fun AppPage(slots: List<Home.Item?>) {
    Column(Modifier.height(186.dp * Home.ROWS), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (slots.all { it == null } && !Home.arranged) {
            Box(Modifier.width(178.dp * Home.COLUMNS).height(160.dp), contentAlignment = Alignment.Center) {
                Text(if (Air.apps.isEmpty()) "Waiting for the host…" else "No app called “${Home.query}”",
                    color = Color.White.copy(alpha = 0.6f), fontFamily = Mont, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
            }
            return@Column
        }
        for (row in slots.chunked(Home.COLUMNS)) {
            // Placed by column rather than flowed, so a row of four can sit half a column in.
            Box(Modifier.width(178.dp * Home.COLUMNS - 10.dp).height(176.dp)) {
                for ((i, pos) in Home.rowPositions(row).withIndex()) {
                    if (pos == null) continue
                    Box(Modifier.offset(x = 178.dp * pos)) {
                        when (val item = row[i]) {
                            is Home.Item.App -> Home.app(item.key)?.let { AppTile(it) }
                            is Home.Item.Folder -> FolderTile(item)
                            null -> Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppTile(app: AppEntry, layer: Int = Targets.SHEET_ITEM) {
    val key = "app:${app.pkg}/${app.activity}"
    val over = hovered(key)
    val grow by animateFloatAsState(if (over) 1.1f else 1f, label = "app")
    Column(
        Modifier.width(168.dp).height(176.dp).cursorTarget(key, layer) { Home.launch(app) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.padding(top = 10.dp).size(100.dp).scale(grow), contentAlignment = Alignment.Center) {
            RoundIcon(app, 100.dp)
            if (over) Box(Modifier.size(108.dp).border(3.dp, Color.White.copy(alpha = 0.7f), CircleShape))
        }
        if (Home.labels) Label(app.label, over)
    }
}

@Composable
private fun FolderTile(folder: Home.Item.Folder) {
    val key = "folder:${folder.id}"
    val over = hovered(key)
    val grow by animateFloatAsState(if (over) 1.1f else 1f, label = "folder")
    Column(
        Modifier.width(168.dp).height(176.dp).cursorTarget(key, Targets.SHEET_ITEM) { Home.openFolder = folder.id },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.padding(top = 10.dp).size(100.dp).scale(grow), contentAlignment = Alignment.Center) {
            FolderIcon(folder, 100.dp)
            if (over) Box(Modifier.size(108.dp).border(3.dp, Color.White.copy(alpha = 0.7f), CircleShape))
        }
        if (Home.labels) Label(folder.name, over)
    }
}

@Composable
private fun Label(text: String, over: Boolean) {
    Text(text, color = Color.White.copy(alpha = if (over) 1f else 0.82f), fontFamily = Mont,
        fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp, start = 6.dp, end = 6.dp))
}

/** An open folder: its apps on a card over the page. Anywhere else closes it. */
@Composable
private fun FolderCard(folder: Home.Item.Folder) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .cursorTarget("folder-backdrop", Targets.FOLDER) { Home.openFolder = null },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.clip(RoundedCornerShape(36.dp)).background(Color(0xFF1C1C1C)).padding(horizontal = 30.dp, vertical = 26.dp)
                .cursorTarget("folder-card", Targets.FOLDER) { },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(folder.name, color = Color.White, fontFamily = Mont, fontWeight = FontWeight.SemiBold, fontSize = 28.sp)
            Spacer(Modifier.height(18.dp))
            for (row in folder.apps.chunked(Home.COLUMNS)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (key in row) Home.app(key)?.let { AppTile(it, Targets.FOLDER_ITEM) }
                }
            }
        }
    }
}

@Composable
private fun PageDots(pages: Int) {
    if (pages <= 1) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until pages) {
            val on = i == Home.page
            Box(Modifier.size(28.dp).cursorTarget("dot:$i", Targets.SHEET_ITEM) { Home.page = i }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(if (on) 12.dp else 9.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = if (on) 0.95f else if (hovered("dot:$i")) 0.6f else 0.32f)))
            }
        }
    }
}

// ------------------------------------------------------------------ settings

@Composable
private fun Settings() {
    Column(
        Modifier.width(620.dp).clip(RoundedCornerShape(28.dp)).background(Color.White.copy(alpha = 0.10f)).padding(vertical = 10.dp),
    ) {
        SettingRow("set:clock", "Clock", if (Home.clock24) "24-HOUR" else "12-HOUR") { Home.toggleClock() }
        SettingRow("set:units", "Temperature", if (Home.fahrenheit) "°F" else "°C") { Home.toggleUnits() }
        SettingRow("set:labels", "App names", if (Home.labels) "SHOWN" else "HIDDEN") { Home.toggleLabels() }
        SettingRow("set:sort", "Order", Home.sort.title) { Home.nextSort() }
        SettingRow("set:reset", "Default folders", "RESET") { Home.resetToDefault() }
    }
}

@Composable
private fun SettingRow(key: String, title: String, value: String, onClick: () -> Unit) {
    val over = hovered(key)
    Row(
        Modifier.width(620.dp).height(76.dp).background(if (over) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .cursorTarget(key, Targets.SHEET_ITEM) { onClick() }.padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Color.White, fontFamily = Mont, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, modifier = Modifier.weight(1f))
        Text(value, color = Color(0xFFD8A628), fontFamily = Mont, fontWeight = FontWeight.Black, fontSize = 18.sp, letterSpacing = 2.sp)
    }
}

// ------------------------------------------------------------------ battery and notifications

/** The battery as a tool: its percentage in the circle, gold while charging, a dot for news. */
@Composable
private fun BatteryTool() {
    val key = "tool:battery"
    val over = hovered(key)
    val lit = Home.notificationsOpen
    val grow by animateFloatAsState(if (over) 1.08f else 1f, label = "tool")
    val count = Notes.list.size
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(78.dp).scale(grow)) {
            Box(
                Modifier.fillMaxSize().clip(CircleShape)
                    .background(Color.White.copy(alpha = when { lit -> 0.92f; over -> 0.30f; else -> 0.16f }))
                    .cursorTarget(key, Targets.SHEET_ITEM) { Home.toggleNotifications() },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (Battery.level >= 0) "${Battery.level}%" else "—",
                    color = when { lit -> Color.Black; Battery.charging -> Color(0xFFD8A628); else -> Color.White },
                    fontFamily = Mont, fontWeight = FontWeight.Black, fontSize = 20.sp)
            }
            if (count > 0 && !lit) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(14.dp)
                .clip(CircleShape).background(Color(0xFFD8A628)))
        }
        Text(if (over) (if (count > 0) "$count NOTIFICATIONS" else "NOTIFICATIONS") else "",
            color = Color.White.copy(alpha = 0.8f), fontFamily = Mont, fontWeight = FontWeight.Black,
            fontSize = 13.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 8.dp).height(18.dp))
    }
}

/** The phone's notifications where the app pages were: click one to open its app, the cross clears it. */
@Composable
private fun NotificationList() {
    val notes = Notes.list
    var first by remember { mutableIntStateOf(0) }
    val visible = 5
    val start = first.coerceIn(0, (notes.size - visible).coerceAtLeast(0))
    Column(
        Modifier.width(680.dp).clip(RoundedCornerShape(28.dp)).background(Color.White.copy(alpha = 0.10f))
            .cursorTarget("notes", Targets.SHEET_ITEM, onScroll = { dy ->
                first = (start + if (dy < 0) 1 else -1).coerceIn(0, (notes.size - visible).coerceAtLeast(0))
            }) { }
            .padding(vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("NOTIFICATIONS", color = Color.White.copy(alpha = 0.6f), fontFamily = Mont, fontWeight = FontWeight.Black,
                fontSize = 15.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            if (notes.any { it.clearable }) {
                val over = hovered("notes:clear")
                Text("CLEAR ALL", color = Color(0xFFD8A628).copy(alpha = if (over) 1f else 0.8f), fontFamily = Mont,
                    fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = 2.sp,
                    modifier = Modifier.cursorTarget("notes:clear", Targets.SHEET_ITEM + 1) { Notes.dismissAll() })
            }
        }
        if (notes.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text("Nothing new", color = Color.White.copy(alpha = 0.5f), fontFamily = Mont,
                    fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
            }
        }
        for (note in notes.drop(start).take(visible)) NoteRow(note)
        if (notes.size > visible) {
            Text("${start + 1}–${minOf(start + visible, notes.size)} OF ${notes.size} · SCROLL FOR MORE",
                color = Color.White.copy(alpha = 0.4f), fontFamily = Mont, fontWeight = FontWeight.Black,
                fontSize = 12.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(start = 32.dp, top = 6.dp, bottom = 4.dp))
        }
    }
}

@Composable
private fun NoteRow(note: Notes.Note) {
    val key = "note:" + note.key
    val over = hovered(key)
    Row(
        Modifier.fillMaxWidth().height(84.dp).background(if (over) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .cursorTarget(key, Targets.SHEET_ITEM + 1) { Notes.open(note) }.padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        RoundIcon(note.entry, 48.dp)
        Column(Modifier.weight(1f)) {
            Text("${note.app.uppercase()} · ${note.ago()}", color = Color.White.copy(alpha = 0.5f), fontFamily = Mont,
                fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.2.sp, maxLines = 1)
            if (note.title.isNotEmpty()) Text(note.title, color = Color.White, fontFamily = Mont,
                fontWeight = FontWeight.SemiBold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note.text.isNotEmpty()) Text(note.text, color = Color.White.copy(alpha = 0.7f), fontFamily = Mont,
                fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (note.clearable) {
            val xKey = "note:x:" + note.key
            val xOver = hovered(xKey)
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = if (xOver) 0.3f else 0.0f))
                .cursorTarget(xKey, Targets.SHEET_ITEM + 2) { Notes.dismiss(note) }, contentAlignment = Alignment.Center) {
                Image(Icons.Rounded.Close, "Clear", Modifier.size(22.dp),
                    colorFilter = ColorFilter.tint(Color.White.copy(alpha = if (xOver) 1f else 0.5f)))
            }
        }
    }
}
