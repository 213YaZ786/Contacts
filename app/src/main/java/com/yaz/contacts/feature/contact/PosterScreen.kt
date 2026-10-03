package com.yaz.contacts.feature.contact

import android.provider.ContactsContract
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yaz.contacts.core.contacts.Details
import com.yaz.contacts.core.contacts.Look
import com.yaz.contacts.core.contacts.POSTER_STYLES
import com.yaz.contacts.core.security.SafeImages
import com.yaz.contacts.data.contacts.ContactStore
import com.yaz.contacts.data.contacts.ContactWriter
import com.yaz.contacts.feature.common.EvenRows
import com.yaz.contacts.ui.component.FloatingAction
import com.yaz.contacts.ui.component.FloatingFrame
import com.yaz.contacts.ui.component.FloatingTop
import com.yaz.contacts.ui.component.LoadingMark
import com.yaz.contacts.ui.component.ZoneSurface
import com.yaz.contacts.ui.component.rememberHaptics
import com.yaz.contacts.ui.icon.AppIcons
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * How a person fills the screen when they call: their photo framed by the
 * fingers (pinch to zoom, drag to place), their name in a style, their
 * colour; their monogram when there is no photo. Dialer shows it; this
 * screen shows it the same way, at the phone's own proportions.
 */
@Composable
fun PosterScreen(id: Long, onBack: () -> Unit) {
    val store: ContactStore = koinInject()
    val writer: ContactWriter = koinInject()
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var details by remember { mutableStateOf<Details?>(null) }
    var look by remember { mutableStateOf(Look()) }
    LaunchedEffect(id) {
        details = store.details(id)?.also { look = it.look }
    }
    val photo by produceState<ImageBitmap?>(null, details?.id) {
        val d = details ?: return@produceState
        value = withContext(Dispatchers.IO) {
            // The large photo, decoded away from the app like any picture from a sync.
            val bytes = runCatching {
                ContactsContract.Contacts.openContactPhotoInputStream(context.contentResolver, ContactsContract.Contacts.getLookupUri(d.id, d.lookup), true)?.use { it.readBytes() }
            }.getOrNull()
            bytes?.let { SafeImages.decode(context, it, 2048) }?.asImageBitmap()
        }
    }
    var saving by remember { mutableStateOf(false) }

    FloatingFrame(
        bottom = 24.dp,
        top = {
            FloatingTop(
                title = "Poster",
                leading = { FloatingAction(AppIcons.Close, "Cancel", onBack) },
                trailing = {
                    FloatingAction(AppIcons.Done, "Save", {
                        val d = details ?: return@FloatingAction
                        if (saving) return@FloatingAction
                        saving = true
                        scope.launch {
                            val ok = writer.save(d, d.copy(look = look), null) != null
                            saving = false
                            if (ok) {
                                haptics.done()
                                onBack()
                            } else haptics.reject()
                        }
                    }, tint = MaterialTheme.colorScheme.primary)
                }
            )
        }
    ) { padding ->
        val d = details
        if (d == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingMark(size = 72.dp) }
            return@FloatingFrame
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
        val room = maxHeight
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(padding.calculateTopPadding()))
            // At the phone's own proportions, as large as the window lets it be
            // while the styles and colours stay in sight below it.
            val ratio = 9f / 19.5f
            val height = (room - padding.calculateTopPadding() - CONTROLS).coerceAtLeast(240.dp)
            val width = minOf(height * ratio, 360.dp)
            Poster(d.display, photo, look, Modifier.width(width).aspectRatio(ratio)) { look = it }
            if (photo != null) Text("Pinch to zoom, drag to frame", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // The name's styles, each shown in its own letters.
            EvenRows(minSlot = 56.dp, gap = 8.dp, modifier = Modifier.widthIn(max = 520.dp)) {
                POSTER_STYLES.forEach { style ->
                    ZoneSurface(shape = RoundedCornerShape(16.dp), accent = look.posterStyle == style, onClick = {
                        haptics.tick()
                        look = look.copy(posterStyle = style)
                    }) {
                        Text("Aa", style = nameStyle(style, 20f), color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                    }
                }
            }
            EvenRows(minSlot = 32.dp, gap = 8.dp, modifier = Modifier.widthIn(max = 400.dp)) {
                val accent = MaterialTheme.colorScheme.primary
                PERSON_COLOURS.forEach { c ->
                    val colour = if (c == 0) accent else Color(c)
                    val chosen = c == look.color
                    Box(
                        Modifier.aspectRatio(1f).clip(CircleShape).background(colour)
                            .clickable {
                                haptics.tick()
                                look = look.copy(color = c)
                            }
                            .semantics { contentDescription = if (c == 0) "Wallpaper colour" else "Colour" },
                        contentAlignment = Alignment.Center
                    ) {
                        if (chosen) Box(Modifier.size(12.dp).clip(CircleShape).background(Color.White))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
        }
    }
}

/** What the styles, the colours and the hint take under the poster. */
private val CONTROLS = 270.dp

/** The poster as Dialer draws it full screen: photo framed, dark fade, name on top. */
@Composable
fun Poster(name: String, photo: ImageBitmap?, look: Look, modifier: Modifier, onFrame: ((Look) -> Unit)? = null) {
    val pop = remember { Animatable(0.94f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 300f)) }
    val tone = if (look.color != 0) Color(look.color) else MaterialTheme.colorScheme.primary
    // The gesture reads the framing as it is now, not as it was when the finger came down.
    val current by androidx.compose.runtime.rememberUpdatedState(look)
    val frame by androidx.compose.runtime.rememberUpdatedState(onFrame)
    Box(
        modifier
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
            }
            .clip(RoundedCornerShape(32.dp))
            .background(Brush.radialGradient(listOf(tone.copy(alpha = 0.95f), darker(tone)), radius = 900f))
    ) {
        if (photo != null) {
            Canvas(
                Modifier.fillMaxSize().then(
                    if (onFrame == null) Modifier else Modifier.pointerInput(photo) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val look = current
                            val z = (look.posterZoom * zoom).coerceIn(1f, 4f)
                            val fit = max(this.size.width.toFloat() / photo.width, this.size.height.toFloat() / photo.height) * z
                            val w = photo.width * fit
                            val h = photo.height * fit
                            // The point of the photo at the centre moves against the finger, never leaving a gap.
                            val minX = this.size.width / 2f / w
                            val minY = this.size.height / 2f / h
                            val x = (look.posterX - pan.x / w).coerceIn(minX, 1f - minX)
                            val y = (look.posterY - pan.y / h).coerceIn(minY, 1f - minY)
                            frame?.invoke(look.copy(posterZoom = z, posterX = x, posterY = y))
                        }
                    }
                )
            ) {
                val fit = max(this.size.width / photo.width, this.size.height / photo.height) * look.posterZoom
                val w = photo.width * fit
                val h = photo.height * fit
                val minX = this.size.width / 2f / w
                val minY = this.size.height / 2f / h
                val cx = look.posterX.coerceIn(minX, 1f - minX)
                val cy = look.posterY.coerceIn(minY, 1f - minY)
                val left = this.size.width / 2f - cx * w
                val top = this.size.height / 2f - cy * h
                drawImage(photo, dstOffset = IntOffset(left.roundToInt(), top.roundToInt()), dstSize = IntSize(w.roundToInt(), h.roundToInt()))
                drawRect(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f)))
                drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.25f), 0.25f to Color.Transparent))
            }
        } else {
            // No photo: their monogram, large, on their colour.
            val letters = look.letters.ifBlank { name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() } }
            Text(
                look.emoji.ifBlank { letters },
                style = TextStyle(fontSize = 96.sp, fontWeight = FontWeight.Light, color = Color.White.copy(alpha = 0.92f)),
                modifier = Modifier.align(Alignment.Center)
            )
        }
        // The name as large as fits: its longest word never cut, at most 34.
        BoxWithConstraints(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = 48.dp, start = 16.dp, end = 16.dp)) {
            val shown = if (look.posterStyle == "bold") name.uppercase() else name
            val longest = shown.split(' ').maxOfOrNull { it.length }?.coerceAtLeast(4) ?: 4
            val widthFactor = if (look.posterStyle == "bold") 0.72f else 0.6f
            val size = minOf(34f, maxWidth.value / (longest * widthFactor * (if (look.posterStyle == "bold") 1.15f else 1f)))
            Text(
                shown,
                style = nameStyle(look.posterStyle, size).copy(color = Color.White, shadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 2f), 12f)),
                textAlign = TextAlign.Center,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** The name's look for a poster style, at [size]. */
fun nameStyle(style: String, size: Float): TextStyle = when (style) {
    "bold" -> TextStyle(fontSize = (size * 1.15f).sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp)
    "light" -> TextStyle(fontSize = size.sp, fontWeight = FontWeight.ExtraLight, letterSpacing = 1.sp)
    "serif" -> TextStyle(fontSize = size.sp, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic)
    "round" -> TextStyle(fontSize = size.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold)
    else -> TextStyle(fontSize = size.sp, fontWeight = FontWeight.Medium)
}

private fun darker(c: Color) = Color(c.red * 0.45f, c.green * 0.45f, c.blue * 0.55f, 1f)

