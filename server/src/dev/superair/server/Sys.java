package dev.superair.server;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Process;
import android.view.Surface;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/**
 * The Android framework, reached from a bare app_process running as the shell user.
 *
 * The ActivityThread bootstrap follows scrcpy (Apache-2.0), which documents the Samsung-specific
 * needs; the display and task calls come from Kamui Desktop and miniMont, where each one was
 * measured on this phone rather than guessed.
 */
public final class Sys {
    private static Object activityThread;
    private static Context context;

    private Sys() {}

    public static void init() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Constructor<?> ctor = at.getDeclaredConstructor();
        ctor.setAccessible(true);
        activityThread = ctor.newInstance();
        Field current = at.getDeclaredField("sCurrentActivityThread");
        current.setAccessible(true);
        current.set(null, activityThread);
        Field system = at.getDeclaredField("mSystemThread");
        system.setAccessible(true);
        system.setBoolean(activityThread, true);

        // Samsung's DisplayManagerGlobal needs a ConfigurationController on the ActivityThread.
        try {
            Class<?> cc = Class.forName("android.app.ConfigurationController");
            Constructor<?> ccCtor = cc.getDeclaredConstructor(Class.forName("android.app.ActivityThreadInternal"));
            ccCtor.setAccessible(true);
            Field f = at.getDeclaredField("mConfigurationController");
            f.setAccessible(true);
            f.set(activityThread, ccCtor.newInstance(activityThread));
        } catch (Throwable t) {
            Log.d("no configuration controller: " + t);
        }
        try {
            Class<?> bind = Class.forName("android.app.ActivityThread$AppBindData");
            Constructor<?> bindCtor = bind.getDeclaredConstructor();
            bindCtor.setAccessible(true);
            Object data = bindCtor.newInstance();
            ApplicationInfo info = new ApplicationInfo();
            info.packageName = ShellContext.PACKAGE;
            Field appInfo = bind.getDeclaredField("appInfo");
            appInfo.setAccessible(true);
            appInfo.set(data, info);
            Field bound = at.getDeclaredField("mBoundApplication");
            bound.setAccessible(true);
            bound.set(activityThread, data);
        } catch (Throwable t) {
            Log.d("no app bind data: " + t);
        }

        Method getSystemContext = at.getDeclaredMethod("getSystemContext");
        context = new ShellContext((Context) getSystemContext.invoke(activityThread));
    }

    public static Context context() {
        return context;
    }

    /**
     * A trusted virtual display.
     *
     * TRUSTED is the flag that cannot be given up: it is what lets another app's activity be
     * launched here at all, it is uid-2000-only, and Google removed it in Android 15 QPR2 before
     * restoring it in 16 with a note saying it may go again. Verified working on Android 17 on this
     * phone. SHOULD_SHOW_SYSTEM_DECORATIONS is deliberately absent — with it One UI attaches its own
     * launcher and taskbar to any trusted display big enough to hold them, and a panel is one app's
     * rectangle, not a desktop.
     */
    public static VirtualDisplay createDisplay(String name, int w, int h, int dpi, Surface surface, int flags)
            throws Exception {
        Constructor<DisplayManager> ctor = DisplayManager.class.getDeclaredConstructor(Context.class);
        ctor.setAccessible(true);
        return ctor.newInstance(context).createVirtualDisplay(name, w, h, dpi, surface, flags);
    }

    public static IInterface service(String name, String type) throws Exception {
        Method get = Class.forName("android.os.ServiceManager").getDeclaredMethod("getService", String.class);
        IBinder binder = (IBinder) get.invoke(null, name);
        Method asInterface = Class.forName(type + "$Stub").getMethod("asInterface", IBinder.class);
        return (IInterface) asInterface.invoke(null, binder);
    }

    /**
     * Waits until the window manager has the display, up to a limit.
     *
     * DisplayManager hands a new display to the window manager on a handler, a beat after
     * createVirtualDisplay returns. An `am start --display` in that gap finds no such display and
     * falls back to the focused one, which on a folded Flip is the cover: the app flashes up there
     * until it is moved. Asking for the size is cheap and returns zero until the display exists.
     */
    public static boolean awaitWindowDisplay(int displayId, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        try {
            IInterface wm = service("window", "android.view.IWindowManager");
            Method size = wm.getClass().getMethod("getInitialDisplaySize", int.class, android.graphics.Point.class);
            while (System.currentTimeMillis() < end) {
                android.graphics.Point p = new android.graphics.Point();
                size.invoke(wm, displayId, p);
                if (p.x > 0 && p.y > 0) return true;
                Thread.sleep(20);
            }
        } catch (Exception e) {
            Log.e("awaitWindowDisplay", e);
            try { Thread.sleep(300); } catch (InterruptedException ignored) { }
        }
        return false;
    }

    private static boolean interactiveFailed;

    /**
     * Whether one display's power group is awake. The phone-wide isInteractive() is no use here:
     * the glasses are a group of their own and stay awake, so the phone never reads as asleep.
     */
    public static boolean isInteractive(int displayId) {
        try {
            IInterface power = service("power", "android.os.IPowerManager");
            return (Boolean) power.getClass().getMethod("isDisplayInteractive", int.class).invoke(power, displayId);
        } catch (Exception e) {
            if (!interactiveFailed) Log.e("isDisplayInteractive; using the display state", e);
            interactiveFailed = true;
            try {
                android.view.Display d = context().getSystemService(DisplayManager.class).getDisplay(displayId);
                return d == null || d.getState() == android.view.Display.STATE_ON;
            } catch (Exception e2) {
                return true;
            }
        }
    }

    /** Shows the lock screen without sleeping, as the screen-lock timeout does. */
    public static void lockNow() {
        try {
            IInterface wm = service("window", "android.view.IWindowManager");
            wm.getClass().getMethod("lockNow", android.os.Bundle.class).invoke(wm, (Object) null);
        } catch (Exception e) {
            Log.e("lockNow", e);
        }
    }

    /** Wake the phone, as the power button would. */
    public static void wakeUp() {
        try {
            IInterface power = service("power", "android.os.IPowerManager");
            power.getClass().getMethod("wakeUp", long.class, int.class, String.class, String.class)
                    .invoke(power, android.os.SystemClock.uptimeMillis(), 0, "superair:panels", ShellContext.PACKAGE);
        } catch (Exception e) {
            exec("input", "keyevent", "224");
        }
    }

    private static Method powerMode;
    private static Object displayControl;
    private static Method physicalToken;

    /**
     * Switches one physical screen's panel off or on without telling the power manager, the way
     * scrcpy's --turn-screen-off does: the phone stays awake, so nothing on the panels stops.
     */
    public static boolean setScreenPower(int displayId, boolean on) {
        try {
            Object info = Class.forName("android.hardware.display.DisplayManagerGlobal")
                    .getMethod("getInstance").invoke(null);
            Object di = info.getClass().getMethod("getDisplayInfo", int.class).invoke(info, displayId);
            Object address = field(di, "address");
            long physical = (Long) address.getClass().getMethod("getPhysicalDisplayId").invoke(address);
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            IBinder token;
            try {
                token = (IBinder) sc.getMethod("getPhysicalDisplayToken", long.class).invoke(null, physical);
            } catch (NoSuchMethodException e) {
                // Android 14 moved it to DisplayControl, in services.jar with its own native library.
                if (physicalToken == null) {
                    ClassLoader loader = (ClassLoader) Class.forName("com.android.internal.os.ClassLoaderFactory")
                            .getDeclaredMethod("createClassLoader", String.class, String.class, String.class,
                                    ClassLoader.class, int.class, boolean.class, String.class)
                            .invoke(null, "/system/framework/services.jar", null, null,
                                    ClassLoader.getSystemClassLoader(), 0, true, null);
                    Class<?> dc = loader.loadClass("com.android.server.display.DisplayControl");
                    Method load = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
                    load.setAccessible(true);
                    load.invoke(Runtime.getRuntime(), dc, "android_servers");
                    physicalToken = dc.getMethod("getPhysicalDisplayToken", long.class);
                    displayControl = dc;
                }
                token = (IBinder) physicalToken.invoke(null, physical);
            }
            if (powerMode == null) powerMode = sc.getMethod("setDisplayPowerMode", IBinder.class, int.class);
            powerMode.invoke(null, token, on ? 2 : 0); // POWER_MODE_NORMAL : POWER_MODE_OFF
            return true;
        } catch (Throwable t) {
            Log.e("could not switch display " + displayId + (on ? " on" : " off"), t);
            return false;
        }
    }

    /** DISPLAY_IME_POLICY_HIDE: a text field on a panel must never pop a keyboard on the cover. */
    public static void hideImeOn(int displayId) {
        if (Build.VERSION.SDK_INT < 31) return;
        try {
            IInterface wm = service("window", "android.view.IWindowManager");
            wm.getClass().getMethod("setDisplayImePolicy", int.class, int.class).invoke(wm, displayId, 2);
        } catch (Exception e) {
            Log.e("ime policy failed", e);
        }
    }

    private static IInterface taskManager() throws Exception {
        return service("activity_task", "android.app.IActivityTaskManager");
    }

    /** The root task holding a package, as {taskId, displayId}, or null. */
    public static int[] findTask(String pkg) {
        try {
            IInterface atm = taskManager();
            List<?> tasks = (List<?>) atm.getClass().getMethod("getAllRootTaskInfos").invoke(atm);
            for (Object t : tasks) {
                for (String f : new String[] {"topActivity", "baseActivity"}) {
                    Object c = field(t, f);
                    if (c instanceof android.content.ComponentName
                            && pkg.equals(((android.content.ComponentName) c).getPackageName())) {
                        return new int[] {(Integer) field(t, "taskId"), (Integer) field(t, "displayId")};
                    }
                }
            }
        } catch (Exception e) {
            Log.e("findTask", e);
        }
        return null;
    }

    /** Every root task as {taskId, displayId} with its packages (top and base), or empty. */
    public static List<Object[]> allTasks() {
        List<Object[]> out = new java.util.ArrayList<>();
        try {
            IInterface atm = taskManager();
            List<?> tasks = (List<?>) atm.getClass().getMethod("getAllRootTaskInfos").invoke(atm);
            for (Object t : tasks) {
                java.util.Set<String> pkgs = new java.util.HashSet<>();
                for (String f : new String[] {"topActivity", "baseActivity"}) {
                    Object c = field(t, f);
                    if (c instanceof android.content.ComponentName) pkgs.add(((android.content.ComponentName) c).getPackageName());
                }
                out.add(new Object[] {field(t, "taskId"), field(t, "displayId"), pkgs});
            }
        } catch (Exception e) {
            Log.e("allTasks", e);
        }
        return out;
    }

    private static Object field(Object o, String name) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
                // declared higher up
            }
        }
        return null;
    }

    /** Samsung sometimes accepts this and leaves the task in place, so callers verify with findTask. */
    public static void moveTask(int taskId, int displayId) {
        try {
            IInterface atm = taskManager();
            atm.getClass().getMethod("moveRootTaskToDisplay", int.class, int.class).invoke(atm, taskId, displayId);
        } catch (Exception e) {
            Log.e("move failed; trying the shell", e);
            Log.d(exec("cmd", "activity", "display", "move-root-task", String.valueOf(taskId), String.valueOf(displayId)));
        }
    }

    /** Bring a task forward on its own display, so a click on a panel gives it key focus. */
    public static void focusTask(int taskId) {
        try {
            IInterface atm = taskManager();
            // What `am task focus` itself calls.
            atm.getClass().getMethod("setFocusedTask", int.class).invoke(atm, taskId);
        } catch (Exception e) {
            Log.d("focus by binder failed, using the shell: " + e);
            exec("am", "task", "focus", String.valueOf(taskId));
        }
    }

    /**
     * Makes a display the focused one by focusing a real task on it: on the glasses the top task is
     * DeX's empty "Desk" container, and focusing that changes nothing. Prefers the given package.
     */
    public static void focusDisplay(int displayId, String prefer) {
        if (displayId < 0) return;
        Integer chosen = null;
        for (Object[] task : allTasks()) {
            if ((Integer) task[1] != displayId) continue;
            @SuppressWarnings("unchecked") java.util.Set<String> pkgs = (java.util.Set<String>) task[2];
            if (pkgs.isEmpty()) continue;
            if (chosen == null || (prefer != null && pkgs.contains(prefer))) chosen = (Integer) task[0];
            if (prefer != null && pkgs.contains(prefer)) break;
        }
        if (chosen == null && displayId > 1) {
            // No app on the glasses: any other display off the phone will do, such as a panel.
            for (Object[] task : allTasks()) {
                if ((Integer) task[1] > 1 && !((java.util.Set<?>) task[2]).isEmpty()) { chosen = (Integer) task[0]; break; }
            }
        }
        if (chosen != null) focusTask(chosen);
    }

    /** Root tasks on a display, or null when the platform does not expose the call. */
    public static List<?> tasksOn(int displayId) {
        try {
            IInterface atm = taskManager();
            Object result = atm.getClass().getMethod("getAllRootTaskInfosOnDisplay", int.class).invoke(atm, displayId);
            return result == null ? Collections.emptyList() : (List<?>) result;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Holds a screen wake lock. Virtual displays share the phone's power group, so without this they
     * follow it into doze at 30 fps — and on a folded Flip the phone's own screen is off nearly all
     * the time. Measured in FarGalaxy's docs/MEASUREMENTS.md.
     */
    public static final class KeepAwake {
        private static final int SCREEN_DIM_WAKE_LOCK = 6;
        private static final int SCREEN_BRIGHT_WAKE_LOCK = 10;
        private static final int ACQUIRE_CAUSES_WAKEUP = 0x10000000;
        private final String tag;
        private final int displayId;
        private IBinder lock;

        public KeepAwake() {
            this("superair:glasses", -1);
        }

        /** A lock on one display's power group, so a panel stays awake while the phone sleeps. */
        public KeepAwake(String tag, int displayId) {
            this.tag = tag;
            this.displayId = displayId;
        }

        public synchronized boolean held() {
            return lock != null;
        }

        public synchronized void hold() {
            if (lock != null) return;
            try {
                IInterface power = service("power", "android.os.IPowerManager");
                Method acquire = null;
                for (Method m : Class.forName("android.os.IPowerManager").getMethods()) {
                    if (m.getName().equals("acquireWakeLock")) acquire = m;
                }
                if (acquire == null) throw new NoSuchMethodException("acquireWakeLock");
                // The signature changes between Android versions, so fill it by parameter type.
                IBinder token = new android.os.Binder();
                Class<?>[] types = acquire.getParameterTypes();
                Object[] values = new Object[types.length];
                boolean flagsSet = false;
                boolean displaySet = false;
                int strings = 0;
                for (int i = 0; i < types.length; i++) {
                    if (types[i] == IBinder.class) values[i] = token;
                    else if (types[i] == int.class && !flagsSet) {
                        values[i] = (displayId >= 0 ? SCREEN_BRIGHT_WAKE_LOCK : SCREEN_DIM_WAKE_LOCK)
                                | ACQUIRE_CAUSES_WAKEUP;
                        flagsSet = true;
                    } else if (types[i] == int.class && !displaySet) {
                        // The int after the flags is the display the lock keeps awake.
                        values[i] = displayId;
                        displaySet = true;
                    } else if (types[i] == int.class) values[i] = -1;
                    else if (types[i] == String.class) values[i] = strings++ == 1 ? ShellContext.PACKAGE : tag;
                }
                acquire.invoke(power, values);
                lock = token;
                Log.d("holding " + (displayId >= 0 ? "display " + displayId : "the phone") + " awake");
            } catch (Throwable t) {
                Log.e("could not hold a wake lock", t);
            }
        }

        public synchronized void release() {
            if (lock == null) return;
            try {
                IInterface power = service("power", "android.os.IPowerManager");
                Class.forName("android.os.IPowerManager").getMethod("releaseWakeLock", IBinder.class, int.class)
                        .invoke(power, lock, 0);
            } catch (Throwable t) {
                Log.e("could not release the wake lock", t);
            }
            lock = null;
        }
    }

    public static String exec(String... cmd) {
        try {
            java.lang.Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            p.waitFor();
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    static final class ShellContext extends ContextWrapper {
        static final String PACKAGE = "com.android.shell";

        ShellContext(Context base) {
            super(base);
        }

        @Override public String getPackageName() { return PACKAGE; }
        @Override public String getOpPackageName() { return PACKAGE; }
        @Override public Context getApplicationContext() { return this; }

        @Override public AttributionSource getAttributionSource() {
            return new AttributionSource.Builder(Process.SHELL_UID).setPackageName(PACKAGE).build();
        }

        @SuppressWarnings("unused")
        public int getDeviceId() { return 0; }
    }
}
