package com.yaz.contacts.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.yaz.contacts.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Contacts' loading mark, the launcher icon in motion: the person raises
 * their right hand from the bust and waves twice, the motion strokes
 * following the hand, then lowers it. The disc is the icon's: a grey body
 * multiplied by the theme's accent (its deep tone by night too), its white
 * rim over it, the person on top. The wave is drawn frame by frame from
 * sprite sheets (Modules/icons/contacts: hand.py, wave_export.py).
 *
 * [progress] from 0 to 1 raises the hand as far as a gesture has gone.
 * While [running] it waves on its own.
 */
@Composable
fun LoadingMark(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    running: Boolean = true,
    progress: Float = 0f
) {
    val body = ImageBitmap.imageResource(R.drawable.people_mark_body)
    val shine = ImageBitmap.imageResource(R.drawable.people_mark_shine)
    val person = ImageBitmap.imageResource(R.drawable.people_mark_person)
    val hand = ImageBitmap.imageResource(R.drawable.people_wave_hand)
    val shadow = ImageBitmap.imageResource(R.drawable.people_wave_shadow)
    val strokes = ImageBitmap.imageResource(R.drawable.people_wave_strokes)
    val colors = MaterialTheme.colorScheme
    val night = colors.background.luminance() < 0.5f
    // The disc keeps the icon's deep tone; by night primary is a pale one.
    val disc = if (night) colors.inversePrimary else colors.primary
    val tint = remember(disc) { ColorFilter.tint(disc, BlendMode.Modulate) }
    val outside = remember(colors.primary) { ColorFilter.tint(colors.primary) }
    val onDisc = remember { ColorFilter.tint(IVORY) }
    val shade = remember { ColorFilter.tint(Color.Black.copy(alpha = 0.42f)) }

    val transition = rememberInfiniteTransition(label = "waving")
    val clock by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(LOOP_MILLIS, easing = LinearEasing), RepeatMode.Restart),
        label = "wave"
    )

    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f * DISC
        val state = if (running) wave(clock) else Wave(progress.coerceIn(0f, 1f), 0, false)
        val breath = if (running) 1f + 0.02f * sin(2f * PI.toFloat() * clock) else 1f
        scale(breath) {
            layer(body, r, tint)
            layer(shine, r, null)
            val unit = 2f * r / 512f
            val hs = 2f * r * HAND * state.lift
            val left = center.x - r + ANCHOR_X * unit - WRIST_X * hs / 512f
            val top = center.y - r + ANCHOR_Y * unit - WRIST_Y * hs / 512f
            if (state.lift > 0.01f) sprite(shadow, state.frame, SHEET / 2, left + r * 0.03f, top + r * 0.05f, hs, shade)
            layer(person, r, null)
            if (state.lift > 0.01f) {
                sprite(hand, state.frame, SHEET, left, top, hs, null)
                if (state.waving) {
                    // The strokes follow the hand: ivory on the disc, the accent beyond it.
                    val gs = hs * STROKES
                    val sx = left - (gs - hs) / 2f
                    val sy = top - (gs - hs) / 2f
                    val round = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, r)) }
                    clipPath(round) { sprite(strokes, state.frame, SHEET, sx, sy, gs, onDisc) }
                    clipPath(round, ClipOp.Difference) { sprite(strokes, state.frame, SHEET, sx, sy, gs, outside) }
                }
            }
        }
    }
}

private class Wave(val lift: Float, val frame: Int, val waving: Boolean)

/** Where the loop is at [t] (0..1): rises, waves as the example twice, goes down, rests. */
private fun wave(t: Float): Wave {
    val ms = t * LOOP_MILLIS
    val waving = CYCLES * FRAMES * FRAME_MILLIS
    return when {
        ms < IN_MILLIS -> Wave(pop(ms / IN_MILLIS), 0, false)
        ms < IN_MILLIS + waving -> Wave(1f, ((ms - IN_MILLIS) / FRAME_MILLIS).toInt() % FRAMES, true)
        ms < IN_MILLIS + waving + OUT_MILLIS -> Wave(1f - pop((ms - IN_MILLIS - waving) / OUT_MILLIS), 0, false)
        else -> Wave(0f, 0, false)
    }
}

/** 0 → 1 with a small overshoot, as the app's entrances pop. */
private fun pop(u: Float): Float {
    val v = u.coerceIn(0f, 1f)
    if (v >= 1f) return 1f
    return 1f - cos(v * PI.toFloat() / 2f).pow(3) + 0.2f * sin(v * PI.toFloat()) * (1f - v)
}

private fun DrawScope.layer(image: ImageBitmap, r: Float, filter: ColorFilter?) {
    val side = (r * 2f).toInt()
    drawImage(
        image,
        dstOffset = IntOffset((center.x - r).toInt(), (center.y - r).toInt()),
        dstSize = IntSize(side, side),
        colorFilter = filter,
        filterQuality = FilterQuality.High
    )
}

/** Frame [frame] of a sheet of [cell] px squares, COLUMNS to a row, drawn at [x], [y], [side] wide. */
private fun DrawScope.sprite(sheet: ImageBitmap, frame: Int, cell: Int, x: Float, y: Float, side: Float, filter: ColorFilter?) {
    val s = side.roundToInt().coerceAtLeast(1)
    drawImage(
        sheet,
        srcOffset = IntOffset((frame % COLUMNS) * cell, (frame / COLUMNS) * cell),
        srcSize = IntSize(cell, cell),
        dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
        dstSize = IntSize(s, s),
        colorFilter = filter,
        filterQuality = FilterQuality.High
    )
}

/** The disc's share of the mark, leaving room for its light. */
private const val DISC = 0.70f
/** The hand's frame against the disc, its wrist in that frame, where the wrist sits on the bust (512 px units). */
private const val HAND = 0.34f
private const val WRIST_X = 309f
private const val WRIST_Y = 476f
private const val ANCHOR_X = 150f
private const val ANCHOR_Y = 378f
/** The strokes a little further out than the example draws them, so they read small. */
private const val STROKES = 1.22f
private const val SHEET = 192
private const val COLUMNS = 8
private const val FRAMES = 46
private const val FRAME_MILLIS = 30f
private const val CYCLES = 2
private const val IN_MILLIS = 250f
private const val OUT_MILLIS = 250f
private const val LOOP_MILLIS = 3500
private val IVORY = Color(0xFFE5E0DA)
