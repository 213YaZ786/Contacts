package com.yaz.contacts.feature.nfc

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.nfc.tech.IsoDep
import android.nfc.tech.Ndef
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.yaz.contacts.BuildConfig
import com.yaz.contacts.core.contacts.Details
import com.yaz.contacts.core.contacts.Look
import com.yaz.contacts.core.nfc.CardTagService
import com.yaz.contacts.core.nfc.Sharing
import com.yaz.contacts.core.vcard.VCard
import com.yaz.contacts.feature.common.ActionTile
import com.yaz.contacts.feature.common.EvenRows
import com.yaz.contacts.feature.contact.Poster
import com.yaz.contacts.ui.component.EmptyZone
import com.yaz.contacts.ui.component.BoldButton
import com.yaz.contacts.ui.component.QuietButton
import com.yaz.contacts.ui.component.FloatingAction
import com.yaz.contacts.ui.component.FloatingFrame
import com.yaz.contacts.ui.component.FloatingTop
import com.yaz.contacts.ui.component.rememberHaptics
import com.yaz.contacts.ui.icon.AppIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.yaz.contacts.core.nfc.Ndef as NdefBytes

/** Where the touch is: waiting, the card gone to the other phone, or theirs come in. */
private sealed interface Touch {
    data object Waiting : Touch
    data object Sent : Touch
    data class Got(val text: String, val name: String, val look: Look) : Touch
}

/**
 * Two phones held top to top: this one gives the user's card as an NFC
 * tag the other reads (it needs no app, Android offers to save the card),
 * or reads theirs. The poster waits with a light on its top edge, the
 * side that touches; a wave of light runs through it when the card goes,
 * and theirs comes down from the top when it comes in. Nothing goes over
 * a network; sharing stops when this screen closes.
 */
@Composable
fun TapScreen(give: Boolean, own: Boolean = true, me: Details?, mePhoto: ImageBitmap?, card: String?, onBack: () -> Unit, onCard: (String) -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val adapter = remember { NfcAdapter.getDefaultAdapter(context) }
    val haptics = rememberHaptics()
    var on by remember { mutableStateOf(adapter?.isEnabled == true) }
    LifecycleResumeEffect(Unit) {
        on = adapter?.isEnabled == true
        onPauseOrDispose { }
    }
    var receiving by remember { mutableStateOf(!give) }
    // NFC for the time of the touch: Android's own NFC panel over the screen
    // when it is off (an app cannot switch it itself), and again on leaving,
    // to put it back off in one tap.
    var turnedOnHere by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var asked by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (adapter != null && !adapter.isEnabled && !asked) {
            asked = true
            turnedOnHere = true
            openNfcPanel(context)
        }
    }
    // Leaving after NFC was turned on here: under Android's panel, the switch shows
    // what to do (turn it off), then a tick once it is off, and the screen goes.
    var leaving by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun leave(then: () -> Unit) {
        if (turnedOnHere && adapter?.isEnabled == true) {
            leaving = then
            openNfcPanel(context)
        } else then()
    }
    val back by rememberUpdatedState(onBack)
    androidx.activity.compose.BackHandler(leaving == null) { leave { back() } }
    var touch by remember { mutableStateOf<Touch>(Touch.Waiting) }
    val got by rememberUpdatedState(onCard)

    fun received(text: String) {
        val d = VCard.parse(text, max = 1).firstOrNull()?.details ?: return
        haptics.done()
        touch = Touch.Got(text, d.display.ifBlank { d.name.display() }, d.look)
    }

    // Debug builds on a phone without NFC (the emulator) play the touch from adb, to be seen.
    if (BuildConfig.DEBUG) DisposableEffect(Unit) {
        val stop = TapDemo.listen { text -> activity?.runOnUiThread { if (text == null) { haptics.done(); touch = Touch.Sent } else received(text) } }
        onDispose { stop() }
    }

    FloatingFrame(
        bottom = 24.dp,
        top = { FloatingTop(title = "Drop", leading = { FloatingAction(AppIcons.ArrowBack, "Back", onClick = { leave { back() } }) }) }
    ) { padding ->
        val demo = BuildConfig.DEBUG && adapter == null
        when {
            adapter == null && !demo -> {
                EmptyZone(title = "No NFC on this phone", message = "Share your card as a QR code instead.", icon = AppIcons.QrCode, modifier = Modifier.fillMaxSize().padding(padding))
                return@FloatingFrame
            }
            leaving != null -> {
                val then = leaving!!
                // Back from the panel: off, a tick and away; still on, the choice stays.
                LaunchedEffect(on) {
                    if (!on) {
                        haptics.done()
                        delay(900)
                        leaving = null
                        then()
                    }
                }
                NfcSwitchStep(
                    turnOn = false,
                    done = !on,
                    title = if (on) "Turn NFC back off" else "NFC is off",
                    action = "Turn off NFC" to { openNfcPanel(context) },
                    other = "Leave it on" to { leaving = null; then() },
                    modifier = Modifier.fillMaxSize().padding(padding)
                )
                return@FloatingFrame
            }
            adapter != null && !on -> {
                NfcSwitchStep(
                    turnOn = true,
                    done = false,
                    title = "NFC is off",
                    action = "Turn on NFC" to {
                        turnedOnHere = true
                        openNfcPanel(context)
                    },
                    modifier = Modifier.fillMaxSize().padding(padding)
                )
                return@FloatingFrame
            }
        }

        // This phone as a tag holding the card, while the screen is open.
        if (adapter != null && !receiving && card != null && activity != null) DisposableEffect(card) {
            Sharing.start(NdefBytes.mime("text/vcard", card.toByteArray()))
            val emulation = runCatching { CardEmulation.getInstance(adapter) }.getOrNull()
            runCatching { emulation?.setPreferredService(activity, ComponentName(context, CardTagService::class.java)) }
            val stop = Sharing.onRead {
                activity.runOnUiThread {
                    haptics.done()
                    touch = Touch.Sent
                }
            }
            onDispose {
                stop()
                runCatching { emulation?.unsetPreferredService(activity) }
                Sharing.stop()
            }
        }
        // Or a reader of the other phone's card.
        if (adapter != null && receiving && activity != null) DisposableEffect(Unit) {
            // From https://github.com/sameerasw/MeDrop/blob/main/app/src/main/java/com/sameerasw/medrop/utils/MeDropNfcManager.kt (MIT), enableReaderMode:
            // the standard NDEF read first, then the ISO-DEP fallback (SELECT AID, SELECT E104, NLEN, chunks of 240).
            // Changed: a length over 64 KB refused before reading; the answer checked as 9000, an empty or short answer
            // ending the read; IsoDep closed by use; only a record of type text/vcard or text/x-vcard, at most 64 KB,
            // is taken (MeDrop's "contains BEGIN:VCARD" test and its raw-bytes fallback are left out).
            // The platform sounds stay off (FLAG_READER_NO_PLATFORM_SOUNDS) as the app gives its own haptics.
            val cardOf = { message: NdefMessage? ->
                message?.records?.firstOrNull { it.toMimeType() in setOf("text/vcard", "text/x-vcard") }
                    ?.payload?.takeIf { it.size <= 64 * 1024 }?.decodeToString()
            }
            val succeeded = { answer: ByteArray -> answer.size >= 2 && answer[answer.size - 2] == 0x90.toByte() && answer[answer.size - 1] == 0x00.toByte() }
            runCatching {
                adapter.enableReaderMode(
                    activity,
                    { tag ->
                        val text = runCatching {
                            Ndef.get(tag)?.use { ndef ->
                                ndef.connect()
                                cardOf(ndef.ndefMessage)
                            }
                        }.getOrNull() ?: runCatching {
                            IsoDep.get(tag)?.use inFallback@{ isoDep ->
                                isoDep.connect()
                                // SELECT the NDEF application D2760000850101, then the file E104.
                                val selectAid = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x07, 0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)
                                if (!succeeded(isoDep.transceive(selectAid))) return@inFallback null
                                val selectFile = byteArrayOf(0x00, 0xA4.toByte(), 0x00, 0x0C, 0x02, 0xE1.toByte(), 0x04)
                                if (!succeeded(isoDep.transceive(selectFile))) return@inFallback null
                                // NLEN: the message's length, on the first two bytes.
                                val nlenAnswer = isoDep.transceive(byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x00, 0x02))
                                if (nlenAnswer.size < 4 || !succeeded(nlenAnswer)) return@inFallback null
                                val nlen = ((nlenAnswer[0].toInt() and 0xFF) shl 8) or (nlenAnswer[1].toInt() and 0xFF)
                                if (nlen !in 1..64 * 1024) return@inFallback null
                                var offset = 2
                                val data = java.io.ByteArrayOutputStream()
                                while (data.size() < nlen && offset <= 0xFFFF) {
                                    val chunk = minOf(nlen - data.size(), 240)
                                    val answer = isoDep.transceive(
                                        byteArrayOf(0x00, 0xB0.toByte(), (offset shr 8).toByte(), offset.toByte(), chunk.toByte())
                                    )
                                    if (!succeeded(answer) || answer.size == 2) return@inFallback null
                                    data.write(answer, 0, minOf(answer.size - 2, nlen - data.size()))
                                    offset += answer.size - 2
                                }
                                if (data.size() != nlen) null else cardOf(NdefMessage(data.toByteArray()))
                            }
                        }.getOrNull()
                        if (text != null) activity.runOnUiThread { received(text) }
                    },
                    NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
                        NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                    null
                )
            }
            onDispose { runCatching { adapter.disableReaderMode(activity) } }
        }

        // Their card in: once it has come down, the screen to save it.
        val current = touch
        if (current is Touch.Got) LaunchedEffect(current) {
            delay(1700)
            leave { got(current.text) }
        }
        if (current is Touch.Sent) LaunchedEffect(current) {
            delay(2600)
            touch = Touch.Waiting
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val room = maxHeight
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), start = 16.dp, end = 16.dp)
            ) {
                Spacer(Modifier.height(8.dp))
                val ratio = 9f / 19.5f
                val height = (room - padding.calculateTopPadding() - 250.dp).coerceIn(220.dp, 560.dp)
                Stage(
                    me = me, mePhoto = mePhoto, receiving = receiving, touch = touch,
                    modifier = Modifier.width(minOf(height * ratio, 300.dp)).aspectRatio(ratio)
                )
                Text(
                    when {
                        touch is Touch.Got -> "${(touch as Touch.Got).name.ifBlank { "Their card" }} is here"
                        touch is Touch.Sent -> if (own) "Sent. Their phone offers to save your card." else "Sent. Their phone offers to save ${me?.display ?: "the card"}."
                        receiving -> "Hold the top of your phone against the top of theirs, with their card open to share."
                        else -> "Hold the top of your phone against the top of theirs. They need no app to save " + (if (own) "your card." else "${me?.display ?: "this card"}.")
                    },
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 420.dp)
                )
                EvenRows(minSlot = 120.dp, modifier = Modifier.widthIn(max = 420.dp)) {
                    if (give) ActionTile(AppIcons.Share, if (own) "Give mine" else "Give card", accent = !receiving) { receiving = false; touch = Touch.Waiting }
                    ActionTile(AppIcons.Download, "Get theirs", accent = receiving) { receiving = true; touch = Touch.Waiting }
                }
            }
        }
    }
}

/**
 * The poster on stage: the user's when giving (an empty glass phone when
 * getting), a light breathing on its top edge, the side that touches.
 * The card rides a wave: sent, a wave rises from below and pushes the
 * poster up and away to the other phone, then it comes back; received,
 * a wave comes down from the top edge and carries their poster in.
 */
@Composable
private fun Stage(me: Details?, mePhoto: ImageBitmap?, receiving: Boolean, touch: Touch, modifier: Modifier) {
    val breath by rememberInfiniteTransition(label = "edge").animateFloat(0.55f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "breath")
    val shimmer by rememberInfiniteTransition(label = "shimmer").animateFloat(-0.3f, 1.3f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "x")
    // The wave's travel, 0 to 1, and the poster it carries (in heights of the stage, up is negative).
    val wave = remember { Animatable(0f) }
    val ride = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(touch) {
        when (touch) {
            Touch.Waiting -> {
                wave.snapTo(0f)
                ride.snapTo(0f)
                fade.snapTo(1f)
            }
            Touch.Sent -> {
                wave.snapTo(0f)
                launch { wave.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
                delay(220)
                // Pushed up by the crest, out through the top edge.
                launch { fade.animateTo(0f, tween(520)) }
                ride.animateTo(-1.25f, tween(640, easing = FastOutSlowInEasing))
                delay(700)
                // Still the user's card: it comes back up from below.
                ride.snapTo(0.6f)
                launch { fade.animateTo(1f, tween(300)) }
                ride.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 220f))
            }
            is Touch.Got -> {
                wave.snapTo(0f)
                ride.snapTo(-1.25f)
                fade.snapTo(0f)
                launch { wave.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
                delay(120)
                // Carried in from the top edge on the crest.
                launch { fade.animateTo(1f, tween(380)) }
                ride.animateTo(0f, spring(dampingRatio = 0.82f, stiffness = 170f))
            }
        }
    }
    val accent = MaterialTheme.colorScheme.primary
    val tone = when (touch) {
        is Touch.Got -> touch.look.color.takeIf { it != 0 }?.let { Color(it) } ?: accent
        else -> me?.look?.color?.takeIf { it != 0 }?.let { Color(it) } ?: accent
    }
    BoxWithConstraints(modifier) {
        val shape = RoundedCornerShape(30.dp)
        // The wave: rings spreading from where the card enters or leaves,
        // drawn past the poster's edges, as on water.
        Canvas(Modifier.fillMaxSize()) {
            val p = wave.value
            if (p <= 0f || p >= 1f) return@Canvas
            val up = touch == Touch.Sent
            val center = Offset(size.width / 2f, if (up) size.height * 1.05f else -size.height * 0.05f)
            val reach = size.height * 1.5f
            for (k in 0 until 4) {
                val r = reach * p - k * size.height * 0.09f
                if (r <= 0f) continue
                val a = (1f - p * p) * (1f - k * 0.2f)
                drawCircle(
                    Brush.radialGradient(
                        0f to Color.Transparent,
                        0.8f to Color.Transparent,
                        0.93f to tone.copy(alpha = 0.75f * a),
                        1f to Color.White.copy(alpha = 0.95f * a),
                        center = center, radius = r
                    ),
                    radius = r, center = center
                )
            }
        }
        // What the wave carries: theirs coming in, else mine, else an empty phone waiting.
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                translationY = ride.value * size.height
                alpha = fade.value
                val swell = 1f + 0.035f * kotlin.math.sin(wave.value * Math.PI.toFloat())
                scaleX = swell
                scaleY = swell
            }.clip(shape)
        ) {
            when {
                touch is Touch.Got -> Poster(touch.name, null, touch.look, Modifier.fillMaxSize())
                !receiving && me != null -> Poster(me.display, mePhoto, me.look, Modifier.fillMaxSize())
                else -> Box(Modifier.fillMaxSize()) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawRect(Brush.verticalGradient(listOf(accent.copy(alpha = 0.18f), accent.copy(alpha = 0.05f))))
                    }
                    androidx.compose.material3.Icon(
                        AppIcons.Nfc, contentDescription = null, tint = accent,
                        modifier = Modifier.align(Alignment.Center).width(72.dp).aspectRatio(1f)
                    )
                }
            }
            // The light on the top edge: the side that touches the other phone, fading out well before the middle.
            Canvas(Modifier.fillMaxSize()) {
                val glow = if (touch == Touch.Waiting) breath else 1f
                val reach = size.height * 0.32f
                drawRect(
                    Brush.radialGradient(
                        0f to Color.White.copy(alpha = 0.9f * glow),
                        0.35f to tone.copy(alpha = 0.4f * glow),
                        1f to Color.Transparent,
                        center = Offset(size.width / 2f, 0f),
                        radius = reach
                    ),
                    size = androidx.compose.ui.geometry.Size(size.width, reach)
                )
                val x = size.width * shimmer
                drawRect(
                    Brush.horizontalGradient(
                        0f to Color.Transparent, 0.5f to Color.White.copy(alpha = 0.95f * glow), 1f to Color.Transparent,
                        startX = x - size.width * 0.25f, endX = x + size.width * 0.25f
                    ),
                    size = androidx.compose.ui.geometry.Size(size.width, 3.dp.toPx())
                )
            }
        }
    }
}

/**
 * What to do in Android's NFC panel, shown under it: a switch that a finger
 * taps, turning on or off over and over, until [done] gives a tick.
 */
@Composable
private fun NfcSwitchStep(turnOn: Boolean, done: Boolean, title: String, action: Pair<String, () -> Unit>, modifier: Modifier, other: Pair<String, () -> Unit>? = null) {
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    val knob = MaterialTheme.colorScheme.surface
    val finger = MaterialTheme.colorScheme.onSurface
    // One loop: the finger comes, taps, the knob slides and the track fills or empties, a pause, back.
    val loop by rememberInfiniteTransition(label = "switch").animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "t")
    val tick = remember { Animatable(0f) }
    LaunchedEffect(done) { if (done) tick.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 300f)) else tick.snapTo(0f) }
    Column(modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.width(200.dp).aspectRatio(1.6f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width * 0.62f
                val h = w * 0.5f
                val left = (size.width - w) / 2f
                val top = (size.height - h) / 2f
                // Where the switch stands in this loop: 0 the start state, 1 the asked one.
                val flip = if (done) 1f else ((loop - 0.35f) / 0.15f).coerceIn(0f, 1f) * (1f - ((loop - 0.9f) / 0.1f).coerceIn(0f, 1f))
                val lit = if (turnOn) flip else 1f - flip
                drawRoundRect(androidx.compose.ui.graphics.lerp(track, accent, lit), Offset(left, top), androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(h / 2f))
                val r = h * 0.38f
                val x = left + h / 2f + (w - h) * lit
                drawCircle(Color.Black.copy(alpha = 0.12f), r * 1.05f, Offset(x, size.height / 2f + 2.dp.toPx()))
                drawCircle(knob, r, Offset(x, size.height / 2f))
                // The finger: comes in, presses (a ring), leaves.
                if (!done) {
                    val come = ((loop - 0.1f) / 0.25f).coerceIn(0f, 1f)
                    val go = ((loop - 0.55f) / 0.25f).coerceIn(0f, 1f)
                    val a = (come * (1f - go))
                    if (a > 0f) {
                        val fx = x + (1f - come) * w * 0.5f
                        val fy = size.height / 2f + (1f - come) * h + go * h
                        drawCircle(finger.copy(alpha = 0.28f * a), h * 0.3f, Offset(fx, fy))
                        val press = ((loop - 0.35f) / 0.2f).coerceIn(0f, 1f)
                        if (press in 0.01f..0.99f) drawCircle(finger.copy(alpha = 0.45f * (1f - press)), h * (0.3f + 0.5f * press), Offset(fx, fy), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                    }
                }
            }
            androidx.compose.material3.Icon(
                AppIcons.CheckCircle, contentDescription = null, tint = accent,
                modifier = Modifier.align(Alignment.TopEnd).width(36.dp).aspectRatio(1f).graphicsLayer { scaleX = tick.value; scaleY = tick.value; alpha = tick.value.coerceIn(0f, 1f) }
            )
        }
        androidx.compose.material3.Icon(AppIcons.Nfc, contentDescription = null, tint = accent, modifier = Modifier.width(28.dp).aspectRatio(1f))
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        if (!done) {
            BoldButton(onClick = action.second, filled = true) { Text(action.first) }
            // 8 dp between two buttons, Material 3's spacing for a group of buttons (m3.material.io/components/buttons/specs).
            other?.let { (label, run) ->
                Spacer(Modifier.height(8.dp))
                QuietButton(onClick = run) { Text(label) }
            }
        }
    }
}

/** Debug only: plays a touch on a phone without NFC, from adb. */
object TapDemo {
    private val listeners = mutableListOf<(String?) -> Unit>()

    @Synchronized
    fun listen(l: (String?) -> Unit): () -> Unit {
        listeners += l
        return { synchronized(this) { listeners -= l } }
    }

    /** null: the card went; text: theirs came in. */
    @Synchronized
    fun play(text: String?) = listeners.toList().forEach { it(text) }
}

/** Android's NFC switch as a panel over the app; its settings page where there is no panel. */
private fun openNfcPanel(context: Context) {
    val panel = Intent(Settings.Panel.ACTION_NFC).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(panel) }.isFailure) {
        runCatching { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
