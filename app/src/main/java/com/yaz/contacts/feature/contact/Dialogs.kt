package com.yaz.contacts.feature.contact

import android.graphics.Bitmap
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yaz.contacts.core.contacts.Details
import com.yaz.contacts.core.contacts.Look
import com.yaz.contacts.core.contacts.MONOGRAM_FONTS
import com.yaz.contacts.feature.common.EvenRows
import com.yaz.contacts.ui.component.ContactAvatar
import com.yaz.contacts.ui.component.ZoneSurface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.yaz.contacts.core.contacts.VIBRATIONS
import com.yaz.contacts.core.vcard.VCard
import com.yaz.contacts.ui.component.ZoneAlertDialog
import com.yaz.contacts.ui.component.rememberHaptics
import com.yaz.contacts.ui.icon.AppIcons
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The contact as a QR code another phone's camera reads into a contact:
 * name, company, numbers, emails, websites. Made on the phone, shown only
 * here.
 */
@Composable
fun QrDialog(d: Details, onDismiss: () -> Unit) {
    // The user's own card gives only what they chose; another person's card goes whole.
    val settingsStore: com.yaz.contacts.data.settings.SettingsStore = org.koin.compose.koinInject()
    val keptBack = settingsStore.current.keptBack
    val shown = if (android.provider.ContactsContract.isProfileId(d.id)) com.yaz.contacts.core.contacts.Shareable.keep(d, keptBack) else d
    val code by produceState<ImageBitmap?>(null, shown) { value = withContext(Dispatchers.Default) { qr(VCard.short(shown, look = android.provider.ContactsContract.isProfileId(d.id))) } }
    val pop = remember { Animatable(0.85f) }
    LaunchedEffect(code) { if (code != null) pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 400f)) }
    ZoneAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(d.display, textAlign = TextAlign.Center) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .widthIn(max = 300.dp)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .graphicsLayer {
                            scaleX = pop.value
                            scaleY = pop.value
                        }
                        // Dark on white whatever the theme: cameras read it best so.
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White)
                        .padding(14.dp)
                        .semantics { contentDescription = "QR code of ${d.display}" },
                    contentAlignment = Alignment.Center
                ) {
                    code?.let { Image(it, null, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxWidth().aspectRatio(1f)) }
                }
                Text(
                    "Another phone's camera adds it to its contacts.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

/** A QR code of [text], one pixel per module, black on white. */
fun qr(text: String): ImageBitmap? = runCatching {
    val matrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 1)
    )
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h) { i -> if (matrix.get(i % w, i / w)) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}.getOrNull()

/** Colours a person can have; 0 is the wallpaper's accent, as everyone else. */
val PERSON_COLOURS = listOf(
    0,
    0xFFE53935.toInt(), 0xFFD81B60.toInt(), 0xFF8E24AA.toInt(), 0xFF5E35B1.toInt(),
    0xFF3949AB.toInt(), 0xFF1E88E5.toInt(), 0xFF00ACC1.toInt(), 0xFF00897B.toInt(),
    0xFF43A047.toInt(), 0xFFC0CA33.toInt(), 0xFFFDD835.toInt(), 0xFFFB8C00.toInt(),
    0xFF6D4C41.toInt(), 0xFF546E7A.toInt()
)

/**
 * A person's colour and their monogram when there is no photo: an emoji,
 * or one or two letters in a font, on their colour. Seen live as chosen;
 * Dialer and SMS show the same.
 */
@Composable
fun MonogramDialog(name: String, current: Look, onDismiss: () -> Unit, onPick: (Look) -> Unit) {
    val haptics = rememberHaptics()
    val accent = MaterialTheme.colorScheme.primary
    var look by remember { mutableStateOf(current) }
    ZoneAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Their colour and monogram") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                ContactAvatar(name, null, 88.dp, look = look.forAvatar())
                EvenRows(minSlot = 36.dp, gap = 8.dp) {
                    PERSON_COLOURS.forEach { c ->
                        val colour = if (c == 0) accent else Color(c)
                        val chosen = c == look.color
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(CircleShape)
                                .clickable {
                                    haptics.tick()
                                    look = look.copy(color = c)
                                }
                                .semantics { contentDescription = if (c == 0) "Wallpaper colour" else "Colour" }
                        ) {
                            Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
                                drawCircle(colour, radius = size.minDimension / 2 - (if (chosen) 5.dp.toPx() else 2.dp.toPx()))
                                if (chosen) drawCircle(colour, style = Stroke(2.dp.toPx()))
                            }
                            if (c == 0) Text("A", color = Color(contrast(colour.toArgb())), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = look.letters,
                        onValueChange = { v -> look = look.copy(letters = v.filter { it.isLetterOrDigit() }.take(2).uppercase(), emoji = if (v.isNotBlank()) "" else look.emoji) },
                        label = { Text("Letters") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = look.emoji,
                        // One emoji, made of however many characters it takes.
                        onValueChange = { v -> look = look.copy(emoji = firstEmoji(v), letters = if (v.isNotBlank()) "" else look.letters) },
                        label = { Text("Emoji") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f)
                    )
                }
                EvenRows(minSlot = 52.dp, gap = 6.dp) {
                    MONOGRAM_FONTS.forEach { f ->
                        ZoneSurface(shape = RoundedCornerShape(14.dp), accent = (look.font.ifBlank { "classic" }) == f, onClick = {
                            haptics.tick()
                            look = look.copy(font = if (f == "classic") "" else f)
                        }) {
                            Text(
                                "Aa",
                                textAlign = TextAlign.Center,
                                fontFamily = when (f) {
                                    "serif" -> androidx.compose.ui.text.font.FontFamily.Serif
                                    "mono" -> androidx.compose.ui.text.font.FontFamily.Monospace
                                    "rounded" -> androidx.compose.ui.text.font.FontFamily.SansSerif
                                    else -> androidx.compose.ui.text.font.FontFamily.Default
                                },
                                fontWeight = when (f) {
                                    "bold" -> androidx.compose.ui.text.font.FontWeight.Black
                                    "rounded" -> androidx.compose.ui.text.font.FontWeight.Medium
                                    else -> null
                                },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { haptics.done(); onPick(look) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** The first emoji of what was typed, whole (skin tones and joined emoji included). */
private fun firstEmoji(text: String): String {
    val t = text.trim()
    if (t.isEmpty()) return ""
    val it = java.text.BreakIterator.getCharacterInstance()
    it.setText(t)
    val end = it.next()
    val first = t.substring(0, if (end == java.text.BreakIterator.DONE) t.length else end)
    return first.takeIf { c -> c.codePoints().anyMatch { Character.getType(it) == Character.OTHER_SYMBOL.toInt() || it >= 0x1F000 } }?.take(16) ?: ""
}

private fun contrast(argb: Int): Int {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return if (r * 0.299 + g * 0.587 + b * 0.114 > 150) android.graphics.Color.BLACK else android.graphics.Color.WHITE
}

/** A vibration of their own: each felt when tapped, kept when chosen. */
@Composable
fun VibrationDialog(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(current) }
    var played by remember { mutableIntStateOf(0) }
    fun play(name: String) {
        val pattern = VIBRATIONS[name] ?: return
        played++
        runCatching { context.getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }
    ZoneAlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.Vibration, null) },
        title = { Text("Their vibration") },
        text = {
            Column {
                Text("Felt for their calls and messages, to know it is them without looking.", style = MaterialTheme.typography.bodyMedium)
                (listOf("") + VIBRATIONS.keys).forEach { name ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                selected = name
                                play(name)
                            }
                            .padding(vertical = 8.dp, horizontal = 4.dp)
                    ) {
                        RadioButton(selected = name == selected, onClick = null)
                        Text(name.ifBlank { "As the phone" }, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(selected) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
