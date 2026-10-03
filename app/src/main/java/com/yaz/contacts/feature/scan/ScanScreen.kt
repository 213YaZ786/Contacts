package com.yaz.contacts.feature.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.yaz.contacts.ui.component.EmptyZone
import com.yaz.contacts.ui.component.FloatingAction
import com.yaz.contacts.ui.component.FloatingFrame
import com.yaz.contacts.ui.component.FloatingPane
import com.yaz.contacts.ui.component.FloatingTop
import com.yaz.contacts.ui.component.rememberHaptics
import com.yaz.contacts.ui.icon.AppIcons
import java.util.concurrent.Executors

/**
 * Another phone's contact QR code, read by the camera on this phone only:
 * a card goes to the import screen, shown before anything is saved. The
 * pictures are looked at and dropped, never kept.
 */
@Composable
fun ScanScreen(onBack: () -> Unit, onCard: (String) -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    LaunchedEffect(Unit) { if (!allowed) ask.launch(Manifest.permission.CAMERA) }
    var wrong by remember { mutableStateOf(false) }
    val found by rememberUpdatedState<(String) -> Unit> { text ->
        val card = Cards.of(text)
        if (card != null) {
            haptics.done()
            onCard(card)
        } else if (!wrong) {
            haptics.reject()
            wrong = true
        }
    }

    FloatingFrame(
        bottom = 24.dp,
        top = { FloatingTop(title = "Scan a contact", leading = { FloatingAction(AppIcons.ArrowBack, "Back", onBack) }) }
    ) { padding ->
        if (!allowed) {
            EmptyZone(
                title = "The camera reads their QR code",
                message = "Allow the camera to add someone from their phone's QR code. Nothing is kept.",
                icon = AppIcons.QrCode,
                actionLabel = "Allow the camera",
                onAction = { ask.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.fillMaxSize().padding(padding)
            )
            return@FloatingFrame
        }
        Box(Modifier.fillMaxSize()) {
            Camera(onText = { found(it) })
            // A square to aim with, breathing gently.
            val t by rememberInfiniteTransition(label = "aim").animateFloat(0.92f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "aim")
            Canvas(Modifier.align(Alignment.Center).widthIn(max = 320.dp).fillMaxWidth(0.7f).aspectRatio(1f)) {
                val side = size.minDimension * t
                val o = Offset((size.width - side) / 2f, (size.height - side) / 2f)
                drawRoundRect(Color.White.copy(alpha = 0.9f), o, Size(side, side), CornerRadius(28.dp.toPx()), style = Stroke(3.dp.toPx()))
            }
            FloatingPane(shape = RoundedCornerShape(24.dp), modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp).widthIn(max = 420.dp)) {
                Text(
                    if (wrong) "This QR code is not a contact." else "Point at the contact's QR code on their phone.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
                )
            }
            if (wrong) LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(2500)
                wrong = false
            }
        }
    }
}

/** The back camera's picture on the screen, each frame looked at for a QR code. */
@Composable
private fun Camera(onText: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var request by remember { mutableStateOf<SurfaceRequest?>(null) }
    val report by rememberUpdatedState(onText)
    DisposableEffect(owner) {
        val executor = Executors.newSingleThreadExecutor()
        val reader = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
        }
        val main = ContextCompat.getMainExecutor(context)
        var provider: ProcessCameraProvider? = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val p = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = p
            val preview = Preview.Builder().build().apply { setSurfaceProvider { request = it } }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { image ->
                runCatching {
                    // The brightness plane is all a QR code needs.
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    val source = PlanarYUVLuminanceSource(data, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
                    reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
                }.getOrNull()?.let { text -> main.execute { report(text) } }
                image.close()
            }
            runCatching {
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, main)
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }
    request?.let { CameraXViewfinder(surfaceRequest = it, modifier = Modifier.fillMaxSize()) }
}

