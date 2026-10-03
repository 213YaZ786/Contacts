package com.yaz.contacts.feature.contact

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yaz.contacts.core.contacts.Account
import com.yaz.contacts.core.contacts.Dates
import com.yaz.contacts.core.contacts.Details
import com.yaz.contacts.core.contacts.Field
import com.yaz.contacts.core.dial.Numbers
import com.yaz.contacts.core.handoff.Reach
import com.yaz.contacts.data.contacts.ContactStore
import com.yaz.contacts.data.contacts.ContactWriter
import com.yaz.contacts.data.contacts.Trash
import com.yaz.contacts.data.settings.SettingsStore
import com.yaz.contacts.feature.common.DeleteQuestion
import com.yaz.contacts.feature.common.rememberUndo
import com.yaz.contacts.ui.component.ContactAvatar
import com.yaz.contacts.ui.component.EmptyZone
import com.yaz.contacts.ui.component.FloatingAction
import com.yaz.contacts.ui.component.FloatingFrame
import com.yaz.contacts.ui.component.FloatingTop
import com.yaz.contacts.ui.component.HeroGlow
import com.yaz.contacts.ui.component.LoadingMark
import com.yaz.contacts.feature.common.ActionTile
import com.yaz.contacts.feature.common.EvenRows
import com.yaz.contacts.ui.component.ZoneSurface
import com.yaz.contacts.ui.component.rememberHaptics
import com.yaz.contacts.ui.icon.AppIcons
import com.yaz.contacts.ui.theme.AlertRed
import com.yaz.contacts.ui.theme.AnswerGreen
import com.yaz.contacts.ui.theme.StarGold
import java.time.LocalDate
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * A person's page: their face in glass over the light of their photo,
 * the ways to reach them, everything their contact holds, and what the
 * phone does for them (ringtone, voicemail, colour, vibration). Calling,
 * writing and the call history are the other apps': each button hands off.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContactScreen(id: Long, onBack: () -> Unit, onEdit: (Long) -> Unit, onDeleted: () -> Unit, onMoved: (Long) -> Unit = { onDeleted() }, onScan: () -> Unit = {}, onPoster: (Long) -> Unit = {}) {
    val store: ContactStore = koinInject()
    val writer: ContactWriter = koinInject()
    val trash: Trash = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settings.collectAsState()
    val changes by store.changes.collectAsState()
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val undo = rememberUndo()
    LaunchedEffect(Unit) { store.refresh() }

    var loaded by remember { mutableStateOf(false) }
    val details by produceState<Details?>(null, id, changes) {
        value = store.details(id)
        loaded = true
    }
    var deleting by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    var choosingColour by remember { mutableStateOf(false) }
    var choosingVibration by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }

    val ringtonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val picked: Uri? = if (Build.VERSION.SDK_INT >= 33) result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        else @Suppress("DEPRECATION") result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        // The default sound is no ringtone of their own.
        val value = picked?.takeIf { it != Settings.System.DEFAULT_RINGTONE_URI }?.toString()
        scope.launch { writer.setRingtone(id, value) }
    }

    val tonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val picked: Uri? = if (Build.VERSION.SDK_INT >= 33) result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        else @Suppress("DEPRECATION") result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        val value = picked?.toString()?.takeIf { it.startsWith("content://media/") || it.startsWith("android.resource://") } ?: ""
        val d = details ?: return@rememberLauncherForActivityResult
        scope.launch { writer.save(d, d.copy(look = d.look.copy(tone = value)), null) }
    }

    FloatingFrame(
        bottom = 24.dp,
        top = {
            FloatingTop(
                title = null,
                leading = { FloatingAction(AppIcons.ArrowBack, "Back", onBack) },
                trailing = {
                    val d = details
                    if (d != null && !d.readOnly) FloatingAction(AppIcons.Edit, "Edit", { onEdit(id) })
                }
            )
        }
    ) { padding ->
        val d = details
        when {
            d == null && !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingMark(size = 72.dp) }
            d == null -> EmptyZone(
                title = "This contact is gone",
                message = "It was deleted or merged into another one.",
                icon = AppIcons.Person,
                modifier = Modifier.fillMaxSize().padding(padding)
            )
            else -> Box(Modifier.fillMaxSize()) {
                HeroGlow(d.photo ?: d.thumbnail, 380.dp, color = d.look.color.takeIf { it != 0 }?.let { Color(it) })
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
                ) {
                    Spacer(Modifier.height(padding.calculateTopPadding() + 8.dp))
                    Header(d)
                    Verified(d)
                    SharedCard(d)
                    Spacer(Modifier.height(20.dp))
                    Actions(d, onQr = { showQr = true }, onScan = onScan)
                    Spacer(Modifier.height(16.dp))
                    Fields(d)
                    if (!android.provider.ContactsContract.isProfileId(d.id)) OnThisPhone(
                        d,
                        onRingtone = {
                            ringtonePicker.launch(
                                Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                                    .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Settings.System.DEFAULT_RINGTONE_URI)
                                    .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, d.ringtone?.let(Uri::parse) ?: Settings.System.DEFAULT_RINGTONE_URI)
                            )
                        },
                        onVoicemail = { on ->
                            haptics.toggle(on)
                            scope.launch { writer.setVoicemail(id, on) }
                        },
                        onColour = { choosingColour = true },
                        onPoster = { onPoster(id) },
                        onVibration = { choosingVibration = true },
                        onTone = {
                            tonePicker.launch(
                                Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                    .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Settings.System.DEFAULT_NOTIFICATION_URI)
                                    .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, d.look.tone.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: Settings.System.DEFAULT_NOTIFICATION_URI)
                            )
                        },
                        onBypass = { on ->
                            haptics.toggle(on)
                            scope.launch { writer.save(d, d.copy(look = d.look.copy(bypass = on)), null) }
                        }
                    )
                    Elsewhere(d, onDelete = { deleting = true }, onMove = { moving = true }, onSeparate = {
                        scope.launch {
                            if (writer.separate(d)) {
                                haptics.done()
                                onBack()
                            }
                        }
                    })
                    Spacer(Modifier.height(24.dp))
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        }
    }

    val d = details ?: return
    if (deleting) {
        DeleteQuestion(d.display.ifBlank { "this contact" }, settings.trashDays, onDismiss = { deleting = false }) {
            deleting = false
            scope.launch {
                if (trash.delete(d, d.accounts.firstOrNull())) {
                    undo.show("${d.display.ifBlank { "Contact" }} deleted") {
                        scope.launch { trash.entries.value.firstOrNull { it.details.id == d.id }?.let { trash.restore(it, store.accounts()) } }
                    }
                    onDeleted()
                }
            }
        }
    }
    if (showQr) QrDialog(d, onDismiss = { showQr = false })
    if (moving) MoveDialog(d, onDismiss = { moving = false }) { account ->
        moving = false
        scope.launch {
            // A copy in the other account, photo included, then the old one goes.
            val photo = d.photo?.let { com.yaz.contacts.core.security.SafeImages.decode(context, android.net.Uri.parse(it)) }
            val fresh = com.yaz.contacts.core.contacts.Snapshot.fresh(d).copy(groups = emptySet())
            val saved = writer.save(null, fresh, account, photo)
            if (saved != null && writer.delete(listOf(d.id)) > 0) {
                haptics.done()
                undo.show("Moved to ${accountLabel(account)}", null)
                onMoved(saved.contactId)
            } else haptics.reject()
        }
    }
    if (choosingColour) MonogramDialog(d.display, d.look, onDismiss = { choosingColour = false }) { look ->
        choosingColour = false
        scope.launch { writer.save(d, d.copy(look = look), null) }
    }
    if (choosingVibration) VibrationDialog(d.look.vibration, onDismiss = { choosingVibration = false }) { vibration ->
        choosingVibration = false
        scope.launch { writer.save(d, d.copy(look = d.look.copy(vibration = vibration)), null) }
    }
}

/** The face popping in, the name, what they are called and where they work. */
@Composable
private fun Header(d: Details) {
    val pop = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 380f)) }
    val ring = d.look.color.takeIf { it != 0 }?.let { Color(it) }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.graphicsLayer {
        scaleX = pop.value
        scaleY = pop.value
        alpha = ((pop.value - 0.6f) / 0.4f).coerceIn(0f, 1f)
    }) {
        if (ring != null) Box(Modifier.size(146.dp)) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) { drawCircle(ring.copy(alpha = 0.55f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx())) }
        }
        ContactAvatar(d.display, d.photo ?: d.thumbnail, 132.dp, look = d.look.forAvatar())
    }
    Spacer(Modifier.height(14.dp))
    Text(d.display.ifBlank { "No name" }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    val phonetic = listOf(d.name.phoneticGiven, d.name.phoneticMiddle, d.name.phoneticFamily).filter { it.isNotBlank() }.joinToString(" ")
    if (phonetic.isNotBlank()) Text(phonetic, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val also = listOfNotNull(d.nickname?.value?.let { "“$it”" }, d.look.pronouns.takeIf { it.isNotBlank() }).joinToString(" · ")
    if (also.isNotBlank()) Text(also, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val work = listOf(d.organization.title, d.organization.department, d.organization.company).filter { it.isNotBlank() }.joinToString(" · ")
    if (work.isNotBlank()) Text(work, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}

/** Their chat key was checked face to face in the messaging app. */
@Composable
private fun Verified(d: Details) {
    val offers: com.yaz.contacts.data.contacts.Offers = koinInject()
    val verified by offers.verified.collectAsState()
    val context = LocalContext.current
    val numbers = d.phones.map { it.value }
    val state = remember(verified, numbers) { offers.isVerified(numbers) } ?: return
    Spacer(Modifier.height(8.dp))
    ZoneSurface(shape = CircleShape, onClick = { numbers.firstOrNull()?.let { Reach.keys(context, it) } }) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            Icon(if (state) AppIcons.Verified else AppIcons.Lock, null, tint = if (state) AnswerGreen else MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (state) "Encrypted chat · verified" else "Encrypted chat", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** What they shared of themselves through the encrypted chat, waiting for the user's tap. */
@Composable
private fun SharedCard(d: Details) {
    val offers: com.yaz.contacts.data.contacts.Offers = koinInject()
    val all by offers.offers.collectAsState()
    val writer: ContactWriter = koinInject()
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val offer = remember(all, d.phones) { offers.offerFor(d.phones.map { it.value }) } ?: return
    val card = remember(offer) { com.yaz.contacts.core.vcard.VCard.parse(offer.card, max = 1).firstOrNull()?.details } ?: return
    val changes = remember(card, d) { com.yaz.contacts.core.contacts.Shared.changes(d, card, offer.photo != null) }
    if (changes.isEmpty()) {
        LaunchedEffect(offer) { offers.drop(offer.number) }
        return
    }
    Spacer(Modifier.height(14.dp))
    ZoneSurface(shape = RoundedCornerShape(24.dp), accent = true, modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Update, null)
                Spacer(Modifier.width(10.dp))
                Text("${d.name.given.ifBlank { d.display }} shared their card", style = MaterialTheme.typography.titleSmall)
            }
            Text("New: " + changes.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
            EvenRows(minSlot = 96.dp) {
                ActionTile(AppIcons.Done, "Apply", accent = true) {
                    scope.launch {
                        val photo = offer.photo?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }.getOrNull() }
                            ?.let { com.yaz.contacts.core.security.SafeImages.decode(context, it) }
                        if (writer.save(d, com.yaz.contacts.core.contacts.Shared.apply(d, card), null, photo) != null) {
                            haptics.done()
                            offers.drop(offer.number)
                        } else haptics.reject()
                    }
                }
                ActionTile(AppIcons.Close, "Ignore") {
                    haptics.tick()
                    offers.drop(offer.number)
                }
            }
        }
    }
}

/** The ways to reach them, each a round pane of glass, wrapping onto a second line on a narrow screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(d: Details, onQr: () -> Unit, onScan: () -> Unit) {
    // The user's own card: only sharing it makes sense.
    if (android.provider.ContactsContract.isProfileId(d.id)) {
        val context = LocalContext.current
        EvenRows(minSlot = 64.dp, modifier = Modifier.widthIn(max = 640.dp)) {
            // Show mine, scan theirs: two phones swap cards face to face.
            ActionTile(AppIcons.QrCode, "My QR code", accent = true) { onQr() }
            ActionTile(AppIcons.PhotoCamera, "Scan theirs") { onScan() }
            ActionTile(AppIcons.Share, "Share") { Reach.share(context, listOf(d.lookup), d.display) }
        }
        return
    }
    val context = LocalContext.current
    val writer: ContactWriter = koinInject()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var choosing by remember { mutableStateOf<String?>(null) }
    var sharing by remember { mutableStateOf(false) }
    val numbers = d.phones.map { it.value }.distinctBy { it.filter(Char::isDigit).takeLast(9) }
    EvenRows(minSlot = 64.dp, modifier = Modifier.widthIn(max = 640.dp)) {
        if (numbers.isNotEmpty()) ActionTile(AppIcons.Call, "Call", AnswerGreen) {
            if (numbers.size == 1) Reach.call(context, numbers[0]) else choosing = "call"
        }
        if (numbers.isNotEmpty() && Reach.canMessage(context)) ActionTile(AppIcons.Message, "Message") {
            if (numbers.size == 1) Reach.message(context, numbers) else choosing = "message"
        }
        if (d.emails.isNotEmpty() && Reach.canEmail(context)) ActionTile(AppIcons.Email, "Email") {
            Reach.email(context, listOf(d.emails.first().value))
        }
        if (d.addresses.isNotEmpty()) ActionTile(AppIcons.Place, "Directions") { Reach.map(context, d.addresses.first().lines) }
        ActionTile(if (d.starred) AppIcons.Star else AppIcons.StarOutline, "Favorite", StarGold, accent = d.starred) {
            haptics.toggle(!d.starred)
            scope.launch { writer.star(d.id, !d.starred) }
        }
        ActionTile(AppIcons.Share, "Share") { sharing = true }
    }
    if (sharing) {
        com.yaz.contacts.ui.component.ZoneAlertDialog(
            onDismissRequest = { sharing = false },
            icon = { Icon(AppIcons.Share, null) },
            title = { Text("Share ${d.display}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShareChoice(AppIcons.ContactPage, "As a contact card", "To any app: messages, mail, files") {
                        sharing = false
                        Reach.share(context, listOf(d.lookup), d.display)
                    }
                    ShareChoice(AppIcons.QrCode, "As a QR code", "For a phone right here to scan") {
                        sharing = false
                        onQr()
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { sharing = false }) { Text("Cancel") } }
        )
    }
    choosing?.let { what ->
        NumberChoice(d, title = if (what == "call") "Call" else "Message", onDismiss = { choosing = null }) { number ->
            choosing = null
            if (what == "call") Reach.call(context, number) else Reach.message(context, listOf(number))
        }
    }
}

@Composable
private fun ShareChoice(icon: ImageVector, title: String, note: String, onClick: () -> Unit) {
    ZoneSurface(shape = RoundedCornerShape(20.dp), onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(start = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Everything the contact holds, a zone per kind, only the kinds it has. */
@Composable
private fun Fields(d: Details) {
    val context = LocalContext.current
    val res = context.resources
    // The same number in two merged accounts is shown once.
    val phones = d.phones.distinctBy { it.value.filter(Char::isDigit).takeLast(9) }
    if (phones.isNotEmpty()) InfoZone("Phone") {
        phones.forEach { p ->
            InfoRow(
                icon = AppIcons.Call,
                value = Numbers.format(context, p.value),
                label = Field.PHONE.label(res, p.kind, p.custom) + if (p.primary) " · default" else "",
                onClick = { Reach.call(context, p.value) },
                copy = p.value,
                trailing = if (Reach.canMessage(context)) ({
                    IconButton(onClick = { Reach.message(context, listOf(p.value)) }) { Icon(AppIcons.Message, "Message", tint = MaterialTheme.colorScheme.primary) }
                }) else null
            )
        }
    }
    if (d.emails.isNotEmpty()) InfoZone("Email") {
        d.emails.forEach { e -> InfoRow(AppIcons.Email, e.value, Field.EMAIL.label(res, e.kind, e.custom), onClick = { Reach.email(context, listOf(e.value)) }, copy = e.value) }
    }
    if (d.addresses.isNotEmpty()) InfoZone("Address") {
        d.addresses.forEach { a -> InfoRow(AppIcons.Place, a.lines, Field.POSTAL.label(res, a.kind, a.custom), onClick = { Reach.map(context, a.lines) }, copy = a.lines) }
    }
    val events = d.events.mapNotNull { e -> Dates.parse(e.value)?.let { e to it } }
    if (events.isNotEmpty()) InfoZone("Dates") {
        val today = LocalDate.now()
        events.forEach { (e, date) ->
            val days = date.daysUntil(today)
            val age = date.ageNext(today)
            val note = when {
                days == 0L && e.kind == android.provider.ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY -> if (age != null) "Today, $age years" else "Today"
                days == 0L -> "Today"
                days == 1L -> "Tomorrow" + (age?.let { ", $it" } ?: "")
                days < 31 -> "In $days days" + (age?.let { ", $it" } ?: "")
                else -> null
            }
            InfoRow(
                icon = if (e.kind == android.provider.ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY) AppIcons.Cake else AppIcons.Event,
                value = Dates.spoken(date),
                label = listOfNotNull(Field.EVENT.label(res, e.kind, e.custom), note).joinToString(" · "),
                onClick = null,
                copy = Dates.spoken(date),
                // On the day, a word to them goes through the messaging app.
                trailing = if (days == 0L && d.phones.isNotEmpty() && Reach.canMessage(context)) ({
                    IconButton(onClick = { Reach.message(context, listOf(d.phones.first().value)) }) { Icon(AppIcons.Message, "Write to them", tint = MaterialTheme.colorScheme.primary) }
                }) else null
            )
        }
    }
    if (d.websites.isNotEmpty()) InfoZone("Website") {
        d.websites.forEach { w -> InfoRow(AppIcons.Link, w.value, Field.WEBSITE.label(res, w.kind, w.custom), onClick = { Reach.web(context, w.value) }, copy = w.value) }
    }
    if (d.relations.isNotEmpty()) InfoZone("Relationships") {
        d.relations.forEach { r -> InfoRow(AppIcons.Group, r.value, Field.RELATION.label(res, r.kind, r.custom), onClick = null, copy = r.value) }
    }
    if (d.messengers.isNotEmpty() || d.sips.isNotEmpty()) InfoZone("Chat") {
        d.messengers.forEach { m -> InfoRow(AppIcons.Message, m.value, m.custom?.takeIf { it.isNotBlank() } ?: "Chat", onClick = null, copy = m.value) }
        d.sips.forEach { s -> InfoRow(AppIcons.Call, s.value, "SIP · " + Field.SIP.label(res, s.kind, s.custom), onClick = null, copy = s.value) }
    }
    if (d.others.isNotEmpty()) InfoZone("In other apps") {
        d.others.forEach { o ->
            InfoRow(AppIcons.Link, o.label, o.app.takeIf { it.isNotBlank() && it != o.label }, onClick = { Reach.other(context, o.rowId, o.mimetype) })
        }
    }
    d.note?.value?.let { note -> InfoZone("Notes") { InfoRow(AppIcons.Edit, note, null, onClick = null, copy = note) } }
    val store: ContactStore = koinInject()
    val groups by store.groups.collectAsState()
    val mine = groups.filter { it.id in d.groups }
    if (mine.isNotEmpty()) InfoZone("Labels") {
        InfoRow(AppIcons.Label, mine.joinToString(", ") { it.title }, null, onClick = null)
    }
}

/** What the phone does when they call or write, kept in their contact for Dialer and SMS. */
@Composable
private fun OnThisPhone(d: Details, onRingtone: () -> Unit, onVoicemail: (Boolean) -> Unit, onColour: () -> Unit, onPoster: () -> Unit, onVibration: () -> Unit, onTone: () -> Unit, onBypass: (Boolean) -> Unit) {
    val context = LocalContext.current
    val ringtone by produceState("Default", d.ringtone) {
        value = when (val r = d.ringtone) {
            null -> "Default"
            "" -> "Silent"
            else -> runCatching { RingtoneManager.getRingtone(context, Uri.parse(r))?.getTitle(context) }.getOrNull() ?: "Their own"
        }
    }
    val tone by produceState("Default", d.look.tone) {
        value = d.look.tone.takeIf { it.isNotBlank() }?.let { t -> runCatching { RingtoneManager.getRingtone(context, Uri.parse(t))?.getTitle(context) }.getOrNull() ?: "Their own" } ?: "Default"
    }
    if (d.readOnly) return
    InfoZone("On this phone") {
        InfoRow(AppIcons.Photo, "Poster", "How they fill the screen when they call", onClick = onPoster)
        InfoRow(AppIcons.Ringtone, ringtone, "Ringtone", onClick = onRingtone)
        InfoRow(AppIcons.Message, tone, "Sound of their messages", onClick = onTone)
        InfoRow(AppIcons.Vibration, d.look.vibration.ifBlank { "As the phone" }, "Vibration for their calls and messages", onClick = onVibration)
        InfoRow(AppIcons.Palette, if (d.look.color == 0 && d.look.letters.isBlank() && d.look.emoji.isBlank()) "Your wallpaper's colour" else "Their own", "Colour and monogram on their calls and messages", onClick = onColour, trailing = d.look.color.takeIf { it != 0 }?.let { c ->
            { Box(Modifier.padding(end = 12.dp).size(22.dp)) { androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) { drawCircle(Color(c)) } } }
        })
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).combinedClickableCompat { onVoicemail(!d.toVoicemail) }.padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)) {
            Icon(AppIcons.Voicemail, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text("Send their calls to voicemail", style = MaterialTheme.typography.bodyLarge)
                Text("The phone does not ring for them", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = d.toVoicemail, onCheckedChange = onVoicemail)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).combinedClickableCompat { onBypass(!d.look.bypass) }.padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)) {
            Icon(AppIcons.Call, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text("Ring even in Do Not Disturb", style = MaterialTheme.typography.bodyLarge)
                Text("Their calls ring when the phone is silent (in Dialer)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = d.look.bypass, onCheckedChange = onBypass)
        }
    }
}

/** The calls (Dialer), blocking (Dialer), where the contact is saved, separating, deleting. */
@Composable
private fun Elsewhere(d: Details, onDelete: () -> Unit, onSeparate: () -> Unit, onMove: () -> Unit) {
    val context = LocalContext.current
    val number = d.phones.firstOrNull()?.value?.takeIf { !android.provider.ContactsContract.isProfileId(d.id) }
    InfoZone("More") {
        if (number != null && Reach.canShowCalls(context)) InfoRow(AppIcons.History, "Calls with them", "In Dialer", onClick = { Reach.calls(context, number) })
        if (number != null) InfoRow(AppIcons.Block, "Block", if (Reach.canShowCalls(context)) "In Dialer, for calls and messages" else "Android's blocked numbers", onClick = { Reach.block(context, number) })
        val scope = rememberCoroutineScope()
        val accent = MaterialTheme.colorScheme.primary.toArgb()
        val haptics = rememberHaptics()
        InfoRow(AppIcons.Add, "Add to the home screen", null, onClick = {
            scope.launch { if (com.yaz.contacts.core.handoff.Shortcuts.pin(context, d, accent)) haptics.done() else haptics.reject() }
        })
        InfoRow(AppIcons.Account, d.accounts.joinToString(", ") { accountLabel(it) }, if (d.readOnly) "Saved in" else "Saved in · tap to move", onClick = if (d.readOnly || android.provider.ContactsContract.isProfileId(d.id)) null else onMove)
        if (d.raws.size > 1) InfoRow(AppIcons.PersonRemove, "Separate", "Back into ${d.raws.size} contacts", onClick = onSeparate)
        if (!d.readOnly) InfoRow(AppIcons.Delete, "Delete", null, onClick = onDelete, tint = AlertRed)
    }
}

/** The accounts a contact can move to: the others that take changes. */
@Composable
private fun MoveDialog(d: Details, onDismiss: () -> Unit, onPick: (Account) -> Unit) {
    val store: ContactStore = koinInject()
    val accounts by produceState(emptyList<Account>()) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.accounts() } }
    val others = accounts.filter { a -> d.accounts.none { it.key == a.key } }
    com.yaz.contacts.ui.component.ZoneAlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.Account, null) },
        title = { Text("Move ${d.display} to") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (others.isEmpty()) Text("There is no other account on this phone. Add one in Settings > Accounts.")
                others.forEach { a ->
                    ZoneSurface(shape = RoundedCornerShape(18.dp), onClick = { onPick(a) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                            Text(accountLabel(a), style = MaterialTheme.typography.titleMedium)
                            if (a.type == null) Text("Not backed up", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text("Its labels stay with the old account.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** "Google · name@gmail.com", "This phone". */
fun accountLabel(a: Account): String = when {
    a.type == null -> "This phone"
    a.type == "com.google" -> "Google · ${a.name}"
    a.type.contains("davdroid") || a.type.contains("davx5") -> "CardDAV · ${a.name}"
    a.type.contains("sim", ignoreCase = true) -> "SIM"
    else -> a.label
}

@Composable
fun InfoZone(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(start = 12.dp, top = 14.dp, bottom = 6.dp)
    )
    ZoneSurface(shape = RoundedCornerShape(24.dp), modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp), content = content)
    }
}

/** One value: a tap does what it is for, a long press copies it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InfoRow(
    icon: ImageVector,
    value: String,
    label: String?,
    onClick: (() -> Unit)?,
    copy: String? = null,
    tint: Color? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val undo = rememberUndo()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                enabled = onClick != null || copy != null,
                onClick = {
                    if (onClick != null) {
                        haptics.tick()
                        onClick()
                    }
                },
                onLongClick = copy?.let { text ->
                    {
                        haptics.firm()
                        Reach.copy(context, label ?: "Contact", text)
                        undo.show("Copied", null)
                    }
                }
            )
            .padding(start = 16.dp, end = if (trailing != null) 4.dp else 16.dp, top = 10.dp, bottom = 10.dp)
    ) {
        Icon(icon, null, tint = tint ?: MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(value, style = MaterialTheme.typography.bodyLarge, color = tint ?: MaterialTheme.colorScheme.onSurface)
            label?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing?.invoke()
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = this.then(Modifier.combinedClickable(onClick = onClick))

/** A contact with several numbers: which one, on a pane of glass. */
@Composable
fun NumberChoice(d: Details, title: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    com.yaz.contacts.ui.component.ZoneAlertDialog(
        onDismissRequest = onDismiss,
        icon = { ContactAvatar(d.display, d.thumbnail, 56.dp) },
        title = { Text("$title ${d.display}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                d.phones.distinctBy { it.value.filter(Char::isDigit).takeLast(9) }.forEach { p ->
                    ZoneSurface(shape = RoundedCornerShape(20.dp), onClick = { onPick(p.value) }, modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(Numbers.format(context, p.value), style = MaterialTheme.typography.titleMedium)
                                Text(Field.PHONE.label(context.resources, p.kind, p.custom), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(8.dp))
                            Icon(if (title == "Call") AppIcons.Call else AppIcons.Message, null, tint = if (title == "Call") AnswerGreen else MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
