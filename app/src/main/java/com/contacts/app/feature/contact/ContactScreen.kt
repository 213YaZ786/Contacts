package com.contacts.app.feature.contact

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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.contacts.app.core.contacts.Account
import com.contacts.app.core.contacts.Dates
import com.contacts.app.core.contacts.Details
import com.contacts.app.core.contacts.Field
import com.contacts.app.core.dial.Numbers
import com.contacts.app.core.handoff.Reach
import com.contacts.app.data.contacts.ContactStore
import com.contacts.app.data.contacts.ContactWriter
import com.contacts.app.data.contacts.Trash
import com.contacts.app.data.settings.SettingsStore
import com.contacts.app.feature.common.DeleteQuestion
import com.contacts.app.feature.common.rememberUndo
import com.contacts.app.ui.component.ContactAvatar
import com.contacts.app.ui.component.EmptyZone
import com.contacts.app.ui.component.FloatingAction
import com.contacts.app.ui.component.FloatingFrame
import com.contacts.app.ui.component.FloatingTop
import com.contacts.app.ui.component.HeroGlow
import com.contacts.app.ui.component.LoadingMark
import com.contacts.app.ui.component.RoundAction
import com.contacts.app.ui.component.ZoneSurface
import com.contacts.app.ui.component.rememberHaptics
import com.contacts.app.ui.icon.AppIcons
import com.contacts.app.ui.theme.AlertRed
import com.contacts.app.ui.theme.AnswerGreen
import com.contacts.app.ui.theme.StarGold
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
fun ContactScreen(id: Long, onBack: () -> Unit, onEdit: (Long) -> Unit, onDeleted: () -> Unit) {
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

    val ringtonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val picked: Uri? = if (Build.VERSION.SDK_INT >= 33) result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        else @Suppress("DEPRECATION") result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        // The default sound is no ringtone of their own.
        val value = picked?.takeIf { it != Settings.System.DEFAULT_RINGTONE_URI }?.toString()
        scope.launch { writer.setRingtone(id, value) }
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
                HeroGlow(d.photo ?: d.thumbnail, 380.dp)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
                ) {
                    Spacer(Modifier.height(padding.calculateTopPadding() + 8.dp))
                    Header(d)
                    Spacer(Modifier.height(20.dp))
                    Actions(d, onQr = { showQr = true })
                    Spacer(Modifier.height(16.dp))
                    Fields(d)
                    OnThisPhone(
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
                        onVibration = { choosingVibration = true }
                    )
                    Elsewhere(d, onDelete = { deleting = true }, onSeparate = {
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
    if (choosingColour) ColourDialog(d.look.color, onDismiss = { choosingColour = false }) { colour ->
        choosingColour = false
        scope.launch { writer.save(d, d.copy(look = d.look.copy(color = colour)), null) }
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
        ContactAvatar(d.display, d.photo ?: d.thumbnail, 132.dp)
    }
    Spacer(Modifier.height(14.dp))
    Text(d.display.ifBlank { "No name" }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    val phonetic = listOf(d.name.phoneticGiven, d.name.phoneticMiddle, d.name.phoneticFamily).filter { it.isNotBlank() }.joinToString(" ")
    if (phonetic.isNotBlank()) Text(phonetic, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    d.nickname?.value?.let { Text("“$it”", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    val work = listOf(d.organization.title, d.organization.department, d.organization.company).filter { it.isNotBlank() }.joinToString(" · ")
    if (work.isNotBlank()) Text(work, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}

/** The ways to reach them, each a round pane of glass, wrapping onto a second line on a narrow screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(d: Details, onQr: () -> Unit) {
    val context = LocalContext.current
    val writer: ContactWriter = koinInject()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var choosing by remember { mutableStateOf<String?>(null) }
    var sharing by remember { mutableStateOf(false) }
    val numbers = d.phones.map { it.value }.distinctBy { it.filter(Char::isDigit).takeLast(9) }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (numbers.isNotEmpty()) RoundAction(AppIcons.Call, "Call", AnswerGreen) {
            if (numbers.size == 1) Reach.call(context, numbers[0]) else choosing = "call"
        }
        if (numbers.isNotEmpty() && Reach.canMessage(context)) RoundAction(AppIcons.Message, "Message") {
            if (numbers.size == 1) Reach.message(context, numbers) else choosing = "message"
        }
        if (d.emails.isNotEmpty() && Reach.canEmail(context)) RoundAction(AppIcons.Email, "Email") {
            Reach.email(context, listOf(d.emails.first().value))
        }
        RoundAction(if (d.starred) AppIcons.Star else AppIcons.StarOutline, "Favorite", StarGold) {
            haptics.toggle(!d.starred)
            scope.launch { writer.star(d.id, !d.starred) }
        }
        RoundAction(AppIcons.Share, "Share") { sharing = true }
    }
    if (sharing) {
        com.contacts.app.ui.component.ZoneAlertDialog(
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
private fun OnThisPhone(d: Details, onRingtone: () -> Unit, onVoicemail: (Boolean) -> Unit, onColour: () -> Unit, onVibration: () -> Unit) {
    val context = LocalContext.current
    val ringtone by produceState("Default", d.ringtone) {
        value = when (val r = d.ringtone) {
            null -> "Default"
            "" -> "Silent"
            else -> runCatching { RingtoneManager.getRingtone(context, Uri.parse(r))?.getTitle(context) }.getOrNull() ?: "Their own"
        }
    }
    if (d.readOnly) return
    InfoZone("On this phone") {
        InfoRow(AppIcons.Ringtone, ringtone, "Ringtone", onClick = onRingtone)
        InfoRow(AppIcons.Vibration, d.look.vibration.ifBlank { "As the phone" }, "Vibration for their calls and messages", onClick = onVibration)
        InfoRow(AppIcons.Palette, if (d.look.color == 0) "Your wallpaper's colour" else "Their own colour", "Colour on their calls and messages", onClick = onColour, trailing = d.look.color.takeIf { it != 0 }?.let { c ->
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
    }
}

/** The calls (Dialer), blocking (Dialer), where the contact is saved, separating, deleting. */
@Composable
private fun Elsewhere(d: Details, onDelete: () -> Unit, onSeparate: () -> Unit) {
    val context = LocalContext.current
    val number = d.phones.firstOrNull()?.value
    InfoZone("More") {
        if (number != null && Reach.canShowCalls(context)) InfoRow(AppIcons.History, "Calls with them", "In Dialer", onClick = { Reach.calls(context, number) })
        if (number != null) InfoRow(AppIcons.Block, "Block", if (Reach.canShowCalls(context)) "In Dialer, for calls and messages" else "Android's blocked numbers", onClick = { Reach.block(context, number) })
        InfoRow(AppIcons.Account, d.accounts.joinToString(", ") { accountLabel(it) }, "Saved in", onClick = null)
        if (d.raws.size > 1) InfoRow(AppIcons.PersonRemove, "Separate", "Back into ${d.raws.size} contacts", onClick = onSeparate)
        if (!d.readOnly) InfoRow(AppIcons.Delete, "Delete", null, onClick = onDelete, tint = AlertRed)
    }
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
    com.contacts.app.ui.component.ZoneAlertDialog(
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
