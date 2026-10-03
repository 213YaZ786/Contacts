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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.yaz.contacts.core.nfc.CardTagService
import com.yaz.contacts.core.nfc.Sharing
import com.yaz.contacts.feature.common.ActionTile
import com.yaz.contacts.feature.common.EvenRows
import com.yaz.contacts.ui.component.EmptyZone
import com.yaz.contacts.ui.component.FloatingAction
import com.yaz.contacts.ui.component.FloatingFrame
import com.yaz.contacts.ui.component.FloatingTop
import com.yaz.contacts.ui.component.ZoneSurface
import com.yaz.contacts.ui.component.rememberHaptics
import com.yaz.contacts.ui.icon.AppIcons
import com.yaz.contacts.core.nfc.Ndef as NdefBytes

/**
 * Two phones back to back: this one gives the user's card as an NFC tag
 * the other reads (it needs no app, Android offers to save the card), or
 * reads theirs. Nothing goes over a network; sharing stops when this
 * screen closes.
 */
@Composable
fun TapScreen(card: String?, onBack: () -> Unit, onCard: (String) -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val adapter = remember { NfcAdapter.getDefaultAdapter(context) }
    val haptics = rememberHaptics()
    var on by remember { mutableStateOf(adapter?.isEnabled == true) }
    LifecycleResumeEffect(Unit) {
        on = adapter?.isEnabled == true
        onPauseOrDispose { }
    }
    // Share mine by default; Receive reads the other phone.
    var receiving by remember { mutableStateOf(card == null) }
    var sent by remember { mutableIntStateOf(0) }
    val got by rememberUpdatedState(onCard)

    FloatingFrame(
        bottom = 24.dp,
        top = { FloatingTop(title = "Touch phones", leading = { FloatingAction(AppIcons.ArrowBack, "Back", onBack) }) }
    ) { padding ->
        when {
            adapter == null -> {
                EmptyZone(title = "No NFC on this phone", message = "Share your card as a QR code instead.", icon = AppIcons.QrCode, modifier = Modifier.fillMaxSize().padding(padding))
                return@FloatingFrame
            }
            !on -> {
                EmptyZone(
                    title = "NFC is off",
                    message = "Turn it on to swap cards by touching phones.",
                    icon = AppIcons.Nfc,
                    actionLabel = "Turn on NFC",
                    onAction = { runCatching { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) } },
                    modifier = Modifier.fillMaxSize().padding(padding)
                )
                return@FloatingFrame
            }
        }

        // This phone as a tag holding the card, while the screen is open.
        if (!receiving && card != null && activity != null) DisposableEffect(card) {
            Sharing.start(NdefBytes.mime("text/vcard", card.toByteArray()))
            val emulation = runCatching { CardEmulation.getInstance(adapter) }.getOrNull()
            runCatching { emulation?.setPreferredService(activity, ComponentName(context, CardTagService::class.java)) }
            val stop = Sharing.onRead {
                activity.runOnUiThread {
                    sent++
                    haptics.done()
                }
            }
            onDispose {
                stop()
                runCatching { emulation?.unsetPreferredService(activity) }
                Sharing.stop()
            }
        }
        // Or a reader of the other phone's card.
        if (receiving && activity != null) DisposableEffect(Unit) {
            runCatching {
                adapter?.enableReaderMode(
                    activity,
                    { tag ->
                        val text = runCatching {
                            Ndef.get(tag)?.use { ndef ->
                                ndef.connect()
                                ndef.ndefMessage?.records?.firstOrNull { it.toMimeType() in setOf("text/vcard", "text/x-vcard") }
                                    ?.payload?.takeIf { it.size <= 64 * 1024 }?.decodeToString()
                            }
                        }.getOrNull()
                        if (text != null) activity.runOnUiThread {
                            haptics.done()
                            got(text)
                        }
                    },
                    NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V,
                    null
                )
            }
            onDispose { runCatching { adapter?.disableReaderMode(activity) } }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), start = 16.dp, end = 16.dp)
        ) {
            Spacer(Modifier.height(24.dp))
            Waves(Modifier.size(220.dp))
            Text(
                when {
                    receiving -> "Hold the back of your phone against theirs, with their card open to share."
                    sent > 0 -> "Sent. Their phone offers to save your card."
                    else -> "Hold the back of your phone against theirs. Their phone offers to save your card, no app needed."
                },
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp)
            )
            EvenRows(minSlot = 120.dp, modifier = Modifier.widthIn(max = 420.dp)) {
                if (card != null) ActionTile(AppIcons.Share, "Give mine", accent = !receiving) { receiving = false }
                ActionTile(AppIcons.Download, "Get theirs", accent = receiving) { receiving = true }
            }
            ZoneSurface(shape = RoundedCornerShape(20.dp), modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                Text(
                    "Only while this screen is open, with your phone unlocked. Nothing goes over the internet.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }
    }
}

/** Rings of the accent leaving the NFC mark, as the field reaches out. */
@Composable
private fun Waves(modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val t by rememberInfiniteTransition(label = "waves").animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "t")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            for (k in 0 until 3) {
                val p = (t + k / 3f) % 1f
                drawCircle(accent.copy(alpha = 0.4f * (1f - p)), radius = size.minDimension / 2f * (0.3f + 0.7f * p), style = Stroke(width = (3f - 2f * p).dp.toPx()))
            }
        }
        Icon(AppIcons.Nfc, contentDescription = null, tint = accent, modifier = Modifier.size(64.dp))
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
