package dev.superair

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Owns a window over Settings and nothing else.
 *
 * Settings replaces its pairing code the moment its screen is left, and on a folded phone there is
 * no second screen to read the code on — so a dialog in our own app cannot work, and an
 * accessibility overlay is the one window an ordinary app can keep above Settings. Taken from
 * Kamui Desktop, where this was the answer to exactly the same problem.
 */
class PairingService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        // The glasses move up into an overlay, above DeX, as soon as the service is on.
        dev.superair.glasses.GlassesPresenter.serviceChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        PairingBar.hide()
        instance = null
        dev.superair.glasses.GlassesPresenter.serviceChanged()
        return super.onUnbind(intent)
    }

    /** Settings may be on the cover or on the glasses, so the bar goes where Settings is. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) PairingBar.follow(event.displayId)
    }

    override fun onInterrupt() = Unit

    companion object {
        @Volatile var instance: PairingService? = null
            private set

        /** Asked of the setting rather than of [instance], which is null for a moment after launch. */
        fun granted(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val target = ComponentName(context, PairingService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == target }
        }
    }
}

object PairingBar {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var manager: WindowManager? = null
    private var view: View? = null
    private var owner: OverlayOwner? = null
    private var displayId = -1
    private var code by mutableStateOf("")
    private var port by mutableStateOf("")
    private var status by mutableStateOf("Open Wireless debugging, then “Pair device with pairing code”")
    private var busy by mutableStateOf(false)

    /** A window that is not an activity still has to answer the three owners Compose looks for. */
    private class OverlayOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore = ViewModelStore()
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

        fun create() {
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }

    val showing: Boolean get() = view != null

    /** @return null when the bar is up, or a sentence saying why it is not. */
    fun show(display: Int): String? {
        hide()
        val service = PairingService.instance
            ?: return "Turn SuperAir on in Accessibility first."
        return runCatching {
            val type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            val target = service.getSystemService(DisplayManager::class.java).getDisplay(display)
                ?: error("display $display is gone")
            // A window context has to be built from a display context, or it lands on the display
            // the service thinks it is on, which on this phone is not the one in your hand.
            val context = service.createDisplayContext(target).createWindowContext(type, null)
            val launcher = HostLauncher.of(context)
            val overlay = OverlayOwner().apply { create() }
            val bar = ComposeView(context).apply {
                setViewTreeLifecycleOwner(overlay)
                setViewTreeViewModelStoreOwner(overlay)
                setViewTreeSavedStateRegistryOwner(overlay)
                setContent { Bar(launcher) }
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                // Focusable so it can be typed into, not touch-modal so Settings behind it still
                // takes taps — you have to be able to work Settings while this is up.
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                // Top, because Android draws the six digits low on the screen.
                gravity = Gravity.TOP or Gravity.START
                title = "SuperAir pairing"
            }
            val windows = context.getSystemService(WindowManager::class.java)
            windows.addView(bar, params)
            manager = windows
            view = bar
            owner = overlay
            displayId = display
            null
        }.getOrElse { it.message ?: it.javaClass.simpleName }
    }

    fun follow(display: Int) {
        if (view != null && display != displayId && display >= 0) show(display)
    }

    fun hide() {
        view?.let { runCatching { manager?.removeViewImmediate(it) } }
        owner?.destroy()
        view = null
        manager = null
        owner = null
    }

    private fun pair(launcher: HostLauncher, target: Int) {
        busy = true
        status = "Pairing…"
        scope.launch {
            launcher.pair(target, code).fold(
                { code = ""; port = ""; hide() },
                { status = "Failed: ${it.message}" }
            )
            busy = false
        }
    }

    /** The port comes from mDNS and is typed only if that failed, so it is the narrow field. */
    @Composable
    private fun Bar(launcher: HostLauncher) {
        val found by launcher.mdns.pairingPort.collectAsState()
        val target = port.toIntOrNull() ?: found
        val ready = !busy && code.length == 6 && target != null
        Column(
            Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.92f), RoundedCornerShape(24.dp))
                .border(1.5.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(24.dp))
                .padding(start = 16.dp, top = 10.dp, end = 10.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                (if (found != null && status.startsWith("Open")) "Found the port. Type the six digits." else status)
                    .uppercase(),
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Field(port, found?.toString() ?: "PORT", Modifier.width(66.dp)) {
                    port = it.filter(Char::isDigit).take(5)
                }
                Field(code, "CODE", Modifier.weight(1f)) { code = it.filter(Char::isDigit).take(6) }
                Label("PAIR", 14, if (ready) 1f else 0.3f,
                    Modifier.clickable(enabled = ready) { pair(launcher, target!!) }
                        .padding(horizontal = 6.dp, vertical = 7.dp))
                Label("CLOSE", 11, 0.58f,
                    Modifier.clickable { hide() }.padding(horizontal = 4.dp, vertical = 7.dp))
            }
        }
    }

    @Composable
    private fun Label(text: String, size: Int, alpha: Float, modifier: Modifier = Modifier) {
        Text(text, modifier, color = Color.White.copy(alpha = alpha),
            fontSize = size.sp, fontWeight = FontWeight.Black)
    }

    /** A field is a white block with black type: no outline, no label above it. */
    @Composable
    private fun Field(value: String, placeholder: String, modifier: Modifier, onValue: (String) -> Unit) {
        Box(
            modifier.height(34.dp).background(Color.White, RoundedCornerShape(17.dp))
                .padding(horizontal = 11.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                textStyle = TextStyle(color = Color.Black, fontWeight = FontWeight.Black,
                    fontSize = 15.sp, letterSpacing = 2.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                cursorBrush = SolidColor(Color.Black),
                modifier = Modifier.fillMaxWidth(),
            )
            if (value.isEmpty()) {
                Text(placeholder, color = Color.Black.copy(alpha = 0.35f),
                    fontSize = 11.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}
