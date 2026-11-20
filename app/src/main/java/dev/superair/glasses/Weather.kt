package dev.superair.glasses

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The weather under the clock, from Open-Meteo as in TheHud: no key, and nothing sent but the
 * phone's last known coarse position, rounded to two decimals. No permission, no weather.
 */
object Weather {
    data class Now(val temperature: Int, val code: Int, val day: Boolean)

    var now by mutableStateOf<Now?>(null); private set
    private var app: Context? = null
    private var fetchedAt = 0L
    private val main = Handler(Looper.getMainLooper())

    fun start(context: Context) {
        if (app != null) return
        app = context.applicationContext
        refresh()
        main.postDelayed(object : Runnable {
            override fun run() { refresh(); main.postDelayed(this, 10 * 60_000L) }
        }, 10 * 60_000L)
    }

    @SuppressLint("MissingPermission")
    fun refresh(force: Boolean = false) {
        val context = app ?: return
        if (!force && System.currentTimeMillis() - fetchedAt < 25 * 60_000L && now != null) return
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(LocationManager::class.java)
        val location = manager.getProviders(true)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time } ?: return
        val unit = if (Home.fahrenheit) "&temperature_unit=fahrenheit" else ""
        val url = "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f".format(Locale.US, location.latitude, location.longitude) +
            "&current=temperature_2m,weather_code,is_day$unit"
        Thread {
            runCatching {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", "SuperAir")
                connection.connectTimeout = 12_000
                connection.readTimeout = 12_000
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val current = JSONObject(body).getJSONObject("current")
                val result = Now(current.getDouble("temperature_2m").roundToInt(), current.getInt("weather_code"), current.optInt("is_day", 1) == 1)
                main.post { now = result; fetchedAt = System.currentTimeMillis() }
            }
        }.start()
    }

    /** WMO weather codes, in a word. */
    fun label(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        in 51..57 -> "Drizzle"
        in 61..67, in 80..82 -> "Rain"
        in 71..77, 85, 86 -> "Snow"
        in 95..99 -> "Storm"
        else -> ""
    }
}
