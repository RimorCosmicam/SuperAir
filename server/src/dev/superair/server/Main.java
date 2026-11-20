package dev.superair.server;

import android.os.Looper;

/**
 * The shell-uid host.
 *
 * Started by the app over the phone's own wireless ADB, with this APK's installed path as the
 * classpath — so there is one artifact to install and no writable copy of SuperAir's code sitting
 * in /data/local/tmp where anything on the device could read it.
 */
public final class Main {
    public static void main(String[] args) {
        try {
            Looper.prepareMainLooper();
            Sys.init();
            keepUsbSteady();
            Host host = new Host();

            // Freeform is what lets an app that calls itself non-resizable fill a grid cell
            // anyway. It is a global setting rather than something we can ask for per display.
            Sys.exec("settings", "put", "global", "enable_freeform_support", "1");
            Sys.exec("settings", "put", "global", "force_resizable_activities", "1");

            Thread handshake = new Thread(() -> {
                // The app may not be up yet, and will be killed and restarted over the life of this
                // process. Acquiring its provider starts it, so this both delivers the binder and
                // brings the glasses back after the app has gone.
                while (!Thread.currentThread().isInterrupted()) {
                    if (!Bridge.publish(host)) {
                        sleep(2000);
                        continue;
                    }
                    sleep(5000);
                }
            }, "handshake");
            handshake.setDaemon(true);
            handshake.start();

            Log.d("ready on display " + host.glassesDisplay());
            Looper.loop();
        } catch (Throwable t) {
            Log.e("the host could not start", t);
            System.exit(1);
        }
    }

    /**
     * Stops the USB mode from changing when the phone locks and unlocks, which would kill this host.
     *
     * With "File transfer" as the default USB configuration, One UI turns USB data off at every
     * lock and back on at every unlock, even with the glasses in the port. Each change restarts
     * adbd, and init kills everything in adbd's process group with it: this host, started over
     * wireless debugging, and every app display it owns. A video playing on the glasses vanished
     * at unlock while its sound carried on. See docs/MEASUREMENTS.md, section 12.
     *
     * So USB stays in charging mode. Switching to it restarts adbd one last time, so it is done
     * here, before any display exists; the app starts the host again, and this time there is
     * nothing to change.
     */
    private static void keepUsbSteady() {
        String unlocked = Sys.exec("dumpsys", "usb");
        boolean switchesOnUnlock = false;
        for (String line : unlocked.split("\n")) {
            String l = line.trim().toLowerCase();
            if (l.startsWith("screen_unlocked_functions=") && !l.endsWith("=") && !l.endsWith("none")) {
                switchesOnUnlock = true;
            }
        }
        if (switchesOnUnlock) {
            Log.d("turning off the USB switch at unlock");
            Sys.exec("svc", "usb", "setScreenUnlockedFunctions", "");
        }
        String current = Sys.exec("svc", "usb", "getFunctions").trim().toLowerCase();
        if (current.matches(".*\\b(mtp|ptp|rndis|midi|ncm)\\b.*")) {
            Log.d("USB is in " + current + " mode; moving it to charging, which restarts adbd and this host");
            Sys.exec("svc", "usb", "setFunctions", "");
            sleep(8000);  // adbd's restart normally ends this process before the sleep does
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
