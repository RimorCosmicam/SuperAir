package dev.superair;

import dev.superair.IAirClient;
import android.view.Surface;

/**
 * The shell-uid host, as the app calls it.
 *
 * Only uid 2000 may create a trusted display, and only a trusted display will host another app's
 * windows — so everything here runs in an app_process started over the phone's own wireless ADB,
 * and the app reaches it through this binder. The Surface arguments are the whole reason this is a
 * binder and not a socket: a panel's pixels are delivered straight into the app's own SurfaceView,
 * with no encoder and no copy, because both ends are on the same phone.
 */
interface IAirHost {
    /** Register for panel events. Called once, as soon as the app receives this binder. */
    void hello(IAirClient client);

    /**
     * Create a display of exactly this size, draw it into `surface`, and land the app on it.
     * Returns the panel id, or throws with a message fit to show on the glasses.
     */
    int openPanel(String pkg, String activity, String label, in Surface surface, int width, int height, int dpi);

    /**
     * Give the panel a new size, and the new surface that goes with it.
     *
     * A real display resize, not a scale: the app relayouts at the true pixel size of its grid
     * footprint, so text stays sharp at every size.
     */
    void resizePanel(int panelId, in Surface surface, int width, int height, int dpi);

    /**
     * Point a panel at a different surface — used when its SurfaceView is recreated.
     *
     * Passing a null surface parks the display on a tiny reader instead. A display must never be
     * left with no surface at all: the framework decides it is unusable and moves its tasks to the
     * default display, which on a folded Flip is the cover screen.
     */
    void setPanelSurface(int panelId, in Surface surface);

    void closePanel(int panelId);

    /** Bring a panel's task to the front of its own display, so it takes key focus. */
    void focusPanel(int panelId);

    /** Packages worth showing in the launcher: label, package and activity, tab-separated. */
    List<String> apps();

    /** The display the glasses are on, as the host sees it, or -1. */
    int glassesDisplay();

    /**
     * A display to stand in for the glasses while they are not plugged in, or -1.
     *
     * Development only, and off by default: it exists so the scene can be looked at over scrcpy
     * with nothing attached to the phone. It is created once and kept, because tearing a display
     * down under the panels on it is how tasks end up back on the cover screen.
     */
    int standIn(int width, int height, int dpi);

    oneway void touch(int panelId, String phase, float x, float y);
    oneway void hover(int panelId, float x, float y);
    oneway void scroll(int panelId, float x, float y, float dx, float dy);
    oneway void rightClick(int panelId, float x, float y);
    oneway void key(int panelId, boolean down, int code, int meta);
    /** Commit a whole string, so the cover keyboard is not a key-event-per-character typewriter. */
    oneway void text(int panelId, String text);
    oneway void back(int panelId);

    /**
     * Turn an accessibility service off and on again. Android sometimes leaves a service enabled
     * but unbound after the app is updated, and only a change to the setting binds it again.
     */
    void rebindAccessibility(String component);
    /** Switches SuperAir's notification listener on, as the shell user may. */
    void allowNotificationListener(String component);
    /** Turns the cover's screen back on after the power button darkened it. */
    void wakeCover();

    void shutdown();
}
