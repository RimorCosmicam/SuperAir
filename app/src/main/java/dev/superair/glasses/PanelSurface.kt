package dev.superair.glasses

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.superair.Air
import dev.superair.PanelState

/**
 * One app's pixels, on the glasses.
 *
 * The surface this view owns becomes the output of a trusted virtual display created by the shell
 * host, so the app draws straight into it and SurfaceFlinger composites the result. Kamui Desktop
 * put an H.264 encoder here because its client was a browser on another machine; both ends are the
 * same phone now, so there is nothing in the path at all.
 *
 * Every geometry change arrives through this view rather than being sent alongside it: the scene
 * lays the view out in its grid cell, the surface reports the size it actually got, and that size
 * becomes the display's. An app is therefore always laid out at exactly the pixels it occupies.
 */
@Composable
fun PanelSurface(panel: PanelState, dpi: Int, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                // The panels sit under the chrome, which is drawn into the window above them.
                setZOrderMediaOverlay(false)
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        Air.surfaceReady(panel, holder.surface, width, height, dpi)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        Air.surfaceGone(panel)
                    }
                })
            }
        },
        onRelease = { Air.surfaceGone(panel) },
    )
}
