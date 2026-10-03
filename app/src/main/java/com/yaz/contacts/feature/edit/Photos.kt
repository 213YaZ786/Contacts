package com.yaz.contacts.feature.edit

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.yaz.contacts.ui.component.ZoneAlertDialog
import com.yaz.contacts.ui.component.rememberHaptics
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The photo framed by the user: pinch to zoom, drag to place it in the
 * circle; what is inside becomes the contact's square photo.
 */
@Composable
fun CropDialog(bitmap: Bitmap, onDismiss: () -> Unit, onDone: (Bitmap) -> Unit) {
    val haptics = rememberHaptics()
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    ZoneAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Frame the photo") },
        text = {
            Box(
                Modifier
                    .widthIn(max = 320.dp)
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .onSizeChanged { box = it }
                    .pointerInput(bitmap) {
                        detectTransformGestures { _, move, gesture, _ ->
                            zoom = (zoom * gesture).coerceIn(1f, 6f)
                            // Never a gap between the picture and the circle's edge.
                            val fit = max(box.width.toFloat() / image.width, box.height.toFloat() / image.height) * zoom
                            val maxX = ((image.width * fit - box.width) / 2f).coerceAtLeast(0f)
                            val maxY = ((image.height * fit - box.height) / 2f).coerceAtLeast(0f)
                            pan = Offset((pan.x + move.x).coerceIn(-maxX, maxX), (pan.y + move.y).coerceIn(-maxY, maxY))
                        }
                    }
            ) {
                Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
                    val fit = max(size.width / image.width, size.height / image.height) * zoom
                    val w = image.width * fit
                    val h = image.height * fit
                    val left = (size.width - w) / 2f + pan.x
                    val top = (size.height - h) / 2f + pan.y
                    drawImage(image, srcOffset = IntOffset.Zero, srcSize = IntSize(image.width, image.height), dstOffset = IntOffset(left.roundToInt(), top.roundToInt()), dstSize = IntSize(w.roundToInt(), h.roundToInt()))
                    val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset.Zero, Size(size.width, size.height))) }
                    clipPath(circle, clipOp = ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.55f)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                haptics.done()
                onDone(crop(bitmap, zoom, pan, box))
            }) { Text("Use") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** The square the user framed, at most 720 pixels a side, as Android keeps contact photos. */
private fun crop(bitmap: Bitmap, zoom: Float, pan: Offset, box: IntSize): Bitmap {
    if (box.width == 0) return Bitmap.createScaledBitmap(bitmap, min(720, bitmap.width), min(720, bitmap.width), true)
    val fit = max(box.width.toFloat() / bitmap.width, box.height.toFloat() / bitmap.height) * zoom
    val side = box.width / fit
    val cx = bitmap.width / 2f - pan.x / fit
    val cy = bitmap.height / 2f - pan.y / fit
    val left = (cx - side / 2f).roundToInt().coerceIn(0, (bitmap.width - 1).coerceAtLeast(0))
    val top = (cy - side / 2f).roundToInt().coerceIn(0, (bitmap.height - 1).coerceAtLeast(0))
    val s = side.roundToInt().coerceAtMost(min(bitmap.width - left, bitmap.height - top)).coerceAtLeast(1)
    val square = Bitmap.createBitmap(bitmap, left, top, s, s)
    val out = min(720, s)
    return if (out == s) square else Bitmap.createScaledBitmap(square, out, out, true)
}
