package com.yaz.contacts.feature.edit

import android.graphics.Bitmap
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Relation
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yaz.contacts.core.contacts.Account
import com.yaz.contacts.core.contacts.Address
import com.yaz.contacts.core.contacts.Dates
import com.yaz.contacts.core.contacts.Details
import com.yaz.contacts.core.contacts.EventDate
import com.yaz.contacts.core.contacts.Field
import com.yaz.contacts.core.contacts.Labelled
import com.yaz.contacts.data.contacts.ContactStore
import com.yaz.contacts.data.contacts.ContactWriter
import com.yaz.contacts.data.settings.SettingsStore
import com.yaz.contacts.feature.contact.accountLabel
import com.yaz.contacts.ui.component.ContactAvatar
import com.yaz.contacts.ui.component.FloatingAction
import com.yaz.contacts.ui.component.FloatingFrame
import com.yaz.contacts.ui.component.FloatingPane
import com.yaz.contacts.ui.component.FloatingTop
import com.yaz.contacts.ui.component.LoadingMark
import com.yaz.contacts.ui.component.ZoneAlertDialog
import com.yaz.contacts.ui.component.ZoneSurface
import com.yaz.contacts.ui.component.rememberHaptics
import com.yaz.contacts.ui.icon.AppIcons
import com.yaz.contacts.ui.theme.AlertRed
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Making or changing a contact, every field Android keeps, in the order
 * people fill them. Opened by the app or by another one with fields
 * already filled; nothing is written until Save, and only what changed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(contactId: Long?, prefill: Details?, onClose: () -> Unit, onSaved: (ContactWriter.Saved) -> Unit, me: Boolean = false) {
    val store: ContactStore = koinInject()
    val writer: ContactWriter = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()

    var original by remember { mutableStateOf<Details?>(null) }
    var draft by remember { mutableStateOf<Details?>(null) }
    var accounts by remember { mutableStateOf<List<Account>>(emptyList()) }
    var account by remember { mutableStateOf<Account?>(null) }
    LaunchedEffect(contactId) {
        accounts = store.accounts()
        val saved = settingsStore.current.defaultAccount
        // A new contact goes where the user said, else to the first synced account, so it is backed up.
        account = accounts.firstOrNull { it.key == saved } ?: accounts.firstOrNull { it.type != null } ?: accounts.firstOrNull()
        val base = contactId?.let { store.details(it) }
        original = base
        // Rows of accounts that take no changes stay out of the editor, untouched.
        draft = merge(base?.let { withoutLocked(it) } ?: Details(), prefill)
    }

    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var removePhoto by remember { mutableStateOf(false) }
    var cropping by remember { mutableStateOf<Bitmap?>(null) }
    var saving by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var choosingAccount by remember { mutableStateOf(false) }
    var more by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val bitmap = com.yaz.contacts.core.security.SafeImages.decode(context, uri)
            if (bitmap == null) haptics.reject() else cropping = bitmap
        }
    }

    val d = draft
    val changed = d != null && (d != (original?.let { withoutLocked(it) } ?: Details()) || photo != null || removePhoto)
    fun close() {
        if (changed) leaving = true else onClose()
    }
    BackHandler(enabled = changed) { leaving = true }

    fun save() {
        val edited = draft ?: return
        if (saving) return
        if (isBlank(edited) && photo == null) {
            haptics.reject()
            return
        }
        saving = true
        scope.launch {
            val saved = writer.save(original, edited, account, photo, removePhoto, me = me || (contactId != null && android.provider.ContactsContract.isProfileId(contactId)))
            saving = false
            if (saved == null) haptics.reject() else {
                haptics.done()
                onSaved(saved)
            }
        }
    }

    FloatingFrame(
        bottom = 24.dp,
        top = {
            FloatingTop(
                title = when {
                    me && contactId == null -> "My card"
                    contactId == null -> "New contact"
                    else -> "Edit contact"
                },
                leading = { FloatingAction(AppIcons.Close, "Cancel", ::close) },
                trailing = { FloatingAction(AppIcons.Done, "Save", ::save, tint = if (changed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            )
        }
    ) { padding ->
        if (d == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingMark(size = 72.dp) }
            return@FloatingFrame
        }
        fun edit(change: (Details) -> Details) {
            draft = change(draft ?: return)
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(padding.calculateTopPadding() + 8.dp))
            // The photo: tap to choose one, the Photo Picker asks no permission.
            Box(contentAlignment = Alignment.BottomEnd, modifier = Modifier.clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) {
                haptics.tick()
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) {
                val shown = photo
                if (shown != null) Image(shown.asImageBitmap(), "Photo", contentScale = ContentScale.Crop, modifier = Modifier.size(120.dp).clip(CircleShape))
                else ContactAvatar(d.name.display(), if (removePhoto) null else d.photo ?: d.thumbnail, 120.dp)
                ZoneSurface(shape = CircleShape, accent = true, modifier = Modifier.size(38.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(AppIcons.AddPhoto, "Choose a photo", modifier = Modifier.size(20.dp)) }
                }
            }
            if ((photo != null || (!removePhoto && d.photo != null))) {
                TextButton(onClick = {
                    photo = null
                    removePhoto = original?.photo != null
                }) { Text("Remove photo") }
            }
            Spacer(Modifier.height(8.dp))
            // Where a new contact is saved; an existing one stays where it is.
            if (original == null && accounts.size > 1 && !me) {
                FloatingPane(shape = CircleShape, onClick = { choosingAccount = true }) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)) {
                        Icon(AppIcons.Account, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Saved in " + (account?.let(::accountLabel) ?: "This phone"), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            Group("Name") {
                if (more) Input(d.name.prefix, "Title (Dr, Mrs)") { v -> edit { it.copy(name = it.name.copy(prefix = v)) } }
                Input(d.name.given, "First name", capital = true) { v -> edit { it.copy(name = it.name.copy(given = v)) } }
                if (more) Input(d.name.middle, "Middle name", capital = true) { v -> edit { it.copy(name = it.name.copy(middle = v)) } }
                Input(d.name.family, "Last name", capital = true) { v -> edit { it.copy(name = it.name.copy(family = v)) } }
                if (more) {
                    Input(d.name.suffix, "Suffix (Jr, III)") { v -> edit { it.copy(name = it.name.copy(suffix = v)) } }
                    Input(d.name.phoneticGiven, "First name as it sounds") { v -> edit { it.copy(name = it.name.copy(phoneticGiven = v)) } }
                    Input(d.name.phoneticMiddle, "Middle name as it sounds") { v -> edit { it.copy(name = it.name.copy(phoneticMiddle = v)) } }
                    Input(d.name.phoneticFamily, "Last name as it sounds") { v -> edit { it.copy(name = it.name.copy(phoneticFamily = v)) } }
                    Input(d.nickname?.value.orEmpty(), "Nickname", capital = true) { v -> edit { it.copy(nickname = (it.nickname ?: Labelled(kind = 1, value = "")).copy(value = v)) } }
                    Input(d.look.pronouns, "Pronouns (she/her, he/him, they/them…)") { v -> edit { it.copy(look = it.look.copy(pronouns = v.take(40))) } }
                }
            }
            Group("Work") {
                Input(d.organization.company, "Company", capital = true) { v -> edit { it.copy(organization = it.organization.copy(company = v)) } }
                Input(d.organization.title, "Job title", capital = true) { v -> edit { it.copy(organization = it.organization.copy(title = v)) } }
                if (more) Input(d.organization.department, "Department", capital = true) { v -> edit { it.copy(organization = it.organization.copy(department = v)) } }
            }
            Rows("Phone", Field.PHONE, d.phones, KeyboardType.Phone, newKind = { if (it.isEmpty()) Phone.TYPE_MOBILE else Phone.TYPE_HOME }) { list -> edit { it.copy(phones = list) } }
            Rows("Email", Field.EMAIL, d.emails, KeyboardType.Email, newKind = { if (it.isEmpty()) Email.TYPE_HOME else Email.TYPE_WORK }) { list -> edit { it.copy(emails = list) } }
            Addresses(d.addresses) { list -> edit { it.copy(addresses = list) } }
            DatesGroup(d.events) { list -> edit { it.copy(events = list) } }
            if (more || d.websites.isNotEmpty()) Rows("Website", Field.WEBSITE, d.websites, KeyboardType.Uri, newKind = { Website.TYPE_HOMEPAGE }) { list -> edit { it.copy(websites = list) } }
            if (more || d.relations.isNotEmpty()) Rows("Relationship", Field.RELATION, d.relations, KeyboardType.Text, newKind = { Relation.TYPE_SPOUSE }, capital = true) { list -> edit { it.copy(relations = list) } }
            if (more || d.sips.isNotEmpty()) Rows("SIP", Field.SIP, d.sips, KeyboardType.Email, newKind = { android.provider.ContactsContract.CommonDataKinds.SipAddress.TYPE_HOME }) { list -> edit { it.copy(sips = list) } }
            Labels(d.groups, account?.key ?: original?.accounts?.firstOrNull()?.key ?: "") { set -> edit { it.copy(groups = set) } }
            Group("Notes") {
                Input(d.note?.value.orEmpty(), "Notes", single = false, capital = true) { v -> edit { it.copy(note = (it.note ?: Labelled(kind = 0, value = "")).copy(value = v)) } }
            }
            if (!more) {
                TextButton(onClick = {
                    haptics.tick()
                    more = true
                }, modifier = Modifier.padding(top = 8.dp)) { Text("More fields") }
            }
            Spacer(Modifier.height(32.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }

    cropping?.let { bitmap ->
        CropDialog(bitmap, onDismiss = { cropping = null }) { framed ->
            cropping = null
            photo = framed
            removePhoto = false
        }
    }
    if (choosingAccount) {
        ZoneAlertDialog(
            onDismissRequest = { choosingAccount = false },
            icon = { Icon(AppIcons.Account, null) },
            title = { Text("Save in") },
            text = {
                Column {
                    accounts.forEach { a ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                                account = a
                                choosingAccount = false
                            }.padding(vertical = 10.dp, horizontal = 4.dp)
                        ) {
                            RadioButton(selected = a.key == account?.key, onClick = null)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(accountLabel(a))
                                if (a.type == null) Text("Not backed up", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingAccount = false }) { Text("Cancel") } }
        )
    }
    if (leaving) {
        ZoneAlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text("Leave without saving?") },
            text = { Text("Your changes will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    leaving = false
                    onClose()
                }) { Text("Leave", color = AlertRed) }
            },
            dismissButton = { TextButton(onClick = { leaving = false }) { Text("Keep editing") } }
        )
    }
}

private fun withoutLocked(d: Details): Details {
    val locked = d.readOnlyRaws
    if (locked.isEmpty()) return d
    return d.copy(
        phones = d.phones.filter { it.rawId !in locked },
        emails = d.emails.filter { it.rawId !in locked },
        addresses = d.addresses.filter { it.rawId !in locked },
        websites = d.websites.filter { it.rawId !in locked },
        events = d.events.filter { it.rawId !in locked },
        relations = d.relations.filter { it.rawId !in locked },
        messengers = d.messengers.filter { it.rawId !in locked },
        sips = d.sips.filter { it.rawId !in locked }
    )
}

/** Nothing worth a contact: no name, company, number, email or address. */
private fun isBlank(d: Details) = d.name.isEmpty && d.organization.company.isBlank() &&
    d.phones.none { it.value.isNotBlank() } && d.emails.none { it.value.isNotBlank() } && d.addresses.all { it.isEmpty } &&
    d.nickname?.value.isNullOrBlank()

/**
 * What another app gave, added to a contact: its name only when the contact
 * has none, its numbers and addresses only when not there already.
 */
fun merge(base: Details, add: Details?): Details {
    add ?: return base
    fun digits(s: String) = s.filter(Char::isDigit).takeLast(9)
    return base.copy(
        name = if (base.name.isEmpty) add.name else base.name,
        organization = if (base.organization.isEmpty) add.organization else base.organization,
        nickname = base.nickname ?: add.nickname,
        note = base.note ?: add.note,
        phones = base.phones + add.phones.filter { p -> base.phones.none { digits(it.value) == digits(p.value) } },
        emails = base.emails + add.emails.filter { e -> base.emails.none { it.value.equals(e.value, true) } },
        addresses = base.addresses + add.addresses,
        websites = base.websites + add.websites.filter { w -> base.websites.none { it.value == w.value } },
        events = base.events + add.events,
        relations = base.relations + add.relations,
        messengers = base.messengers + add.messengers,
        sips = base.sips + add.sips
    )
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(start = 12.dp, top = 16.dp, bottom = 6.dp)
    )
    ZoneSurface(shape = RoundedCornerShape(24.dp), modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun Input(
    value: String,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    keyboard: KeyboardType = KeyboardType.Text,
    single: Boolean = true,
    capital: Boolean = false,
    focus: Boolean = false,
    onChange: (String) -> Unit
) {
    // A row just added takes the keyboard at once.
    val requester = remember { androidx.compose.ui.focus.FocusRequester() }
    if (focus) LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(if (single) 300 else 5000)) },
        label = { Text(label) },
        singleLine = single,
        minLines = if (single) 1 else 3,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, capitalization = if (capital) KeyboardCapitalization.Words else KeyboardCapitalization.None),
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        ),
        modifier = modifier.focusRequester(requester)
    )
}

/** A kind with a label and a value, many of it: a label pill, the field, a way to take it out. */
@Composable
private fun Rows(
    title: String,
    field: Field,
    items: List<Labelled>,
    keyboard: KeyboardType,
    newKind: (List<Labelled>) -> Int,
    capital: Boolean = false,
    onChange: (List<Labelled>) -> Unit
) {
    val haptics = rememberHaptics()
    val context = LocalContext.current
    var labelling by remember { mutableStateOf<Int?>(null) }
    var added by remember { mutableStateOf(-1) }
    Group(title) {
        items.forEachIndexed { i, item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Input(item.value, field.label(context.resources, item.kind, item.custom), Modifier.weight(1f), keyboard, capital = capital, focus = i == added) { v ->
                    onChange(items.toMutableList().also { it[i] = item.copy(value = v) })
                }
                IconButton(onClick = { labelling = i }) { Icon(AppIcons.Label, "Label") }
                IconButton(onClick = {
                    haptics.tick()
                    onChange(items.toMutableList().also { it.removeAt(i) })
                }) { Icon(AppIcons.Close, "Remove") }
            }
        }
        AddRow("Add ${title.lowercase()}") {
            haptics.tick()
            added = items.size
            onChange(items + Labelled(kind = newKind(items), value = ""))
        }
    }
    labelling?.let { i ->
        val item = items.getOrNull(i) ?: return@let
        KindDialog(field, item.kind, item.custom, onDismiss = { labelling = null }) { kind, custom ->
            labelling = null
            onChange(items.toMutableList().also { it[i] = item.copy(kind = kind, custom = custom) })
        }
    }
}

@Composable
private fun AddRow(label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp)
    ) {
        Icon(AppIcons.Add, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    }
}

/** Android's kinds in the phone's language, or the user's own word. */
@Composable
private fun KindDialog(field: Field, kind: Int, custom: String?, onDismiss: () -> Unit, onPick: (Int, String?) -> Unit) {
    val context = LocalContext.current
    var own by remember { mutableStateOf(if (kind == field.custom) custom.orEmpty() else "") }
    ZoneAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Label") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                field.kinds.forEach { k ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(k, null) }.padding(vertical = 8.dp, horizontal = 4.dp)
                    ) {
                        RadioButton(selected = k == kind, onClick = null)
                        Text(field.label(context.resources, k, null), modifier = Modifier.padding(start = 12.dp))
                    }
                }
                Input(own, "Your own label") { own = it.take(40) }
            }
        },
        confirmButton = { TextButton(onClick = { if (own.isNotBlank()) onPick(field.custom, own.trim()) else onDismiss() }) { Text(if (own.isNotBlank()) "Use" else "Cancel") } }
    )
}

@Composable
private fun Addresses(items: List<Address>, onChange: (List<Address>) -> Unit) {
    val haptics = rememberHaptics()
    val context = LocalContext.current
    var labelling by remember { mutableStateOf<Int?>(null) }
    var added by remember { mutableStateOf(-1) }
    Group("Address") {
        items.forEachIndexed { i, a ->
            fun set(change: (Address) -> Address) = onChange(items.toMutableList().also { it[i] = change(a) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Field.POSTAL.label(context.resources, a.kind, a.custom), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f).padding(start = 4.dp))
                IconButton(onClick = { labelling = i }) { Icon(AppIcons.Label, "Label") }
                IconButton(onClick = {
                    haptics.tick()
                    onChange(items.toMutableList().also { it.removeAt(i) })
                }) { Icon(AppIcons.Close, "Remove") }
            }
            Input(a.street, "Street", single = false, capital = true, focus = i == added) { v -> set { it.copy(street = v) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Input(a.postcode, "Postcode", Modifier.weight(0.4f)) { v -> set { it.copy(postcode = v) } }
                Input(a.city, "City", Modifier.weight(0.6f), capital = true) { v -> set { it.copy(city = v) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Input(a.region, "Region", Modifier.weight(1f), capital = true) { v -> set { it.copy(region = v) } }
                Input(a.country, "Country", Modifier.weight(1f), capital = true) { v -> set { it.copy(country = v) } }
            }
        }
        AddRow("Add address") {
            haptics.tick()
            added = items.size
            onChange(items + Address(kind = if (items.isEmpty()) StructuredPostal.TYPE_HOME else StructuredPostal.TYPE_WORK))
        }
    }
    labelling?.let { i ->
        val a = items.getOrNull(i) ?: return@let
        KindDialog(Field.POSTAL, a.kind, a.custom, onDismiss = { labelling = null }) { kind, custom ->
            labelling = null
            onChange(items.toMutableList().also { it[i] = a.copy(kind = kind, custom = custom) })
        }
    }
}

/** Birthdays and other dates, picked on a calendar; the year may be left out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatesGroup(items: List<Labelled>, onChange: (List<Labelled>) -> Unit) {
    val haptics = rememberHaptics()
    val context = LocalContext.current
    var picking by remember { mutableStateOf<Int?>(null) }
    var labelling by remember { mutableStateOf<Int?>(null) }
    Group("Dates") {
        items.forEachIndexed { i, e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable { picking = i }.padding(horizontal = 8.dp, vertical = 8.dp)) {
                    Text(Field.EVENT.label(context.resources, e.kind, e.custom), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Dates.parse(e.value)?.let { Dates.spoken(it) } ?: "Choose a date", style = MaterialTheme.typography.bodyLarge)
                }
                IconButton(onClick = { labelling = i }) { Icon(AppIcons.Label, "Label") }
                IconButton(onClick = {
                    haptics.tick()
                    onChange(items.toMutableList().also { it.removeAt(i) })
                }) { Icon(AppIcons.Close, "Remove") }
            }
        }
        AddRow(if (items.none { it.kind == Event.TYPE_BIRTHDAY }) "Add birthday" else "Add date") {
            haptics.tick()
            val kind = if (items.none { it.kind == Event.TYPE_BIRTHDAY }) Event.TYPE_BIRTHDAY else Event.TYPE_ANNIVERSARY
            onChange(items + Labelled(kind = kind, value = ""))
            picking = items.size
        }
    }
    picking?.let { i ->
        val e = items.getOrNull(i) ?: return@let
        val current = Dates.parse(e.value)
        val state = rememberDatePickerState(
            initialSelectedDateMillis = current?.let { LocalDate.of(it.year ?: 2000, it.month, it.day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
        )
        var noYear by remember { mutableStateOf(current != null && current.year == null) }
        ZoneAlertDialog(
            onDismissRequest = { picking = null },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DatePicker(state = state, showModeToggle = true, title = null, headline = null)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { noYear = !noYear }) {
                        Checkbox(checked = noYear, onCheckedChange = { noYear = it })
                        Text("Without the year")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val millis = state.selectedDateMillis
                    picking = null
                    if (millis != null) {
                        val day = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        val date = EventDate(if (noYear) null else day.year, day.monthValue, day.dayOfMonth)
                        onChange(items.toMutableList().also { it[i] = e.copy(value = date.stored()) })
                    }
                }) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } }
        )
    }
    labelling?.let { i ->
        val e = items.getOrNull(i) ?: return@let
        KindDialog(Field.EVENT, e.kind, e.custom, onDismiss = { labelling = null }) { kind, custom ->
            labelling = null
            onChange(items.toMutableList().also { it[i] = e.copy(kind = kind, custom = custom) })
        }
    }
}

/** The labels of the account the contact is in, ticked on and off. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Labels(chosen: Set<Long>, account: String, onChange: (Set<Long>) -> Unit) {
    val store: ContactStore = koinInject()
    val groups by store.groups.collectAsState()
    val haptics = rememberHaptics()
    val mine = groups.filter { it.account == account }
    if (mine.isEmpty()) return
    Group("Labels") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            mine.forEach { g ->
                val on = g.id in chosen
                ZoneSurface(shape = CircleShape, accent = on, onClick = {
                    haptics.toggle(!on)
                    onChange(if (on) chosen - g.id else chosen + g.id)
                }) {
                    Text(g.title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
        }
    }
}

