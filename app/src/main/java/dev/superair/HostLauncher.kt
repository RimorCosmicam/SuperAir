package dev.superair

import android.content.Context
import android.util.Log
import dev.superair.adb.AdbClient
import dev.superair.adb.AdbMdns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Starts the shell host over the phone's own wireless ADB.
 *
 * Only uid 2000 may create a trusted display, and only a trusted display will hold another app's
 * windows. That one permission is what the whole product rests on, which is why pairing is the
 * first screen and the app says so plainly. It pairs on-device: no computer, no cable, no Shizuku.
 *
 * Adapted from Kamui Desktop, where the timing of each step was measured rather than guessed.
 */
class HostLauncher private constructor(private val context: Context) {
    val mdns = AdbMdns(context).also { it.start() }
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("host", Context.MODE_PRIVATE)

    private val _status = MutableStateFlow("Starting…")
    val status = _status.asStateFlow()

    /** Whether the glasses should be running. Stop clears it, so a stopped desk stays stopped. */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) = prefs.edit().putBoolean("enabled", value).apply()

    var paired: Boolean
        get() = prefs.getBoolean("paired", false)
        private set(value) = prefs.edit().putBoolean("paired", value).apply()

    /** The host is alive exactly when it has handed us a working binder. */
    fun alive(): Boolean = Air.host?.asBinder()?.isBinderAlive == true

    fun report(text: String) { _status.value = text }

    suspend fun pair(port: Int, code: String): Result<Unit> = lock.withLock {
        AdbClient(context).pairWith(mdns.host.value, port, code).onSuccess { paired = true }
    }

    /**
     * adbd can die at any point in this and leave a read blocked forever, which no coroutine
     * timeout can interrupt. So each attempt owns its connection, and past the deadline that
     * connection is closed under it and the next attempt starts clean.
     */
    suspend fun launch(): Boolean = lock.withLock {
        val client = AdbClient(context)
        val attempt = scope.async { launchOver(client) }
        val started = withTimeoutOrNull(LAUNCH_MILLIS) { attempt.await() }
        if (started == null) {
            Log.w(TAG, "launch timed out, abandoning its connection")
            attempt.cancel()
            scope.launch { client.release() }
        }
        started ?: false
    }

    private suspend fun launchOver(client: AdbClient): Boolean {
        val hosts = listOf(AdbMdns.LOOPBACK, mdns.host.value).distinct()
        val ports = listOfNotNull(mdns.connectPort.value, LEGACY_PORT).distinct()
        val connected = hosts.any { host ->
            ports.any { port ->
                client.connectTo(host, port)
                    .onFailure { Log.i(TAG, "no adb on $host:$port: ${it.message}") }.isSuccess
            }
        }
        if (!connected) return false
        paired = true
        val apk = context.applicationInfo.sourceDir
        // A subshell with setsid, and the shell kept alive two seconds: adbd kills what a shell
        // started if the shell exits first. Measured in FarGalaxy's HostController.
        val command = "mkdir -p $DIR && chmod 700 $DIR && export CLASSPATH='$apk' && " +
            "(setsid nohup app_process / --nice-name=superair-host dev.superair.server.Main " +
            "> $DIR/log 2>&1 < /dev/null &) ; sleep 2"
        val stream = client.openShell(command)
        Log.i(TAG, "launch sent")
        delay(SHELL_MILLIS)
        runCatching { stream.close() }
        client.release()
        return true
    }

    fun stop() {
        runCatching { Air.host?.shutdown() }
    }

    companion object {
        const val RUNNING = "On the glasses"
        const val FAILED = "The host did not start"
        const val WAITING = "Waiting for wireless debugging…"
        private const val TAG = "SuperAir"
        private const val DIR = "/data/local/tmp/superair"
        private const val LEGACY_PORT = 5555
        private const val SHELL_MILLIS = 3000L
        private const val LAUNCH_MILLIS = 45_000L

        @Volatile private var instance: HostLauncher? = null
        fun of(context: Context): HostLauncher = instance ?: synchronized(this) {
            instance ?: HostLauncher(context.applicationContext).also { instance = it }
        }
    }
}
