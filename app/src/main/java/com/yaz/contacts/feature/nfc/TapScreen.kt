package com.yaz.contacts.feature.nfc

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
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
    DisposableEffect(Unit) {
        onDispose {
            if (turnedOnHere && adapter?.isEnabled == true && activity?.isChangingConfigurations != true) openNfcPanel(context)
        }
    }
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
        top = { FloatingTop(title = "Touch phones", leading = { FloatingAction(AppIcons.ArrowBack, "Back", onBack) }) }
    ) { padding ->
        val demo = BuildConfig.DEBUG && adapter == null
        when {
            adapter == null && !demo -> {
                EmptyZone(title = "No NFC on this phone", message = "Share your card as a QR code instead.", icon = AppIcons.QrCode, modifier = Modifier.fillMaxSize().padding(padding))
                return@FloatingFrame
            }
            adapter != null && !on -> {
                EmptyZone(
                    title = "NFC is off",
                    message = "Turn it on to swap cards by touching phones. On leaving, you can turn it back off in one tap.",
                    icon = AppIcons.Nfc,
                    actionLabel = "Turn on NFC",
                    onAction = {
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
            runCatching {
                adapter.enableReaderMode(
                    activity,
                    { tag ->
                        val text = runCatching {
                            Ndef.get(tag)?.use { ndef ->
                                ndef.connect()
                                ndef.ndefMessage?.records?.firstOrNull { it.toMimeType() in setOf("text/vcard", "text/x-vcard") }
                                    ?.payload?.takeIf { it.size <= 64 * 1024 }?.decodeToString()
                            }
                        }.getOrNull()
                        if (text != null) activity.runOnUiThread { received(text) }
                    },
                    NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V,
                    null
                )
            }
            onDispose { runCatching { adapter.disableReaderMode(activity) } }
        }

        // Their card in: once it has come down, the screen to save it.
        val current = touch
        if (current is Touch.Got) LaunchedEffect(current) {
            delay(1700)
            got(current.text)
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
