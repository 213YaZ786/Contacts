package com.yaz.contacts.feature.scan

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.yaz.contacts.core.contacts.Details
import com.yaz.contacts.core.ocr.CardText
import com.yaz.contacts.core.ocr.OcrModels
import com.yaz.contacts.core.security.SafeImages
import com.yaz.contacts.feature.common.ActionTile
import com.yaz.contacts.feature.common.EvenRows
import com.yaz.contacts.ui.component.BoldButton
import com.yaz.contacts.ui.component.EmptyZone
import com.yaz.contacts.ui.component.FloatingAction
import com.yaz.contacts.ui.component.FloatingFrame
import com.yaz.contacts.ui.component.FloatingPane
import com.yaz.contacts.ui.component.FloatingTop
import com.yaz.contacts.ui.component.LoadingMark
import com.yaz.contacts.ui.component.rememberHaptics
import com.yaz.contacts.ui.icon.AppIcons
import kotlinx.coroutines.launch

/**
 * A paper business card read into a new contact: photographed (or picked
 * from the photos), read on the phone, sorted into fields; the editor then
 * opens with them for the user to check and save. The picture is dropped.
 */
@Composable
fun CardScanScreen(onBack: () -> Unit, onDetails: (Details) -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var ready by remember { mutableStateOf(OcrModels.ready(context)) }
    var fetching by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var failed by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }

    fun readBitmap(bitmap: Bitmap) {
        reading = true
        scope.launch {
            val text = OcrModels.read(context, bitmap)
            reading = false
            val details = text?.let { CardText.parse(it, OcrModels.region(context)) }
            if (details == null || details.name.isEmpty && details.phones.isEmpty() && details.emails.isEmpty()) {
                haptics.reject()
                failed = true
            } else {
                haptics.done()
                onDetails(details)
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch { SafeImages.decode(context, uri, 2400)?.let(::readBitmap) ?: haptics.reject() }
    }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }

    FloatingFrame(
        bottom = 24.dp,
        top = { FloatingTop(title = "Business card", leading = { FloatingAction(AppIcons.ArrowBack, "Back", onBack) }) }
    ) { padding ->
        when {
            !ready -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)
            ) {
                EmptyZone(
                    title = "Read business cards",
                    message = "The phone reads the card itself. It needs its reading data for your languages, " +
                        "${(OcrModels.sizeToFetch(context) + 500_000) / 1_000_000} MB fetched once from Tesseract's repository and checked.",
                    icon = AppIcons.Download
                )
                if (fetching) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth())
                } else BoldButton(filled = true, onClick = {
                    fetching = true
                    scope.launch {
                        val ok = OcrModels.fetch(context) { progress = it }
                        fetching = false
                        ready = ok && OcrModels.ready(context)
                        if (ready) haptics.done() else haptics.reject()
                    }
                }) { Text("Get it") }
            }
            reading -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                modifier = Modifier.fillMaxSize()
            ) {
                LoadingMark(size = 96.dp)
                Text("Reading the card…", style = MaterialTheme.typography.titleMedium)
            }
            else -> Box(Modifier.fillMaxSize()) {
                if (allowed) CardCamera(onReady = { capture = it }) else LaunchedEffect(Unit) { ask.launch(Manifest.permission.CAMERA) }
                // The card's shape to frame it in.
                Canvas(Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 520.dp).fillMaxWidth().aspectRatio(85.6f / 54f)) {
                    drawRoundRect(Color.White.copy(alpha = 0.9f), Offset.Zero, Size(size.width, size.height), CornerRadius(18.dp.toPx()), style = Stroke(3.dp.toPx()))
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).widthIn(max = 520.dp).fillMaxWidth()
                ) {
                    FloatingPane(shape = RoundedCornerShape(22.dp)) {
                        Text(
                            if (failed) "No name, number or email could be read. Try closer, in good light." else "Fit the card in the frame, flat and well lit.",
                            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                        )
                    }
                    EvenRows(minSlot = 120.dp) {
                        ActionTile(AppIcons.PhotoCamera, "Take it", accent = true) {
                            val shot = capture ?: return@ActionTile
                            haptics.firm()
                            shot.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    val bitmap = runCatching { upright(image) }.getOrNull()
                                    image.close()
                                    if (bitmap != null) readBitmap(bitmap) else haptics.reject()
                                }
                                override fun onError(exception: ImageCaptureException) = haptics.reject()
                            })
                        }
                        ActionTile(AppIcons.Photo, "From photos") { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    }
                }
            }
        }
    }
}

/** The camera's picture, turned the way the phone was held. */
private fun upright(image: ImageProxy): Bitmap {
    val bitmap = image.toBitmap()
    val degrees = image.imageInfo.rotationDegrees
    if (degrees == 0) return bitmap
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
}

@Composable
private fun CardCamera(onReady: (ImageCapture) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var request by remember { mutableStateOf<SurfaceRequest?>(null) }
    DisposableEffect(owner) {
        var provider: ProcessCameraProvider? = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val p = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = p
            val preview = Preview.Builder().build().apply { setSurfaceProvider { request = it } }
            val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
            runCatching {
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                onReady(capture)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { provider?.unbindAll() }
    }
    request?.let { CameraXViewfinder(surfaceRequest = it, modifier = Modifier.fillMaxSize()) }
}

