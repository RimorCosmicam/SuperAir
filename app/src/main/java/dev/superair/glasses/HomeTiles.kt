package dev.superair.glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap
import dev.superair.AppEntry

// The two things a Home slot can show, drawn the same on the glasses and on the cover.

private val roundIcons = HashMap<String, ImageBitmap?>()

/** The app's icon as a full circle: adaptive icons drawn full-bleed, old square ones on white. */
@Composable
fun RoundIcon(app: AppEntry?, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val icon = remember(app?.pkg) { app?.let { roundIcons.getOrPut(it.pkg) { roundBitmap(context, it.pkg) } } }
    if (icon != null) Image(icon, app?.label, modifier.size(size).clip(CircleShape))
    else Box(modifier.size(size).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)))
}

/** A folder: a translucent disc with its first four apps in it. */
@Composable
fun FolderIcon(folder: Home.Item.Folder, size: Dp, modifier: Modifier = Modifier) {
    val inner = size * 0.3f
    val gap = size * 0.05f
    Box(modifier.size(size).clip(CircleShape).background(Color.White.copy(alpha = 0.24f)), contentAlignment = Alignment.Center) {
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            for (row in folder.apps.take(4).chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (key in row) RoundIcon(Home.app(key), inner)
                    if (row.size == 1) Spacer(Modifier.size(inner))
                }
            }
        }
    }
}

private fun roundBitmap(context: Context, pkg: String): ImageBitmap? = runCatching {
    val pm = context.packageManager
    // The app's own icon resource, not the launcher's: Samsung hands out its icons already masked to
    // a squircle, which cannot then be made round. The raw resource is the adaptive icon itself.
    val info = pm.getApplicationInfo(pkg, 0)
    val drawable = runCatching {
        pm.getResourcesForApplication(info).getDrawableForDensity(info.icon, android.util.DisplayMetrics.DENSITY_XXXHIGH, null)
    }.getOrNull() ?: pm.getApplicationIcon(pkg)
    val size = 200
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    if (drawable is AdaptiveIconDrawable) {
        // The layers are 108 units with the middle 72 meant to show: draw them so that middle,
        // a little more, fills the circle.
        val bleed = (size * 0.2f).toInt()
        drawable.background?.let { it.setBounds(-bleed, -bleed, size + bleed, size + bleed); it.draw(canvas) }
        drawable.foreground?.let { it.setBounds(-bleed, -bleed, size + bleed, size + bleed); it.draw(canvas) }
    } else {
        // An old square icon: on a white disc, as Android XR shows them.
        canvas.drawColor(android.graphics.Color.WHITE)
        val inner = drawable.toBitmap(size * 3 / 4, size * 3 / 4)
        canvas.drawBitmap(inner, size * 0.125f, size * 0.125f, null)
    }
    bitmap.asImageBitmap()
}.getOrNull()
