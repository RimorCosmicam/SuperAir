package dev.superair.cover

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardHide
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.superair.Air
import dev.superair.AppEntry
import dev.superair.Grid
import dev.superair.PanelState
import dev.superair.Place
import dev.superair.Pointer
import dev.superair.glasses.GlassesPresenter
import dev.superair.glasses.Home
import dev.superair.hud.HalftoneBackground
import dev.superair.hud.HudTheme
import dev.superair.hud.touchpad.GestureEvent
import dev.superair.hud.touchpad.GestureRecognizer
import dev.superair.hud.touchpad.PhysicsMomentumScroller
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

// TheHud's phone screen, fitted to the Flip's cover and pointed at SuperAir: GLASSES and its dot,
// the desk as a miniature of the 6x3 grid, the tabs, and the pad running to the bottom edge.
// Black, white and Mont; one accent; the halftone is the only ornament.

private val pill = RoundedCornerShape(50)
private fun View.tick() = performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
private fun View.thud() = performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

private enum class Pad(val title: String) { TOUCHPAD("TOUCHPAD"), REMOTE("REMOTE"), APPS("APPS") }

/** Whether the keyboard is up, typing into the focused panel. */
private object Typing { var on by mutableStateOf(false) }

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CoverScreen() {
    var pad by remember { mutableStateOf(if (Air.panels.isEmpty()) Pad.APPS else Pad.TOUCHPAD) }
    var glasses by remember { mutableStateOf(GlassesPresenter.attached) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var overlay by remember { mutableStateOf(dev.superair.PairingService.granted(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            glasses = GlassesPresenter.attached
            overlay = dev.superair.PairingService.granted(context)
            delay(1000)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Always clear of the camera cutout: the status bar's room even while One UI hides the bar,
        // so the layout does not jump up under the camera when it goes.
        Column(Modifier.fillMaxSize().windowInsetsPadding(
            WindowInsets.statusBarsIgnoringVisibility.union(WindowInsets.displayCutout))) {
            if (!overlay) AccessibilityOff()
            // Home takes the whole screen below the tabs: it is a page of its own, as on the glasses.
            if (pad != Pad.APPS) Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Header(glasses)
                Miniature(Modifier.fillMaxWidth().height(96.dp)) { Air.launcherOpen = true; pad = Pad.APPS }
            }
            PadHeader(pad) { choice ->
                pad = choice
                if (choice == Pad.APPS) Air.launcherOpen = true
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (pad) {
                    Pad.TOUCHPAD -> Touchpad(Modifier.fillMaxSize(), glasses)
                    Pad.REMOTE -> RemotePad(Modifier.fillMaxSize())
                    Pad.APPS -> PhoneHome(Modifier.fillMaxSize()) { pad = Pad.TOUCHPAD }
                }
            }
        }
        KeyboardProxy()
    }
}

/**
 * The accessibility service is off, so the glasses fall back to a Presentation, and One UI covers
 * a Presentation with its lock screen: locking the phone hides everything on the glasses. One UI
 * switches the service off whenever SuperAir is force-stopped, so this has to be said, not assumed.
 */
@Composable
private fun AccessibilityOff() {
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp))
        .background(HudTheme.accent).clickable {
            runCatching {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText("Turn SuperAir on in Accessibility, or locking the phone hides the glasses",
            style = HudTheme.mont(11f, FontWeight.SemiBold, color = Color.Black), modifier = Modifier.weight(1f))
    }
}

// ------------------------------------------------------------------ GLASSES

@Composable
private fun Header(glasses: Boolean) {
    Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText("GLASSES", style = HudTheme.mont(17f, tracking = 4f, color = HudTheme.inkDim))
        // The dot is the glasses: lit while SuperAir is drawing on them.
        Box(Modifier.padding(start = 8.dp).size(7.dp).clip(pill)
            .background(if (glasses) HudTheme.accent else HudTheme.inkFaint))
        Spacer(Modifier.weight(1f))
        val count = Air.panels.size
        BasicText(when {
            Air.host == null -> "STARTING"
            count == 0 -> "EMPTY DESK"
            count == 1 -> "1 APP"
            else -> "$count APPS"
        }, style = HudTheme.mont(10f, tracking = 2f, color = HudTheme.inkFaint))
    }
}

/**
 * The desk, small: the 6x3 grid with each open app as a chip in its cells. Tap a chip to focus it,
 * drag it to move it, tap an empty cell to open an app.
 */
@Composable
private fun Miniature(modifier: Modifier, openApps: () -> Unit) {
    val view = LocalView.current
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val gap = 4.dp
        val cellW = (maxWidth - gap * (Grid.COLUMNS - 1)) / Grid.COLUMNS
        val cellH = (maxHeight - gap * (Grid.ROWS - 1)) / Grid.ROWS
        val stepX = with(density) { (cellW + gap).toPx() }
        val stepY = with(density) { (cellH + gap).toPx() }
        fun cellAt(x: Float, y: Float) =
            (x / stepX).toInt().coerceIn(0, Grid.COLUMNS - 1) to (y / stepY).toInt().coerceIn(0, Grid.ROWS - 1)

        for (row in 0 until Grid.ROWS) for (column in 0 until Grid.COLUMNS) {
            Box(Modifier.offset(x = (cellW + gap) * column, y = (cellH + gap) * row).size(cellW, cellH)
                .clip(RoundedCornerShape(8.dp)).border(1.dp, HudTheme.edge, RoundedCornerShape(8.dp)))
        }

        var lifted by remember { mutableStateOf<PanelState?>(null) }
        var hint by remember { mutableStateOf<String?>(null) }
        var closing by remember { mutableStateOf(false) }
        for (panel in Air.panels) {
            val p = panel.place
            val focused = Air.focused == panel
            val doomed = closing && lifted == panel
            Box(Modifier.offset(x = (cellW + gap) * p.column, y = (cellH + gap) * p.row)
                .size(cellW * p.spanX + gap * (p.spanX - 1), cellH * p.spanY + gap * (p.spanY - 1))
                .alpha(if (doomed) 0.45f else 1f)
                .clip(RoundedCornerShape(8.dp))
                .background(if (doomed) Color(0xFF3A1714) else Color(0xFF242424))
                .border(if (lifted == panel) 2.dp else 1.dp,
                    when { doomed -> Color(0xFFEF6A5A); focused || lifted == panel -> HudTheme.accent; else -> HudTheme.edge },
                    RoundedCornerShape(8.dp))) {
                // The bar along the top, as TheHud marks what is on the glasses.
                Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(2.5.dp)
                    .background(if (focused) HudTheme.accent else HudTheme.inkFaint, pill))
                AppIcon(panel.app, Modifier.align(Alignment.Center).size(if (p.spanY > 1) 28.dp else 20.dp))
                if (Air.fullscreen == panel) BasicText("FULL", style = HudTheme.mont(8f, tracking = 1.5f, color = HudTheme.accent),
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp))
                if (p.spanX > 1) BasicText(panel.app.label.uppercase(),
                    style = HudTheme.mont(8.5f, tracking = 1f, color = if (focused) HudTheme.ink else HudTheme.inkDim),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 6.dp, vertical = 4.dp))
            }
        }
        hint?.let {
            Box(Modifier.align(Alignment.Center).clip(pill).background(Color.Black.copy(alpha = 0.85f))
                .padding(horizontal = 14.dp, vertical = 6.dp)) {
                BasicText(it, style = HudTheme.mont(12f, tracking = 2f, color = if (closing) Color(0xFFEF6A5A) else HudTheme.accent))
            }
        }

        // One gesture layer over the whole miniature. On an app: a quick drag moves it, a hold and
        // then a drag sizes it (its top-left corner stays, the finger is the far corner), and a drag
        // off the grid closes it. On an empty cell: a tap opens Home.
        val outside = with(density) { 22.dp.toPx() }
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val (c0, r0) = cellAt(down.position.x, down.position.y)
                val panel = Air.panels.lastOrNull { it.place.overlaps(Place(c0, r0, 1, 1)) }
                val result: String? = withTimeoutOrNull(380L) {
                    var outcome = "up"
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        val travel = change.position - down.position
                        if (abs(travel.x) + abs(travel.y) > viewConfiguration.touchSlop) { outcome = "move"; break }
                    }
                    outcome
                }
                if (result == "up") {
                    view.tick()
                    if (panel == null) { openApps(); return@awaitEachGesture }
                    // A second tap soon after, on the same app: fullscreen on the glasses, or back.
                    val second = withTimeoutOrNull(260L) { awaitFirstDown(requireUnconsumed = false) }
                    val again = second?.let { d ->
                        val (c, r) = cellAt(d.position.x, d.position.y)
                        panel.place.overlaps(Place(c, r, 1, 1))
                    } == true
                    if (again) {
                        view.thud()
                        Air.toggleFullscreen(panel)
                    } else {
                        // One tap: on the fullscreen app it puts it back; on any other app it
                        // ends the fullscreen and focuses that one.
                        if (Air.fullscreen != null) Air.fullscreen = null
                        Air.focus(panel)
                    }
                    return@awaitEachGesture
                }
                if (panel == null) return@awaitEachGesture
                val sizing = result == null
                lifted = panel
                Air.focus(panel)
                view.thud()
                val grabColumn = c0 - panel.place.column
                val grabRow = r0 - panel.place.row
                if (sizing) hint = "${panel.place.spanX} × ${panel.place.spanY}"
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull() ?: break
                    if (!change.pressed) break
                    val at = change.position
                    val (c, r) = cellAt(at.x, at.y)
                    if (sizing) {
                        val w = (c - panel.place.column + 1).coerceIn(1, Grid.COLUMNS - panel.place.column)
                        val h = (r - panel.place.row + 1).coerceIn(1, Grid.ROWS - panel.place.row)
                        if (w != panel.place.spanX || h != panel.place.spanY) { Air.resizeTo(panel, w, h); view.tick() }
                        hint = "$w × $h"
                    } else {
                        val off = at.x < -outside || at.y < -outside || at.x > size.width + outside || at.y > size.height + outside
                        if (off != closing) { closing = off; view.tick() }
                        hint = if (off) "RELEASE TO CLOSE" else null
                        if (!off) {
                            val place = panel.place.copy(column = c - grabColumn, row = r - grabRow)
                            if (place != panel.place) { Air.place(panel, place); view.tick() }
                        }
                    }
                    change.consume()
                }
                if (closing) { view.thud(); Air.close(panel) }
                lifted = null; hint = null; closing = false
            }
        })
    }
}

// ------------------------------------------------------------------ the pad

/** The tabs, and the two buttons a thumb wants constantly: back, and the keyboard. */
@Composable
private fun PadHeader(pad: Pad, choose: (Pad) -> Unit) {
    val view = LocalView.current
    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        for (choice in Pad.entries) {
            val on = choice == pad
            // The whole height of the row is the button, not just the word.
            Column(Modifier.fillMaxHeight().width(IntrinsicSize.Max).clickable { view.tick(); choose(choice) }
                .padding(horizontal = 8.dp), verticalArrangement = Arrangement.Center) {
                BasicText(choice.title, style = HudTheme.mont(12f, tracking = 2f, color = if (on) HudTheme.ink else HudTheme.inkFaint))
                Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(2.5.dp).background(if (on) HudTheme.accent else Color.Transparent, pill))
            }
        }
        Spacer(Modifier.weight(1f))
        // Home: the launcher on the glasses, over a soft black layer that sends the apps back.
        HeaderButton(Icons.Rounded.Home, lit = Air.launcherOpen) {
            if (Air.launcherOpen && Air.panels.isNotEmpty()) {
                Air.launcherOpen = false; Home.endSearch(); Home.settingsOpen = false; Home.openFolder = null
                choose(Pad.TOUCHPAD)
            } else choose(Pad.APPS)
        }
        HeaderButton(Icons.Rounded.ChevronLeft, lit = false) {
            Air.focused?.takeIf { it.remoteId >= 0 }?.let { runCatching { Air.host?.back(it.remoteId) } }
        }
        val typing = Typing.on || Home.searching || Home.renaming != null
        HeaderButton(if (typing) Icons.Rounded.KeyboardHide else Icons.Rounded.Keyboard, lit = typing) {
            if (typing) { Typing.on = false; Home.endSearch(); Home.renaming = null } else Typing.on = true
        }
    }
}

@Composable
private fun HeaderButton(icon: ImageVector, lit: Boolean, action: () -> Unit) {
    val view = LocalView.current
    Box(Modifier.size(48.dp).clip(pill).clickable { view.tick(); action() }, contentAlignment = Alignment.Center) {
        Image(icon, null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(if (lit) HudTheme.accent else HudTheme.ink))
    }
}

/** MiniMate's touchpad, over the halftone: one finger moves, a tap clicks, two scroll, two tap right-click. */
@OptIn(ExperimentalComposeUiApi::class)
@SuppressLint("ClickableViewAccessibility")
@Composable
private fun Touchpad(modifier: Modifier, glasses: Boolean) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val momentum = remember { PhysicsMomentumScroller(scope) { v, h -> Pointer.scroll(-h.toFloat(), -v.toFloat()) } }
    val density = LocalDensity.current.density
    val zones = remember {
        EdgeZones(
            density = density,
            onScroll = { v -> momentum.stop(); Pointer.scroll(0f, v) },
            onClickDown = { Pointer.down() },
            onClickUp = { Pointer.up() },
            onBack = {
                view.tick()
                Air.focused?.takeIf { it.remoteId >= 0 }?.let { runCatching { Air.host?.back(it.remoteId) } }
            },
            onTick = { view.tick() },
        )
    }
    val recognizer = remember {
        GestureRecognizer(context) { event ->
            when (event) {
                is GestureEvent.Move -> {
                    val dx = event.dx * CURSOR_SCALE
                    val dy = event.dy * CURSOR_SCALE
                    // The click corner held down is a held button: moving drags.
                    if (event.isDragging || zones.clickPressed) Pointer.moveWhilePressed(dx, dy) else Pointer.moveBy(dx, dy)
                }
                GestureEvent.TapClick -> { view.tick(); Pointer.tap() }
                GestureEvent.RightClick -> { view.tick(); Pointer.rightClick() }
                is GestureEvent.Scroll -> { momentum.stop(); Pointer.scroll(-event.hScroll.toFloat(), -event.vScroll.toFloat()) }
                is GestureEvent.ScrollFling -> momentum.startFling(event.velocityY, event.velocityX)
                GestureEvent.DoubleTapDragStart -> { view.tick(); Pointer.down() }
                GestureEvent.DragEnd -> Pointer.up()
                is GestureEvent.Click, is GestureEvent.Pinch -> Unit
            }
        }
    }
    Box(modifier
        .onSizeChanged {
            recognizer.screenWidth = it.width.toFloat(); recognizer.screenHeight = it.height.toFloat()
            zones.width = it.width.toFloat(); zones.height = it.height.toFloat()
        }
        .pointerInteropFilter { event ->
            zones.route(event)?.let { recognizer.onTouchEvent(it); it.recycle() }
            true
        }) {
        HalftoneBackground(Modifier.fillMaxSize())
        if (glasses && Air.host != null) EdgeControlsOverlay(zones)
        val note = when {
            Air.host == null -> "STARTING" to "Waiting for the SuperAir host over wireless debugging."
            !glasses -> "CONNECT THE GLASSES" to "Plug them into the phone's USB-C port."
            else -> null
        }
        note?.let { (title, detail) ->
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText(title, style = HudTheme.mont(14f, tracking = 2.5f))
                BasicText(detail, style = HudTheme.mont(13f, FontWeight.SemiBold, color = HudTheme.inkDim))
            }
        }
    }
}

/** TheHud's cursor speed: a pad point is 1.6 glasses pixels. */
private const val CURSOR_SCALE = 1.6f

// ------------------------------------------------------------------ the remote

@Composable
private fun Card(modifier: Modifier, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(modifier.clip(shape).background(Color(0xFF171717)).border(1.dp, HudTheme.edge, shape)) { content() }
}

/**
 * Point the phone and the cursor follows: the big card clicks (held, it drags inside the app), the
 * strip scrolls, RIGHT right-clicks, CENTRE puts the cursor back in the middle, and MOVE picks up
 * the app under the cursor so pointing carries it from cell to cell until MOVE is pressed again.
 */
@Composable
private fun RemotePad(modifier: Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    var holding by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gyroscope = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val listener = object : SensorEventListener {
            private var last = 0L
            override fun onSensorChanged(event: SensorEvent) {
                val now = event.timestamp
                val elapsed = if (last == 0L) 0f else (now - last) / 1e9f
                last = now
                // The phone is held folded, cover towards you: turning it left and right is about
                // its z axis, tipping it up and down about its x.
                val yaw = -event.values[2]
                val pitch = -event.values[0]
                if (abs(yaw) < DEADBAND && abs(pitch) < DEADBAND) return
                val dx = yaw * elapsed * GAIN
                val dy = pitch * elapsed * GAIN
                if (holding) Pointer.moveWhilePressed(dx, dy) else Pointer.moveBy(dx, dy)
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        if (gyroscope != null) sensors.registerListener(listener, gyroscope, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sensors.unregisterListener(listener) }
    }

    fun Modifier.press(onDown: () -> Unit, onUp: () -> Unit = {}) = pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown()
            view.tick()
            onDown()
            while (true) {
                val e = awaitPointerEvent()
                if (e.changes.none { it.pressed }) break
            }
            onUp()
        }
    }

    val scrollStrip = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown()
            view.tick()
            var last: Float? = null
            var carry = 0f
            while (true) {
                val e = awaitPointerEvent()
                val p = e.changes.firstOrNull() ?: break
                if (!p.pressed) break
                val y = p.position.y
                last?.let { carry += y - it }
                last = y
                // A notch for every twenty points; down the strip scrolls down the page.
                val step = 20 * density
                while (abs(carry) >= step) {
                    val t = if (carry > 0) 1 else -1
                    carry -= t * step
                    Pointer.scroll(0f, -t.toFloat())
                }
                p.consume()
            }
        }
    }

    Column(modifier.padding(horizontal = 12.dp).padding(top = 4.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Card(Modifier.weight(1f).fillMaxHeight().press(
                onDown = { holding = true; Pointer.down() },
                onUp = { holding = false; Pointer.up() })) {
                HalftoneBackground(Modifier.fillMaxSize())
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicText("CLICK", style = HudTheme.mont(16f, tracking = 3f))
                    BasicText("Point the phone · hold to drag", style = HudTheme.mont(11f, FontWeight.SemiBold, color = HudTheme.inkDim))
                }
            }
            Column(Modifier.width(80.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Card(Modifier.weight(1f).fillMaxWidth().then(scrollStrip)) {
                    BasicText("SCROLL", style = HudTheme.mont(10f, tracking = 2f, color = HudTheme.inkDim), modifier = Modifier.align(Alignment.Center))
                }
                Card(Modifier.height(80.dp).fillMaxWidth().press(onDown = {}, onUp = { Pointer.rightClick() })) {
                    BasicText("RIGHT", style = HudTheme.mont(10f, tracking = 2f, color = HudTheme.inkDim), modifier = Modifier.align(Alignment.Center))
                }
            }
        }
        Row(Modifier.height(64.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Card(Modifier.weight(1f).fillMaxHeight().press(onDown = {
                val (w, h) = Air.canvas
                Pointer.moveTo(w / 2f, h / 2f)
            })) {
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Image(Icons.Rounded.MyLocation, null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(HudTheme.ink))
                    BasicText("CENTRE", style = HudTheme.mont(12f, tracking = 2.5f))
                }
            }
            val carrying = Pointer.carrying != null
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp))
                .background(if (carrying) HudTheme.accent else Color(0xFF171717))
                .border(1.dp, HudTheme.edge, RoundedCornerShape(18.dp))
                .press(onDown = { if (Pointer.carrying != null) Pointer.drop() else Pointer.grab() })) {
                val fg = if (carrying) Color.Black else HudTheme.ink
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Image(Icons.Rounded.OpenWith, null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(fg))
                    BasicText(if (carrying) "DROP" else "MOVE", style = HudTheme.mont(12f, tracking = 2.5f, color = fg))
                }
            }
        }
    }
}

private const val DEADBAND = 0.02f
private const val GAIN = 2600f

// ------------------------------------------------------------------ icons

private val icons = HashMap<String, ImageBitmap?>()

@Composable
private fun AppIcon(app: AppEntry, modifier: Modifier) {
    val context = LocalContext.current
    val icon = remember(app.pkg) {
        icons.getOrPut(app.pkg) {
            runCatching { context.packageManager.getApplicationIcon(app.pkg).toBitmap(128, 128).asImageBitmap() }.getOrNull()
        }
    }
    if (icon != null) Image(icon, app.label, modifier)
    else Box(modifier.clip(pill).background(Color(0xFF2A2A2A)))
}

// ------------------------------------------------------------------ typing

/**
 * The system keyboard, typing into the focused panel. The field holds an invisible sentinel, so
 * a backspace on an empty field still arrives as a deletion and can be sent on.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun KeyboardProxy() {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val imeUp = WindowInsets.isImeVisible
    var wasUp by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf(TextFieldValue(SENTINEL, TextRange(1))) }

    BasicTextField(
        value = field,
        onValueChange = { next ->
            val old = field.text
            val now = next.text
            var common = 0
            while (common < old.length && common < now.length && old[common] == now[common]) common++
            repeat(old.length - common) { Pointer.key(KeyEvent.KEYCODE_DEL) }
            val added = now.substring(common)
            if (added.isNotEmpty()) Pointer.type(added)
            // Everything is sent as it changes. Mid-word the keyboard keeps its composing text;
            // once a word is done the field starts again from the sentinel, so it never grows.
            field = if (next.composition != null && now.startsWith(SENTINEL)) next
                else TextFieldValue(SENTINEL, TextRange(1))
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { Pointer.key(KeyEvent.KEYCODE_ENTER) }),
        modifier = Modifier.size(1.dp).alpha(0f).focusRequester(focus),
    )

    val wanted = Typing.on || Home.searching || Home.renaming != null
    LaunchedEffect(wanted) {
        if (wanted) { focus.requestFocus(); keyboard?.show() } else { keyboard?.hide(); focus.freeFocus() }
    }
    // The keyboard put away by hand ends the typing.
    LaunchedEffect(imeUp) {
        if (wasUp && !imeUp) { Typing.on = false; if (Home.searching) Home.endSearch(); Home.renaming = null }
        wasUp = imeUp
    }
}

private const val SENTINEL = "​"
