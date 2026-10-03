package com.yaz.contacts.core.security

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.net.Uri
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pictures from outside, decoded by [DecoderService] in its isolated
 * process. Null for anything that is not a picture, too large, or when the
 * decoder took too long or died on it.
 */
object SafeImages {

    suspend fun decode(context: Context, uri: Uri, maxSide: Int = 2048): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()?.use { decode(context, it, maxSide) }
    }

    suspend fun decode(context: Context, bytes: ByteArray, maxSide: Int = 1440): Bitmap? = withContext(Dispatchers.IO) {
        decode(context, bytes.inputStream(), maxSide)
    }

    private suspend fun decode(context: Context, input: InputStream, maxSide: Int): Bitmap? = withTimeoutOrNull(TIMEOUT) {
        val app = context.applicationContext
        var connection: ServiceConnection? = null
        val decoder = suspendCancellableCoroutine<IDecoder?> { cont ->
            val c = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    if (cont.isActive) cont.resume(IDecoder.Stub.asInterface(service))
                }
                override fun onServiceDisconnected(name: ComponentName?) {}
                override fun onBindingDied(name: ComponentName?) {
                    if (cont.isActive) cont.resume(null)
                }
            }
            connection = c
            val bound = runCatching { app.bindService(Intent(app, DecoderService::class.java), c, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
            if (!bound) cont.resume(null)
        }
        try {
            decoder ?: return@withTimeoutOrNull null
            val pipe = ParcelFileDescriptor.createPipe()
            // Fed from here while the decoder reads it.
            Thread {
                runCatching { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out -> input.copyTo(out) } }
            }.start()
            val result = runCatching { decoder.decode(pipe[0], maxSide) }.getOrNull()
            pipe[0].close()
            result ?: return@withTimeoutOrNull null
            runCatching {
                DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(result).buffered()).use { inp ->
                    val w = inp.readInt()
                    val h = inp.readInt()
                    require(w in 1..4096 && h in 1..4096)
                    val pixels = ByteArray(w * h * 4)
                    inp.readFully(pixels)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(ByteBuffer.wrap(pixels)) }
                }
            }.getOrNull()
        } finally {
            connection?.let { runCatching { app.unbindService(it) } }
        }
    }

    private const val TIMEOUT = 15_000L
}
