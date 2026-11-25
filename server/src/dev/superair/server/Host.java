package dev.superair.server;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.display.DisplayManager;
import android.os.RemoteException;
import android.view.Display;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.superair.IAirClient;
import dev.superair.IAirHost;

/** Everything privileged, behind one binder. The app holds the other end. */
final class Host extends IAirHost.Stub {
    private final Map<Integer, Panel> panels = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Input input;
    private final Sys.KeepAwake awake = new Sys.KeepAwake();
    private int nextId = 1;
    private volatile IAirClient client;

    Host() throws Exception {
        input = new Input();
        awake.hold();
        Thread reaper = new Thread(this::reap, "panel-reaper");
        reaper.setDaemon(true);
        reaper.start();
        Thread guard = new Thread(this::guard, "panel-guard");
        guard.setDaemon(true);
        guard.start();
        // A host before this one may have died with the cover dark: never inherit that.
        if (Sys.isInteractive(COVER)) Sys.setScreenPower(COVER, true);
        Thread cover = new Thread(this::powerKeys, "power-keys");
        cover.setDaemon(true);
        cover.start();
        Thread glasses = new Thread(this::watchGlasses, "glasses-watch");
        glasses.setDaemon(true);
        glasses.start();
        Runtime.getRuntime().addShutdownHook(new Thread(this::restoreCover));
    }

    /**
     * The app has (re)connected.
     *
     * It only says hello with a binder it has not seen, which means either this host is new and
     * has no panels, or the app is new: restarted by an install, a crash or One UI, and holding
     * none of its old panels. Those panels' apps would keep running on displays nobody can see,
     * playing and draining until the glasses came out. So whatever is left is closed.
     */
    @Override public void hello(IAirClient client) {
        this.client = client;
        Log.d("the app is here");
        for (Integer id : new ArrayList<>(panels.keySet())) {
            Log.d("panel " + id + " was left from the app before; closing it");
            closePanel(id);
        }
        tellCover();
    }

    @Override public synchronized int openPanel(String pkg, String activity, String label, Surface surface,
                                                int width, int height, int dpi) throws RemoteException {
        int id = nextId++;
        Panel panel = new Panel(id, pkg, activity, label, width, height, dpi);
        try {
            panel.open(surface, glassesDisplay());
        } catch (Exception e) {
            panel.close();
            Log.e("could not open " + label, e);
            // The message is shown on the glasses, so it has to read like a sentence.
            throw new IllegalStateException(e.getMessage() == null ? "could not open " + label : e.getMessage());
        }
        panels.put(id, panel);
        Log.d("panel " + id + " is " + label + " on display " + panel.displayId());
        return id;
    }

    @Override public void resizePanel(int panelId, Surface surface, int width, int height, int dpi) {
        Panel panel = panels.get(panelId);
        if (panel != null) panel.resize(surface, width, height, dpi);
    }

    @Override public void setPanelSurface(int panelId, Surface surface) {
        Panel panel = panels.get(panelId);
        if (panel != null) panel.setSurface(surface);
    }

    @Override public void closePanel(int panelId) {
        Panel panel = panels.remove(panelId);
        if (panel == null) return;
        int task = panel.taskId();
        panel.close();
        // The display is gone; make sure the app went with it rather than reappearing somewhere.
        if (task >= 0) Sys.exec("am", "stack", "remove", String.valueOf(task));
        Log.d("panel " + panelId + " closed");
    }

    @Override public void focusPanel(int panelId) {
        Panel panel = panels.get(panelId);
        if (panel != null && panel.taskId() >= 0) Sys.focusTask(panel.taskId());
    }

    /** Label, package and activity, tab-separated, sorted by label. */
    @Override public List<String> apps() {
        List<String> out = new ArrayList<>();
        try {
            PackageManager pm = Sys.context().getPackageManager();
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo info : pm.queryIntentActivities(intent, 0)) {
                if (info.activityInfo == null) continue;
                if ("dev.superair".equals(info.activityInfo.packageName)) continue;
                CharSequence label = info.loadLabel(pm);
                out.add(label + "\t" + info.activityInfo.packageName + "\t" + info.activityInfo.name);
            }
            Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        } catch (Exception e) {
            Log.e("could not list apps", e);
        }
        return out;
    }

    /**
     * The glasses: anything that is not one of the phone's own two screens.
     *
     * On a Flip the inner screen is display 0 and the cover is 1, and the cover is flagged for
     * presentations too — so the id is the only thing that separates them from a display somebody
     * plugged in. DeX reports its display to apps as off, so the state is no guide. Taken from
     * TheHud, where this was already got wrong once.
     */
    @Override public int glassesDisplay() {
        try {
            DisplayManager dm = Sys.context().getSystemService(DisplayManager.class);
            Display best = null;
            for (Display display : dm.getDisplays()) {
                if (display.getDisplayId() < 2) continue;
                // The id alone is not enough. This phone already carries other projects' virtual
                // displays — a FarGalaxy display sat at id 7 and was taken for the glasses first
                // time out. The glasses arrive over DisplayPort, so they are the one external
                // display; everything virtual is somebody's software, ours included.
                if (typeOf(display) != TYPE_EXTERNAL) continue;
                if (best == null || display.getDisplayId() > best.getDisplayId()) best = display;
            }
            return best == null ? -1 : best.getDisplayId();
        } catch (Exception e) {
            Log.e("no display manager", e);
            return -1;
        }
    }

    /** Display.TYPE_EXTERNAL. Hidden, so it is read by reflection with the constant as a fallback. */
    private static final int TYPE_EXTERNAL = 2;

    private static int typeOf(Display display) {
        try {
            return (Integer) Display.class.getMethod("getType").invoke(display);
        } catch (Exception e) {
            return -1;
        }
    }

    private volatile android.hardware.display.VirtualDisplay standIn;

    @Override public synchronized int standIn(int width, int height, int dpi) {
        if (standIn != null) return standIn.getDisplay().getDisplayId();
        try {
            // The same flags a panel gets, minus nothing: it is standing in for a real screen, so
            // it must be trusted and must not invite One UI to put a launcher on it.
            android.media.ImageReader reader = android.media.ImageReader.newInstance(
                    Math.max(1, width / 8), Math.max(1, height / 8),
                    android.graphics.PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(source -> {
                android.media.Image image = source.acquireLatestImage();
                if (image != null) image.close();
            }, new android.os.Handler(android.os.Looper.getMainLooper()));
            standIn = Sys.createDisplay("superair-standin", width, height, dpi,
                    reader.getSurface(), Panel.standInFlags());
            int id = standIn.getDisplay().getDisplayId();
            Log.d("stand-in display " + id + " at " + width + "x" + height + "/" + dpi);
            return id;
        } catch (Exception e) {
            Log.e("no stand-in display", e);
            return -1;
        }
    }

    private int displayOf(int panelId) {
        Panel panel = panels.get(panelId);
        return panel == null ? -1 : panel.displayId();
    }

    @Override public void touch(int panelId, String phase, float x, float y) {
        input.touch(displayOf(panelId), phase, x, y);
    }

    @Override public void hover(int panelId, float x, float y) {
        input.hover(displayOf(panelId), x, y);
    }

    @Override public void scroll(int panelId, float x, float y, float dx, float dy) {
        input.scroll(displayOf(panelId), x, y, dx, dy);
    }

    @Override public void rightClick(int panelId, float x, float y) {
        input.rightClick(displayOf(panelId), x, y);
    }

    @Override public void key(int panelId, boolean down, int code, int meta) {
        input.key(displayOf(panelId), down, code, meta);
    }

    @Override public void text(int panelId, String text) {
        input.text(displayOf(panelId), text);
    }

    @Override public void back(int panelId) {
        input.tapKey(displayOf(panelId), android.view.KeyEvent.KEYCODE_BACK);
    }

    @Override public void rebindAccessibility(String component) {
        String enabled = Sys.exec("settings", "get", "secure", "enabled_accessibility_services").trim();
        if (enabled.isEmpty() || "null".equals(enabled) || !enabled.contains(component)) return;
        StringBuilder without = new StringBuilder();
        for (String part : enabled.split(":")) {
            if (part.equals(component)) continue;
            if (without.length() > 0) without.append(':');
            without.append(part);
        }
        Log.d("rebinding " + component);
        Sys.exec("settings", "put", "secure", "enabled_accessibility_services",
                without.length() == 0 ? "\"\"" : without.toString());
        try { Thread.sleep(400); } catch (InterruptedException ignored) { }
        Sys.exec("settings", "put", "secure", "enabled_accessibility_services", enabled);
    }

    @Override public void allowNotificationListener(String component) {
        if (component == null || !component.startsWith("dev.superair/")) return;
        Log.d("allowing notification listener " + component + ": "
                + Sys.exec("cmd", "notification", "allow_listener", component).trim());
    }

    @Override public void wakeCover() {
        if (!coverDark) return;
        coverDark = false;
        Sys.setScreenPower(COVER, true);
        Log.d("cover touched: back on");
        tellCover();
    }

    private void tellCover() {
        IAirClient c = client;
        if (c != null) try { c.coverDark(coverDark); } catch (Exception ignored) { }
    }

    /** Never leave the cover dark behind us: a dark screen that still takes touches is a trap. */
    void restoreCover() {
        if (coverDark) Sys.setScreenPower(COVER, true);
        coverDark = false;
    }

    @Override public void shutdown() {
        Log.d("shutting down");
        for (Integer id : new ArrayList<>(panels.keySet())) closePanel(id);
        awake.release();
        restoreCover();
        System.exit(0);
    }

    /** How long the glasses may be gone before the host gives up. A cable wiggle is shorter. */
    private static final long GONE_MILLIS = 10_000;

    /**
     * Ends the host once the glasses have gone.
     *
     * The host holds a screen wake lock for the glasses, and nothing else ever let it go: with the
     * glasses in a drawer it kept the phone awake for a whole day. So without the glasses, or the
     * development stand-in, for GONE_MILLIS, everything is closed and the process exits. The app
     * starts a new host when the glasses come back. The clock starts at launch, so a host started
     * with nothing plugged in goes too.
     */
    private void watchGlasses() {
        long seen = System.currentTimeMillis();
        while (true) {
            try {
                Thread.sleep(2000);
                if (glassesDisplay() >= 0 || standIn != null) {
                    seen = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - seen > GONE_MILLIS) {
                    Log.d("the glasses are gone");
                    shutdown();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                Log.e("glasses watch", e);
            }
        }
    }

    /**
     * Keeps every panel's app on its panel.
     *
     * Opening an app on its display is checked once, at launch. But apps start more of themselves
     * later — a popup, a new window, a trampoline activity that relaunches the real one — and a new
     * task with no display of its own goes to the phone's focused display, which on a folded Flip is
     * the cover screen. Samsung Internet does exactly that. So the phone's own two screens are
     * watched, and any task of an app that has a panel is put straight back on that panel.
     */
    private void guard() {
        while (true) {
            try {
                Thread.sleep(150);
                if (panels.isEmpty()) continue;
                for (Object[] task : Sys.allTasks()) {
                    int taskId = (Integer) task[0];
                    int displayId = (Integer) task[1];
                    if (displayId != 0 && displayId != 1) continue;
                    @SuppressWarnings("unchecked") java.util.Set<String> pkgs = (java.util.Set<String>) task[2];
                    if (pkgs.contains("dev.superair")) continue;
                    for (Panel panel : new ArrayList<>(panels.values())) {
                        if (!pkgs.contains(panel.pkg) || panel.displayId() < 0) continue;
                        Log.d(panel.label + " opened task " + taskId + " on display " + displayId + "; moving it back to its panel");
                        Sys.moveTask(taskId, panel.displayId());
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                Log.e("guard", e);
            }
        }
    }

    /** The cover's panel is dark while the phone stays awake for the apps on the glasses. */
    private volatile boolean coverDark;
    private static final int COVER = 1;

    /**
     * The power button, while the glasses are on.
     *
     * One UI keeps every panel in the phone's own display group whatever flags it asks for, so
     * sleeping the phone sleeps the panels: their apps stop and only the outlines are left. So a
     * press that puts the phone to sleep with the glasses on is undone at once: the phone is woken
     * straight back up, the lock screen goes up, and only the cover's screen is switched off. The
     * next press brings the cover back, on the lock screen, with SuperAir in front behind it.
     *
     * Driven by the power key itself, read from its input device, not by polling the power state:
     * with the fingerprint on the power key, a sleep and the wake that follows can both fall
     * between two polls, and then the cover was left dark with nothing to bring it back.
     */
    private void powerKeys() {
        String device = powerKeyDevice();
        if (device == null) {
            Log.d("no power key device readable; the cover will not be kept dark");
            return;
        }
        Log.d("watching the power key on " + device);
        java.util.concurrent.ExecutorService work = java.util.concurrent.Executors.newSingleThreadExecutor();
        while (true) {
            try {
                java.lang.Process p = new ProcessBuilder("getevent", "-ql", device).redirectErrorStream(true).start();
                try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.contains("KEY_POWER") && line.contains("DOWN")) work.execute(this::powerPressed);
                    }
                }
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                Log.e("power key", e);
                try { Thread.sleep(2000); } catch (InterruptedException ie) { return; }
            }
        }
    }

    /** The input device that carries KEY_POWER, such as /dev/input/event1. */
    private static String powerKeyDevice() {
        String current = null;
        for (String line : Sys.exec("getevent", "-pl").split("\n")) {
            if (line.startsWith("add device")) current = line.substring(line.indexOf(':') + 1).trim();
            else if (current != null && line.contains("KEY_POWER")) return current;
        }
        return null;
    }

    private void powerPressed() {
        try {
            boolean slept = awaitSleep(1500);
            if (coverDark) {
                // The system thinks the screen is on, so this press put it to sleep: wake it, onto
                // the lock screen that went up when the cover went dark.
                coverDark = false;
                tellCover();
                Sys.wakeUp();
                Sys.setScreenPower(COVER, true);
                Thread.sleep(250);
                Sys.setScreenPower(COVER, true);
                frontOnCover();
                Log.d("cover back on" + (slept ? "" : " (no sleep seen)"));
            } else if (slept && glassesDisplay() >= 0) {
                Sys.wakeUp();
                coverDark = true;
                tellCover();
                Log.d("power button with the glasses on: apps kept running, cover off and locked");
                Sys.lockNow();
                darken();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Waits for the press to put the cover to sleep; false if it did not, as for a long press. */
    private static boolean awaitSleep(long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (!Sys.isInteractive(COVER)) return true;
            Thread.sleep(10);
        }
        return false;
    }

    /** One UI goes back to its own cover home after the lock screen; SuperAir belongs in front. */
    private static void frontOnCover() {
        Sys.exec("am", "start", "--user", "0", "--display", String.valueOf(COVER),
                "-f", "0x10000000", "-n", "dev.superair/.MainActivity");
    }

    /** The power manager turns the screen back on as it wakes, a beat later: switch it off after. */
    private void darken() throws InterruptedException {
        for (int i = 0; i < 4 && coverDark; i++) {
            Thread.sleep(i == 0 ? 250 : 400);
            if (coverDark) Sys.setScreenPower(COVER, false);
        }
    }

    /** An app that backs out of its own panel should take the panel with it. */
    private void reap() {
        while (true) {
            try {
                Thread.sleep(2000);
                for (Map.Entry<Integer, Panel> entry : new ArrayList<>(panels.entrySet())) {
                    if (!entry.getValue().isEmpty()) continue;
                    int id = entry.getKey();
                    Log.d("panel " + id + " emptied itself");
                    closePanel(id);
                    IAirClient c = client;
                    if (c != null) try { c.panelGone(id); } catch (RemoteException ignored) { client = null; }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                Log.e("reaper", e);
            }
        }
    }
}
