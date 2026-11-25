package dev.superair.glasses

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Finds the glasses and keeps the scene on them.
 *
 * On a Flip the inner screen is display 0 and the cover is 1, and the cover is flagged for
 * presentations too — so the id is the only thing that separates the phone's own screens from
 * something plugged in. DeX reports its display to apps as off, so the state is no guide. All of
 * that was learned in TheHud, where it was got wrong first.
 *
 * The phone is folded the entire time this is in use, so nothing here may depend on display 0.
 */
object GlassesPresenter {
    private const val TAG = "SuperAir"
    private var presentation: Presentation? = null
    private var overlay: GlassesOverlay? = null
    private var shownOn: Int? = null
    private var retries = 0
    private lateinit var app: Context

    /** The application context, once started. */
    val appContext: Context? get() = if (::app.isInitialized) app else null
    private val main = Handler(Looper.getMainLooper())

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { retries = 0; refresh() }
        override fun onDisplayRemoved(displayId: Int) = refresh()
        // DeX turns the display "off" and on again as it takes it over; each time is a chance
        // that our window was thrown away with it.
        override fun onDisplayChanged(displayId: Int) { if (displayId == shownOn || shownOn == null) refresh() }
    }

    val attached: Boolean get() = overlay != null || presentation?.isShowing == true

    /**
     * Use a host-made display when nothing is plugged in.
     *
     * Development only and off unless asked for: it is how the scene gets looked at over scrcpy
     * with no glasses attached. A display nobody can see still costs power, so it is never the
     * default.
     */
    var useStandIn: Boolean
        get() = ::app.isInitialized &&
            app.getSharedPreferences("host", Context.MODE_PRIVATE).getBoolean("standIn", false)
        set(value) {
            if (!::app.isInitialized) return
            app.getSharedPreferences("host", Context.MODE_PRIVATE).edit()
                .putBoolean("standIn", value).apply()
            standInId = null
            refresh()
        }

    private var standInId: Int? = null

    /** True between start and stop: while AirService runs, which is while the glasses are on. */
    @Volatile var running = false
        private set

    /** A safety net: whatever took our window away without saying so, it comes back. */
    private val safetyNet = object : Runnable {
        override fun run() { refresh(); main.postDelayed(this, 3000) }
    }

    fun start(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { start(context) }; return }
        if (!::app.isInitialized) {
            app = context.applicationContext
            Home.init(app)
            Weather.start(app)
            Battery.start(app)
        }
        if (!running) {
            running = true
            app.getSystemService(DisplayManager::class.java).registerDisplayListener(listener, main)
            main.removeCallbacks(safetyNet)
            main.postDelayed(safetyNet, 3000)
        }
        refresh()
    }

    /** The accessibility service came or went: the scene moves between the overlay and the presentation. */
    fun serviceChanged() {
        if (!::app.isInitialized) return
        main.post { tearDown(); retries = 0; refresh() }
    }

    /** Takes the scene down and stops watching for it, until the next start. */
    fun stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { stop() }; return }
        running = false
        main.removeCallbacks(safetyNet)
        if (::app.isInitialized) app.getSystemService(DisplayManager::class.java).unregisterDisplayListener(listener)
        tearDown()
    }

    private var rebindAsked = false

    /**
     * After an update Android can leave the service switched on but never bind it, and then the
     * glasses are stuck below DeX until somebody toggles it by hand. The host is the shell user,
     * which may change that setting, so it toggles it for us — once per run.
     */
    fun ensureService() {
        if (!::app.isInitialized || rebindAsked) return
        main.postDelayed({
            if (rebindAsked || dev.superair.PairingService.instance != null) return@postDelayed
            if (!dev.superair.PairingService.granted(app)) return@postDelayed
            val host = dev.superair.Air.host ?: return@postDelayed
            rebindAsked = true
            Log.i(TAG, "accessibility service is on but not bound; asking the host to rebind it")
            val component = android.content.ComponentName(app, dev.superair.PairingService::class.java).flattenToString()
            Thread { runCatching { host.rebindAccessibility(component) } }.start()
        }, 3000)
    }

    private fun tearDown() {
        presentation?.dismiss()
        presentation = null
        overlay?.remove()
        overlay = null
        shownOn = null
    }

    /**
     * The glasses: the one external display after the phone's own two screens.
     *
     * The id alone is not enough. This phone already carries other projects' virtual displays — a
     * FarGalaxy display sat at id 7 and was taken for the glasses first time out — and SuperAir
     * adds one per panel of its own. The glasses arrive over DisplayPort, so they are the one
     * external display; everything virtual is somebody's software, ours included.
     */
    fun glassesDisplay(context: Context = app): Display? {
        val manager = context.getSystemService(DisplayManager::class.java)
        return manager.displays
            .filter { it.displayId >= 2 && it.type() == TYPE_EXTERNAL }
            .maxByOrNull { it.displayId }
    }

    /**
     * Whether SuperAir has anywhere to be: the glasses are plugged in, or the development stand-in
     * is switched on. Everything that holds a lock, AirService and the host, runs only while this
     * is true.
     */
    fun wanted(context: Context): Boolean =
        glassesDisplay(context) != null ||
            context.getSharedPreferences("host", Context.MODE_PRIVATE).getBoolean("standIn", false)

    private fun standIn(): Display? {
        if (!useStandIn) return null
        val host = dev.superair.Air.host ?: return null
        val manager = app.getSystemService(DisplayManager::class.java)
        // The stand-in belongs to the host that made it. A new host has none, and without one it
        // sees no screen and exits: so a remembered id counts only while its display exists.
        standInId?.let { manager.getDisplay(it) }?.let { return it }
        val id = runCatching { host.standIn(1920, 1080, 160) }.getOrNull()?.takeIf { it >= 0 } ?: return null
        standInId = id
        return manager.getDisplay(id)
    }

    private fun Display.type(): Int = runCatching {
        Display::class.java.getMethod("getType").invoke(this) as Int
    }.getOrDefault(-1)

    /** Display.TYPE_EXTERNAL, which is hidden. */
    private const val TYPE_EXTERNAL = 2

    fun refresh() {
        if (!::app.isInitialized || !running) return
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { refresh() }; return }
        val display = glassesDisplay() ?: standIn()
        val service = dev.superair.PairingService.instance
        val upToDate = when {
            service != null -> overlay?.isShowing == true
            else -> presentation?.isShowing == true
        }
        if (display?.displayId == shownOn && upToDate) return
        tearDown()
        if (display == null) return
        Log.i(TAG, "glasses on display ${display.displayId} (${display.name}), overlay=${service != null}")

        // Samsung DeX takes the glasses over and puts its taskbar, launcher and wallpaper there,
        // and it throws an ordinary Presentation away within seconds. An accessibility overlay is
        // the one kind of window that sits above all of DeX, and SuperAir already has the service
        // for the pairing bar. Learned in TheHud.
        if (service != null) {
            val shown = runCatching { GlassesOverlay(service, display) }
                .onFailure { Log.w(TAG, "no overlay on ${display.displayId}", it) }.getOrNull()
            if (shown != null) { overlay = shown; shownOn = display.displayId; retries = 0; return }
            // Just after the glasses arrive the service may not be allowed on the display yet
            // ("token null is not valid"): show below DeX for now and try again shortly.
            if (retries < 6) main.postDelayed({ if (overlay == null) refresh() }, 300L shl retries++)
        }
        val shown = runCatching { AirPresentation(app, display).also { it.show() } }
            .onFailure { Log.w(TAG, "no presentation on ${display.displayId}", it) }
            .getOrNull() ?: return
        presentation = shown
        shownOn = display.displayId
        shown.setOnDismissListener {
            if (presentation === shown) {
                presentation = null; shownOn = null
                Log.i(TAG, "presentation dismissed by the system; putting it back")
                main.postDelayed({ refresh() }, 500)
            }
        }
    }
}

/** The scene as an accessibility overlay: above everything on the display, DeX's taskbar included. */
private class GlassesOverlay(service: android.accessibilityservice.AccessibilityService, display: Display) {
    private val owner = AlwaysOn()
    private val context = service.createDisplayContext(display)
        .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
    private val manager = context.getSystemService(WindowManager::class.java)
    private val view = ComposeView(context).apply {
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        setContent { AirScene() }
    }

    private var attachedOnce = false
    private var removed = false

    /** Up until the system takes it away. Not yet attached, just after adding, still counts. */
    val isShowing: Boolean get() = !removed && (!attachedOnce || view.isAttachedToWindow)

    init {
        owner.resume()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            android.graphics.PixelFormat.OPAQUE,
        ).apply {
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "SuperAir glasses"
        }
        view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) { attachedOnce = true }
            override fun onViewDetachedFromWindow(v: android.view.View) = Unit
        })
        manager.addView(view, params)
    }

    fun remove() {
        removed = true
        runCatching { manager.removeView(view) }
        owner.destroy()
    }
}

/** The scene's window on the glasses. Not touchable: every touch arrives from the folded phone. */
private class AirPresentation(context: Context, display: Display) : Presentation(context, display) {
    private val owner = AlwaysOn()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        // Black, and nothing behind it. The optics are additive, so this is what makes the room
        // show through everywhere a panel is not.
        window?.setBackgroundDrawableResource(android.R.color.black)
        window?.decorView?.let {
            it.setViewTreeLifecycleOwner(owner)
            it.setViewTreeSavedStateRegistryOwner(owner)
        }
        owner.resume()
        setContentView(ComposeView(context).apply { setContent { AirScene() } })
    }

    override fun onStop() {
        super.onStop()
        owner.destroy()
    }
}

/**
 * A lifecycle for a window that has none.
 *
 * A Presentation is not an activity, and Compose needs an owner to attach to. Taken from TheHud.
 */
private class AlwaysOn : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this).also { it.performRestore(null) }

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    fun resume() { registry.currentState = Lifecycle.State.RESUMED }
    fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
}
