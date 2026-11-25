package dev.superair

import android.content.Intent
import android.provider.Settings
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.superair.cover.CoverScreen
import kotlinx.coroutines.delay

/**
 * The only activity, and it runs on the cover screen, because the phone is folded.
 *
 * Until wireless debugging is paired there is nothing SuperAir can do at all: only the shell user
 * may create a display that will hold another app's windows, and that one permission is what the
 * whole thing rests on. So pairing is the first screen and it says so plainly.
 */
class MainActivity : ComponentActivity() {
    /**
     * While the power button has the cover dark, every touch is dropped: the phone is in a pocket.
     * Only the power button brings the cover back, onto the lock screen.
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (Air.coverDark) return true
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startService()
        setContent {
            val launcher = HostLauncher.of(this)
            // Read live, not once: both are granted in Settings while this screen sits behind it,
            // and coming back must move straight on without restarting the app.
            var paired by remember { mutableStateOf(launcher.paired) }
            var granted by remember { mutableStateOf(PairingService.granted(this)) }
            var on by remember { mutableStateOf(launcher.enabled) }
            LaunchedEffect(Unit) {
                while (true) {
                    paired = launcher.paired || Air.host != null
                    granted = PairingService.granted(this@MainActivity)
                    on = launcher.enabled
                    delay(700)
                }
            }
            when {
                !on -> TurnedOff { launcher.turnOn(); on = true; startService() }
                paired -> CoverScreen()
                else -> Pairing(launcher, granted)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Once, for the weather on Home. Declining just leaves the clock without it.
        val prefs = getSharedPreferences("home", MODE_PRIVATE)
        if (!prefs.getBoolean("askedLocation", false) &&
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("askedLocation", true).apply()
            requestPermissions(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION), 7)
        }
        startService()
        dev.superair.glasses.GlassesPresenter.refresh()
    }

    /**
     * Only with the glasses in: the service holds wake locks and would only stop itself again.
     * Pairing is not required here, since the service is what notices a fresh pairing.
     */
    private fun startService() {
        if (HostLauncher.of(this).enabled && dev.superair.glasses.GlassesPresenter.wanted(this)) {
            startForegroundService(Intent(this, AirService::class.java))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7) dev.superair.glasses.Weather.refresh(force = true)
    }
}

@Composable
private fun Pairing(launcher: HostLauncher, granted: Boolean) {
    var note by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text("SUPERAIR", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black)
        Text(
            "Only the shell user may make a display that holds another app's windows, so SuperAir " +
                "needs wireless debugging paired once. Nothing else is asked for, and it pairs on " +
                "the phone: no computer, no cable.",
            color = Color.White.copy(alpha = 0.58f),
            fontSize = 11.sp,
        )

        Step(1, "Turn SuperAir on in Accessibility", done = granted)
        Button(if (granted) "ACCESSIBILITY \u2713" else "OPEN ACCESSIBILITY", Modifier.fillMaxWidth()) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { note = "Could not open Accessibility settings" }
        }

        Step(2, "Open Wireless debugging and tap \u201cPair device with pairing code\u201d", done = false)
        Button("PAIR WITH FLOATING BAR", Modifier.fillMaxWidth(), enabled = granted) {
            // The bar goes up first, on the display this screen is on, and then Settings opens
            // underneath it. The other way round and the bar lands on the wrong display.
            val display = context.display?.displayId ?: 0
            note = PairingBar.show(display)
            if (note == null) {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure { note = "Could not open Developer options" }
            }
        }

        note?.let { Text(it, color = Color(0xFFEF4444), fontSize = 11.sp) }
        Text(
            "The bar floats above Settings so the six digits stay on screen while you type them.",
            color = Color.White.copy(alpha = 0.34f),
            fontSize = 10.sp,
        )
    }
}

/** SuperAir was turned off in Settings. It stays off, glasses or not, until turned on here. */
@Composable
private fun TurnedOff(turnOn: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterVertically),
    ) {
        Text("SUPERAIR IS OFF", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black)
        Text(
            "Nothing is running and plugging in the glasses starts nothing. Turn it on to use " +
                "the glasses again.",
            color = Color.White.copy(alpha = 0.58f),
            fontSize = 11.sp,
        )
        Button("TURN ON", Modifier.fillMaxWidth(), onClick = turnOn)
    }
}

@Composable
private fun Step(number: Int, text: String, done: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            "$number",
            color = if (done) Color(0xFFD8A628) else Color.White.copy(alpha = 0.58f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
        )
        Text(text, color = Color.White.copy(alpha = if (done) 0.9f else 0.72f), fontSize = 11.sp)
    }
}

@Composable
private fun Button(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(11.dp))
            .background(Color.White.copy(alpha = if (enabled) 0.16f else 0.05f))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Color.White.copy(alpha = if (enabled) 1f else 0.3f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
        )
    }
}
