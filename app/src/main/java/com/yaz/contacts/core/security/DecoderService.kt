package com.yaz.contacts.core.security

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.DataOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Decodes pictures that come from outside (a photo chosen, a contact
 * card's photo) in a process of its own with no permission, no files and
 * no network: a picture crafted to attack the image decoder can reach
 * nothing of the user's. What comes back is plain pixels, which the app
 * takes without parsing any picture format.
 */
class DecoderService : Service() {

    private val binder = object : IDecoder.Stub() {
        override fun decode(input: ParcelFileDescriptor, maxSide: Int): ParcelFileDescriptor? {
            val bytes = ParcelFileDescriptor.AutoCloseInputStream(input).use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_INPUT) return null
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            val side = maxSide.coerceIn(64, 4096)
            val bitmap = runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width
                    val h = info.size.height
                    require(w in 1..MAX_DIMENSION && h in 1..MAX_DIMENSION)
                    val scale = min(1f, side.toFloat() / max(w, h))
                    if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
                }.copy(Bitmap.Config.ARGB_8888, false)
            }.getOrNull() ?: return null
            val pipe = ParcelFileDescriptor.createPipe()
            Thread {
                runCatching {
                    DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).buffered()).use { out ->
                        out.writeInt(bitmap.width)
                        out.writeInt(bitmap.height)
                        val pixels = ByteBuffer.allocate(bitmap.byteCount)
                        bitmap.copyPixelsToBuffer(pixels)
                        out.write(pixels.array())
                    }
                }
                bitmap.recycle()
            }.start()
            return pipe[0]
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        const val MAX_INPUT = 40 * 1024 * 1024
        /** Larger than any camera makes; a bigger header is a trap. */
        const val MAX_DIMENSION = 30_000
    }
}
