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

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
