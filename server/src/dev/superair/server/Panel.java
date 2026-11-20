package dev.superair.server;

import android.graphics.PixelFormat;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

/**
 * One app, on a display of its own, drawn straight into a SurfaceView on the glasses.
 *
 * Kamui Desktop encoded this to H.264 because its client was a browser on another machine. Here
 * both ends are the same phone, so the app's pixels go into the panel's surface and are composited
 * by SurfaceFlinger — no encoder, no decoder, no latency worth the name, and no hardware encoder
 * budget to ration.
 */
final class Panel {
    /**
     * SHOULD_SHOW_SYSTEM_DECORATIONS (bit 9) is deliberately absent: with it One UI attaches its
     * own launcher and taskbar to any trusted display large enough, and a panel is one app's
     * rectangle, not a desktop. OWN_FOCUS and OWN_DISPLAY_GROUP keep each panel's focus to itself,
     * so clicking one does not steal the caret from another.
     */
    private static final int FLAGS = (1 << 0)   // PUBLIC
            | (1 << 1)    // PRESENTATION
            | (1 << 3)    // OWN_CONTENT_ONLY
            | (1 << 6)    // SUPPORTS_TOUCH
            | (1 << 7)    // ROTATES_WITH_CONTENT
            | (1 << 8)    // DESTROY_CONTENT_ON_REMOVAL
            | (1 << 10)   // TRUSTED
            | (1 << 11)   // OWN_DISPLAY_GROUP
            | (1 << 12)   // ALWAYS_UNLOCKED
            | (1 << 13)   // TOUCH_FEEDBACK_DISABLED
            | (1 << 14);  // OWN_FOCUS
    // Not DEVICE_DISPLAY_GROUP (1 << 15). One UI puts the panel in the phone's group 0 either way,
    // so the power button would sleep it: Host.powerKeys keeps the phone awake and darkens only the
    // cover instead.

    /** The same flag word, for the development stand-in display. */
    static int standInFlags() {
        return FLAGS;
    }

    final int id;
    final String pkg;
    final String activity;
    final String label;
    private final long createdAt = System.currentTimeMillis();

    private int width, height, dpi;
    private VirtualDisplay display;
    private int taskId = -1;
    private int emptyPolls;

    /**
     * Where the display draws when the app has taken its SurfaceView away.
     *
     * Never leave a display with no surface. miniMont and Kamui both found the same thing the hard
     * way: the framework decides such a display is unusable and relocates its tasks to the default
     * display — which on a folded Flip is the cover screen, in your hand, under the touchpad.
     */
    private ImageReader parked;

    Panel(int id, String pkg, String activity, String label, int width, int height, int dpi) {
        this.id = id;
        this.pkg = pkg;
        this.activity = activity;
        this.label = label;
        this.width = even(width);
        this.height = even(height);
        this.dpi = dpi;
    }

    /** Keep sizes sane: a display of two pixels is a crash waiting on somebody else's layout pass. */
    private static int even(int v) {
        return Math.max(160, v & ~1);
    }

    /** Somewhere to put focus while launching: the glasses. See launch(). */
    private int elsewhere = -1;
    private static final int COVER = 1;

    synchronized void open(Surface surface, int elsewhere) throws Exception {
        this.elsewhere = elsewhere;
        parked = parkedReader(width, height);
        display = Sys.createDisplay("superair-" + id, width, height, dpi,
                surface != null ? surface : parked.getSurface(), FLAGS);
        if (display == null) throw new IllegalStateException("the system refused a display for " + label);
        if (!Sys.awaitWindowDisplay(displayId(), 2000)) Log.d("display " + displayId() + " is slow to appear");
        Sys.hideImeOn(displayId());
        launch();
    }

    private static ImageReader parkedReader(int width, int height) {
        ImageReader reader = ImageReader.newInstance(
                Math.max(1, width / 8), Math.max(1, height / 8), PixelFormat.RGBA_8888, 2);
        // Emptied as it fills, so the producer never stalls. On the main looper explicitly: a null
        // handler means "this thread's looper", and the thread that opens a panel has none.
        reader.setOnImageAvailableListener(source -> {
            Image image = source.acquireLatestImage();
            if (image != null) image.close();
        }, new Handler(Looper.getMainLooper()));
        return reader;
    }

    int displayId() {
        return display == null ? -1 : display.getDisplay().getDisplayId();
    }

    int taskId() {
        return taskId;
    }

    /**
     * Lands the app on this display or fails loudly: nothing SuperAir starts may stay on the cover
     * screen, which on a folded phone is the touchpad under your thumb.
     *
     * `am start --display` only creates a task when the app has none; an app already running comes
     * forward wherever it already is. So an existing task is moved first, and a new one is checked
     * afterwards. Verified again on Android 17: launching Settings while Settings was already open
     * on the cover display left it exactly where it was.
     */
    private void launch() throws Exception {
        int target = displayId();
        int[] before = Sys.findTask(pkg);
        if (before != null && before[1] != target && !moveHere(before[0], target)) {
            throw new IllegalStateException(label + " is already open elsewhere and could not be moved");
        }
        // One UI sends a new task of any app enabled for the cover launcher to the cover screen
        // whenever the cover has focus, whatever display was asked for (ActivityStarter: "modifying
        // PreferredTaskDisplayArea for launching app"). The app flashed up under your thumb until
        // it was moved. So focus is put on the glasses for the launch and given back after.
        Sys.focusDisplay(elsewhere, null);
        String out;
        try {
            out = Sys.exec("am", "start", "--user", "0", "--display", String.valueOf(target),
                "--windowingMode", "1", "-a", "android.intent.action.MAIN",
                "-c", "android.intent.category.LAUNCHER", "-f", "0x10200000", "-n", pkg + "/" + activity);
        } finally {
            Sys.focusDisplay(COVER, "dev.superair");
        }
        if (out.contains("Error") || out.contains("Exception")) Log.d("am: " + out.trim());
        int[] task = null;
        for (int i = 0; i < 20; i++) {
            task = Sys.findTask(pkg);
            if (task != null && task[1] == target) { taskId = task[0]; return; }
            if (task != null && i >= 2) break; // landed elsewhere: move it now, not in three seconds
            Thread.sleep(100);
        }
        if (task == null) throw new IllegalStateException(label + " did not start");
        if (moveHere(task[0], target)) { taskId = task[0]; return; }
        if (before == null) Sys.exec("am", "stack", "remove", String.valueOf(task[0]));
        throw new IllegalStateException(label + " opened on display " + task[1] + " and could not be moved");
    }

    private boolean moveHere(int taskId, int target) throws InterruptedException {
        Sys.moveTask(taskId, target);
        for (int i = 0; i < 10; i++) {
            int[] task = Sys.findTask(pkg);
            if (task != null && task[1] == target) { this.taskId = task[0]; return true; }
            Thread.sleep(150);
        }
        return false;
    }

    /**
     * A new size, and the surface that goes with it.
     *
     * This is a real display resize rather than a scale, which is the whole reason a panel stays
     * sharp at every grid footprint: the app relayouts at the true pixel size of its cell.
     */
    synchronized void resize(Surface surface, int w, int h, int newDpi) {
        if (display == null) return;
        int nw = even(w), nh = even(h);
        boolean sameShape = nw == width && nh == height && newDpi == dpi;
        width = nw;
        height = nh;
        dpi = newDpi;
        if (!sameShape) {
            ImageReader older = parked;
            parked = parkedReader(width, height);
            display.resize(width, height, dpi);
            if (older != null) older.close();
        }
        display.setSurface(surface != null ? surface : parked.getSurface());
    }

    /** Point the display somewhere else — a recreated SurfaceView, or the parked reader. */
    synchronized void setSurface(Surface surface) {
        if (display == null) return;
        display.setSurface(surface != null ? surface : parked.getSurface());
    }

    synchronized void close() {
        if (display != null) {
            // Park it first: releasing a display whose surface has already gone is the path where
            // the framework gets a chance to relocate the task instead of destroying it.
            display.setSurface(null);
            display.release();
        }
        display = null;
        if (parked != null) parked.close();
        parked = null;
    }

    /** True once the app has left its display for good — backed out, finished, or crashed. */
    boolean isEmpty() {
        if (display == null || System.currentTimeMillis() - createdAt < 8000) return false;
        java.util.List<?> tasks = Sys.tasksOn(displayId());
        if (tasks == null || !tasks.isEmpty()) {
            emptyPolls = 0;
            return false;
        }
        return ++emptyPolls >= 2;
    }
}
