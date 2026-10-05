package org.circa.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.wear.compose.material3.MaterialTheme
import org.circa.launcher.data.AppLoader
import org.circa.launcher.model.AppEntry

/**
 * A launcher icon rasterised for a circle that it must fill edge to edge.
 *
 * Adaptive icons are 108 dp canvases of which the central 72 dp (2/3) is what any mask shows; the
 * two layers are drawn *unmasked* into [bitmap] (the drawable's own `draw` would clip them to the
 * system's squircle and leave the corners empty) and the composable shows that central 2/3 through
 * a circle, so the background colour runs right to the rim - no white "sticker" disc behind the
 * art, as the v1.3 rows had. A legacy icon has no layers: it is scaled to fill the circle.
 */
class IconArt(val bitmap: Bitmap, val adaptive: Boolean)

private const val ADAPTIVE_PX = 216
private const val LEGACY_PX = 192

/** Rasterize a [Drawable] (launcher icons) so it can be shown by Compose [Image]. */
fun Drawable.toIconArt(): IconArt {
    if (this is AdaptiveIconDrawable) {
        val bitmap = Bitmap.createBitmap(ADAPTIVE_PX, ADAPTIVE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        for (layer in listOfNotNull(background, foreground)) {
            layer.setBounds(0, 0, ADAPTIVE_PX, ADAPTIVE_PX)
            layer.draw(canvas)
        }
        return IconArt(bitmap, adaptive = true)
    }
    val existing = (this as? BitmapDrawable)?.bitmap
    if (existing != null) return IconArt(existing, adaptive = false)
    val width = if (intrinsicWidth > 0) intrinsicWidth else LEGACY_PX
    val height = if (intrinsicHeight > 0) intrinsicHeight else LEGACY_PX
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return IconArt(bitmap, adaptive = false)
}

/** [art] filling a circle of [size]: adaptive icons show their central 72/108 through the circle. */
@Composable
fun CircleIcon(art: IconArt?, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(
                bitmap = art.bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.requiredSize(if (art.adaptive) size * (108f / 72f) else size),
            )
        }
    }
}

/** The launcher activity's icon, full-bleed in a circle of [size]. */
@Composable
fun AppIcon(entry: AppEntry, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val art = remember(entry.packageName, entry.className) {
        AppLoader.loadIcon(context, entry)?.toIconArt()
    }
    CircleIcon(art, size, modifier)
}

/** A package's application icon (the notification cards' app badge), full-bleed in a circle. */
@Composable
fun PackageIcon(packageName: String, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val art = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName) }
            .getOrNull()?.toIconArt()
    }
    CircleIcon(art, size, modifier)
}
