package dev.superair.server;

/** stdout, which deploy.sh redirects to a log file the app can also read back over adb. */
public final class Log {
    private Log() {}

    public static void d(String message) {
        System.out.println("[superair] " + message);
    }

    public static void e(String message, Throwable cause) {
        System.out.println("[superair] " + message + ": " + cause);
        cause.printStackTrace(System.out);
    }
}
