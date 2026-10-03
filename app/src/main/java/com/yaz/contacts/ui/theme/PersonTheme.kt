package com.yaz.contacts.ui.theme

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.yaz.contacts.ui.glass.LocalGlass
import com.yaz.contacts.ui.glass.glassGround
import com.yaz.contacts.ui.glass.rememberGlassLook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A person's page in their own colours, as Android takes a palette from the
 * wallpaper: the main colour of their photo (or the colour chosen for them)
 * becomes the page's accent, its ground and its glass, in light and dark.
 */
object PersonColours {

    /**
     * The photo's main colour: the hue most present among its lively pixels
     * (greys, near whites and near blacks set aside), averaged; null for a
     * photo without a colour of its own (black and white, a grey wall).
     */
    suspend fun of(context: Context, photo: String?): Color? = withContext(Dispatchers.IO) {
        photo ?: return@withContext null
        runCatching {
            val uri = Uri.parse(photo)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 64 && bounds.outHeight / (sample * 2) >= 64) sample *= 2
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@runCatching null
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
            bitmap.recycle()
            // Twelve hue buckets, each pixel counting by how lively it is.
            val weight = DoubleArray(12)
            val sums = Array(12) { DoubleArray(3) }
            val hsv = FloatArray(3)
            for (p in pixels) {
                android.graphics.Color.colorToHSV(p, hsv)
                val s = hsv[1]
                val v = hsv[2]
                if (s < 0.22f || v < 0.18f || v > 0.98f) continue
                val bucket = ((hsv[0] / 30f).toInt()).coerceIn(0, 11)
                val k = (s * v).toDouble()
                weight[bucket] += k
                sums[bucket][0] += android.graphics.Color.red(p) * k
                sums[bucket][1] += android.graphics.Color.green(p) * k
                sums[bucket][2] += android.graphics.Color.blue(p) * k
            }
            val best = weight.indices.maxByOrNull { weight[it] } ?: return@runCatching null
            // Too little colour in the whole picture: none of its own.
            if (weight[best] < pixels.size * 0.04) return@runCatching null
            Color(
                red = (sums[best][0] / weight[best] / 255.0).toFloat().coerceIn(0f, 1f),
                green = (sums[best][1] / weight[best] / 255.0).toFloat().coerceIn(0f, 1f),
                blue = (sums[best][2] / weight[best] / 255.0).toFloat().coerceIn(0f, 1f)
            )
        }.getOrNull()
    }

    /** [base] with the person's colour [seed] as its accent and its light, kept readable. */
    fun scheme(seed: Color, base: ColorScheme): ColorScheme {
        val dark = base.background.luminance() < 0.5f
        val hsl = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(seed.toArgb(), hsl)
        val hue = hsl[0]
        val sat = hsl[1].coerceIn(0.35f, 0.85f)
        fun tone(h: Float, s: Float, l: Float) = Color(androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf((h + 360f) % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))))
        val pureBlack = base.background == Color.Black
        return if (dark) base.copy(
            primary = tone(hue, sat, 0.78f),
            onPrimary = tone(hue, sat, 0.18f),
            primaryContainer = tone(hue, sat * 0.8f, 0.30f),
            onPrimaryContainer = tone(hue, sat, 0.90f),
            secondary = tone(hue, sat * 0.45f, 0.76f),
            secondaryContainer = tone(hue, sat * 0.4f, 0.27f),
            onSecondaryContainer = tone(hue, sat * 0.4f, 0.90f),
            tertiary = tone(hue + 50f, sat * 0.7f, 0.78f),
            tertiaryContainer = tone(hue + 50f, sat * 0.6f, 0.30f),
            background = if (pureBlack) base.background else tone(hue, 0.18f, 0.08f),
            surface = if (pureBlack) base.surface else tone(hue, 0.18f, 0.08f),
            surfaceContainerLow = tone(hue, 0.16f, 0.11f),
            surfaceContainer = tone(hue, 0.16f, 0.13f),
            surfaceContainerHigh = tone(hue, 0.15f, 0.17f),
            surfaceContainerHighest = tone(hue, 0.14f, 0.21f),
            surfaceVariant = tone(hue, 0.14f, 0.24f)
        ) else base.copy(
            primary = tone(hue, sat, 0.38f),
            onPrimary = Color.White,
            primaryContainer = tone(hue, sat, 0.88f),
            onPrimaryContainer = tone(hue, sat, 0.14f),
            secondary = tone(hue, sat * 0.4f, 0.40f),
            secondaryContainer = tone(hue, sat * 0.5f, 0.88f),
            onSecondaryContainer = tone(hue, sat * 0.5f, 0.15f),
            tertiary = tone(hue + 50f, sat * 0.6f, 0.40f),
            tertiaryContainer = tone(hue + 50f, sat * 0.6f, 0.88f),
            background = tone(hue, 0.40f, 0.97f),
            surface = tone(hue, 0.40f, 0.97f),
            surfaceContainerLow = tone(hue, 0.35f, 0.95f),
            surfaceContainer = tone(hue, 0.32f, 0.93f),
            surfaceContainerHigh = tone(hue, 0.30f, 0.91f),
            surfaceContainerHighest = tone(hue, 0.28f, 0.89f),
            surfaceVariant = tone(hue, 0.25f, 0.88f)
        )
    }
}

/**
 * [content] in the person's colours ([seed]), ground and glass included;
 * as is when there is none.
 */
@Composable
fun PersonTheme(seed: Color?, content: @Composable () -> Unit) {
    if (seed == null) {
        content()
        return
    }
    val base = MaterialTheme.colorScheme
    val scheme = remember(seed, base) { PersonColours.scheme(seed, base) }
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        val look = rememberGlassLook(scheme, LocalGlass.current != null)
        CompositionLocalProvider(LocalGlass provides look, LocalContentColor provides scheme.onBackground) {
            Box(Modifier.fillMaxSize().glassGround(look, scheme.background)) { content() }
        }
    }
}
