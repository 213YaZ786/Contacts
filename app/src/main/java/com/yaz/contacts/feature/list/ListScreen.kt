package com.yaz.contacts.feature.list

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yaz.contacts.core.contacts.Contact
import com.yaz.contacts.core.contacts.Contacts
import com.yaz.contacts.core.dial.Numbers
import com.yaz.contacts.core.handoff.Reach
import com.yaz.contacts.data.contacts.ContactStore
import com.yaz.contacts.data.contacts.ContactWriter
import com.yaz.contacts.data.contacts.Trash
import com.yaz.contacts.data.settings.SettingsStore
import com.yaz.contacts.feature.common.DeleteQuestion
import com.yaz.contacts.feature.common.EvenRows
import com.yaz.contacts.feature.common.FaceTile
import com.yaz.contacts.feature.common.IconControl
import com.yaz.contacts.feature.common.TextControl
import com.yaz.contacts.feature.common.LineWidth
import com.yaz.contacts.feature.common.ListHeading
import com.yaz.contacts.feature.common.PersonLine
import com.yaz.contacts.feature.common.rememberUndo
import com.yaz.contacts.feature.main.TabFrame
import com.yaz.contacts.ui.component.EmptyZone
import com.yaz.contacts.ui.component.FloatingPane
import com.yaz.contacts.ui.component.LoadingMark
import com.yaz.contacts.ui.component.PillItem
import com.yaz.contacts.ui.component.PillMenu
import com.yaz.contacts.ui.component.PillMotion
import com.yaz.contacts.ui.component.SearchPill
import com.yaz.contacts.ui.component.rememberHaptics
import com.yaz.contacts.ui.component.rememberPillMenu
import com.yaz.contacts.ui.icon.AppIcons
import com.yaz.contacts.ui.theme.AlertRed
import com.yaz.contacts.ui.theme.AnswerGreen
import com.yaz.contacts.ui.theme.StarGold
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** What the list shows: everyone, the favourites, or one label. */
sealed interface Filter {
    data object All : Filter
    data object Favorites : Filter
    data object Recent : Filter
    data class Label(val id: Long) : Filter
    data object Private : Filter
}

/**
 * Everyone in the phone's contacts: the favourites as faces on top, then
 * A to Z under their letter, with the search and the filters floating in
 * glass. A tap opens a person, a long press offers the rest.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ListScreen(onOpen: (Long) -> Unit, onOpenPrivate: (String) -> Unit, onMakeMe: () -> Unit, onOpenSettings: () -> Unit, onOpenTidy: () -> Unit) {
    val store: ContactStore = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settings.collectAsState()
    val all by store.contacts.collectAsState()
    val groups by store.groups.collectAsState()
    val privateBook: com.yaz.contacts.data.contacts.PrivateBook = koinInject()
    val hidden by privateBook.people.collectAsState()
    // Read again on coming back: the permission may have been given meanwhile.
    var canRead by remember { mutableStateOf(store.canRead()) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        canRead = store.canRead()
        store.refresh()
        onPauseOrDispose { }
    }

    var query by rememberSaveable { mutableStateOf("") }
    var filterKey by rememberSaveable { mutableStateOf("all") }
    val filter: Filter = when {
        filterKey == "fav" -> Filter.Favorites
        filterKey == "recent" -> Filter.Recent
        filterKey == "private" -> Filter.Private
        filterKey.startsWith("label/") -> filterKey.removePrefix("label/").toLongOrNull()?.let { Filter.Label(it) } ?: Filter.All
        else -> Filter.All
    }
    val order = settings.sortOrder
    val people = remember(all, order, settings.shownAccounts, settings.onlyWithNumbers) {
        val list = all.orEmpty().let { list ->
            if (settings.shownAccounts.isEmpty()) list else list.filter { c -> c.accounts.any { it in settings.shownAccounts } }
        }.let { list -> if (settings.onlyWithNumbers) list.filter { it.phones.isNotEmpty() } else list }
        Contacts.sorted(list, order)
    }
    val filtered = remember(people, filter) {
        when (filter) {
            Filter.All -> people
            Filter.Favorites -> people.filter { it.starred }
            // Changed in the last 30 days, newest first.
            Filter.Recent -> people.filter { it.updated > System.currentTimeMillis() - 30L * 24 * 3600 * 1000 }.sortedByDescending { it.updated }
            is Filter.Label -> people.filter { filter.id in it.groups }
            Filter.Private -> emptyList()
        }
    }
    val shown = remember(filtered, query) { Contacts.search(filtered, query) }
    val favorites = remember(people) { people.filter { it.starred } }
    val usedLabels = remember(groups, people) { groups.filter { g -> people.any { g.id in it.groups } } }

    val changes by store.changes.collectAsState()
    // A picture of the contacts to undo changes from, once a day, when the app is opened.
    val snapshots: com.yaz.contacts.data.contacts.Snapshots = koinInject()
    LaunchedEffect(all != null) { if (all != null) snapshots.takeIfDue() }
    val me by androidx.compose.runtime.produceState<Contact?>(null, changes, canRead) { value = store.me() }
    val undo = rememberUndo()
    val trash: Trash = koinInject()
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<List<Contact>>(emptyList()) }
    // Several people chosen at once, by a long press then taps.
    var selectedIds by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    val selected = selectedIds.toSet()
    val selecting = selected.isNotEmpty()
    fun toggle(id: Long) {
        selectedIds = if (id in selected) selectedIds - id else selectedIds + id
    }
    androidx.activity.compose.BackHandler(enabled = selecting) { selectedIds = emptyList() }
    var labelling by remember { mutableStateOf(false) }
    var choosingLabel by remember { mutableStateOf(false) }
    val writer: ContactWriter = koinInject()
    val context = LocalContext.current
    val haptics = rememberHaptics()

    Box(Modifier.fillMaxSize()) {
        TabFrame(
            title = when (filter) {
                Filter.All -> "Contacts"
                Filter.Favorites -> "Favorites"
                Filter.Recent -> "Recent"
                Filter.Private -> "Private"
                is Filter.Label -> groups.firstOrNull { it.id == filter.id }?.title ?: "Contacts"
            },
            onOpenSettings = onOpenSettings,
            onOpenTidy = onOpenTidy,
            // In glass over the list, as Dialer's tabs.
            overlay = {
            // The sections in a floating pill at the bottom, as Dialer's tabs:
            // everyone, favourites, recent, private, labels.
            if (canRead && people.isNotEmpty() && !selecting) {
                val sections = buildList {
                    add(Triple("all", com.yaz.contacts.ui.component.DockItem(AppIcons.Contacts, "All"), filter == Filter.All))
                    if (favorites.isNotEmpty() || filter == Filter.Favorites) add(Triple("fav", com.yaz.contacts.ui.component.DockItem(AppIcons.Star, "Favorites"), filter == Filter.Favorites))
                    add(Triple("recent", com.yaz.contacts.ui.component.DockItem(AppIcons.History, "Recent"), filter == Filter.Recent))
                    if (hidden.isNotEmpty() || filter == Filter.Private) add(Triple("private", com.yaz.contacts.ui.component.DockItem(AppIcons.Lock, "Private"), filter == Filter.Private))
                    if (usedLabels.isNotEmpty()) add(Triple("labels", com.yaz.contacts.ui.component.DockItem(AppIcons.Label, "Labels"), filter is Filter.Label))
                }
                val at by androidx.compose.animation.core.animateFloatAsState(
                    sections.indexOfFirst { it.third }.coerceAtLeast(0).toFloat(),
                    androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 500f), label = "section"
                )
                com.yaz.contacts.ui.component.FloatingDock(
                    items = sections.map { it.second },
                    position = at,
                    onSelect = { i ->
                        val key = sections[i].first
                        if (key == "labels") choosingLabel = true else filterKey = key
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.navigationBars).padding(bottom = 16.dp)
                )
            }
            },
            controls = {
                if (selecting) {
                    val chosen = people.filter { it.id in selected }
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                        EvenRows(minSlot = 56.dp, modifier = Modifier.widthIn(max = 560.dp)) {
                            IconControl(AppIcons.Close, "Done choosing") { selectedIds = emptyList() }
                            TextControl("${chosen.size}", true) { selectedIds = if (selected.size == shown.size) emptyList() else shown.map { it.id } }
                            IconControl(AppIcons.Share, "Share") { Reach.share(context, chosen.map { it.lookup }, "${chosen.size} contacts") }
                            val numbers = chosen.mapNotNull { it.phones.firstOrNull() }
                            if (numbers.isNotEmpty() && Reach.canMessage(context)) IconControl(AppIcons.Message, "Message all") { Reach.message(context, numbers) }
                            IconControl(AppIcons.Label, "Add to a label") { labelling = true }
                            if (chosen.size >= 2) IconControl(AppIcons.Merge, "Merge into one") {
                                scope.launch {
                                    if (writer.merge(chosen.map { it.id })) {
                                        haptics.done()
                                        selectedIds = emptyList()
                                        undo.show("Merged into one", null)
                                    } else haptics.reject()
                                }
                            }
                            IconControl(AppIcons.Delete, "Delete", AlertRed) { deleting = chosen }
                        }
                    }
                } else if (canRead && people.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        SearchPill(
                            query, { query = it },
                            hint = "Search ${people.size} contacts",
                            modifier = Modifier.widthIn(max = 560.dp).weight(1f, fill = false).fillMaxWidth(),
                            floating = true
                        )
                    }
                }
            }
        ) { padding ->
            when {
                !canRead -> EmptyZone(
                    title = "Your contacts show here",
                    message = "Allow contacts above to see them.",
                    icon = AppIcons.Contacts,
                    modifier = Modifier.fillMaxSize().padding(padding)
                )
                filter == Filter.Private -> PrivateList(hidden, query, padding, onOpenPrivate)
                all == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingMark(size = 72.dp) }
                people.isEmpty() -> EmptyZone(
                    title = "No contacts yet",
                    message = "Add someone with the round button, or bring a file of contacts in from Tidy up.",
                    icon = AppIcons.PersonAdd,
                    modifier = Modifier.fillMaxSize().padding(padding)
                )
                shown.isEmpty() -> EmptyZone(
                    title = "No one found",
                    message = if (query.isNotBlank()) "No name, number or email matches \"$query\"." else "No one here yet.",
                    icon = AppIcons.Search,
                    modifier = Modifier.fillMaxSize().padding(padding)
                )
                else -> PeopleList(
                    shown = shown,
                    favorites = if (query.isBlank() && filter == Filter.All) favorites else emptyList(),
                    birthdays = if (query.isBlank() && filter == Filter.All) Contacts.birthdays(people, java.time.LocalDate.now()) else emptyList(),
                    grouped = filter != Filter.Recent,
                    lastFirst = settings.lastNameFirst,
                    me = if (query.isBlank() && filter == Filter.All && !selecting) (me ?: NO_CARD) else null,
                    onMe = { me?.let { onOpen(it.id) } ?: onMakeMe() },
                    order = order,
                    padding = padding,
                    onOpen = { id -> if (selecting) toggle(id) else onOpen(id) },
                    onDelete = { deleting = listOf(it) },
                    selected = selected,
                    onSelect = { id ->
                        haptics.firm()
                        toggle(id)
                    }
                )
            }
        }
    }

    if (choosingLabel) com.yaz.contacts.ui.component.ZoneAlertDialog(
        onDismissRequest = { choosingLabel = false },
        icon = { androidx.compose.material3.Icon(AppIcons.Label, null) },
        title = { Text("Labels") },
        text = {
            EvenRows(minSlot = 120.dp) {
                usedLabels.forEach { g ->
                    TextControl(g.title, (filter as? Filter.Label)?.id == g.id) {
                        filterKey = "label/${g.id}"
                        choosingLabel = false
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { choosingLabel = false }) { Text("Close") } }
    )
    if (deleting.isNotEmpty()) {
        val those = deleting
        val what = if (those.size == 1) those[0].name.ifBlank { "this contact" } else "${those.size} contacts"
        DeleteQuestion(what, settings.trashDays, onDismiss = { deleting = emptyList() }) {
            deleting = emptyList()
            selectedIds = emptyList()
            scope.launch {
                val gone = mutableListOf<Long>()
                for (c in those) {
                    val details = store.details(c.id) ?: continue
                    if (trash.delete(details, details.accounts.firstOrNull())) gone += details.id
                }
                if (gone.isEmpty()) return@launch
                undo.show(if (gone.size == 1) "${those[0].name.ifBlank { "Contact" }} deleted" else "${gone.size} contacts deleted") {
                    scope.launch {
                        val accounts = store.accounts()
                        trash.entries.value.filter { it.details.id in gone }.forEach { trash.restore(it, accounts) }
                    }
                }
            }
        }
    }
    if (labelling) {
        LabelChoice(groups = groups, onDismiss = { labelling = false }) { group ->
            labelling = false
            scope.launch {
                if (writer.setInGroup(group, selected, true)) {
                    haptics.done()
                    undo.show("Added to the label", null)
                    selectedIds = emptyList()
                }
            }
        }
    }
}

/** The label to put the chosen people in. */
@Composable
private fun LabelChoice(groups: List<com.yaz.contacts.core.contacts.Group>, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    com.yaz.contacts.ui.component.ZoneAlertDialog(
        onDismissRequest = onDismiss,
        icon = { androidx.compose.material3.Icon(AppIcons.Label, null) },
        title = { Text("Add to a label") },
        text = {
            if (groups.isEmpty()) Text("No label yet: make one in Tidy up.")
            else androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.forEach { g ->
                    com.yaz.contacts.ui.component.ZoneSurface(shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), onClick = { onPick(g.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text(g.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun PeopleList(
    shown: List<Contact>,
    favorites: List<Contact>,
    birthdays: List<Triple<Contact, Long, Int?>>,
    grouped: Boolean,
    lastFirst: Boolean,
    me: Contact?,
    onMe: () -> Unit,
    order: com.yaz.contacts.core.contacts.SortOrder,
    padding: PaddingValues,
    onOpen: (Long) -> Unit,
    onDelete: (Contact) -> Unit,
    selected: Set<Long>,
    onSelect: (Long) -> Unit
) {
    // Recent ones keep their order, newest first, under one heading.
    val groups = remember(shown, order, grouped) { if (grouped) shown.groupBy { Contacts.bucketOf(it, order) } else mapOf("Changed lately" to shown) }
    val rail = grouped && shown.size >= RAIL_FROM
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = if (rail) 40.dp else 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // The user's own card first, to share in a tap.
            if (me != null) item(key = "me") {
                PersonLine(
                    name = if (me === NO_CARD) "My card" else me.name.ifBlank { "My card" },
                    photo = me.photo,
                    subtitle = if (me === NO_CARD) "Make yours, to share it as a QR code" else "My card",
                    starred = false,
                    onOpen = onMe
                )
            }
            if (me != null) item(key = "shared") { SharedWithYou(shown, onOpen) }
            if (grouped && me != null) {
                item(key = "touch") { KeepInTouch(shown, onOpen) }
                item(key = "often") {
                    val save = LocalSave.current
                    TalkedOften(shown, onSave = { number -> save.value?.invoke(number) })
                }
            }
            if (birthdays.isNotEmpty()) {
                item(key = "bday") { BirthdayCard(birthdays, onOpen) }
            }
            if (favorites.isNotEmpty()) {
                item(key = "fav/title") { ListHeading("Favorites") }
                item(key = "fav/tiles") {
                    EvenRows(minSlot = 76.dp, gap = 4.dp, modifier = Modifier.widthIn(max = LineWidth)) {
                        favorites.forEach { c ->
                            val menu = rememberPillMenu()
                            Box(menu.tracker) {
                                FaceTile(c.name, c.photo, onOpen = { onOpen(c.id) }, onLongPress = { if (selected.isEmpty()) menu.open() else onSelect(c.id) }, look = c.look)
                                ContactMenu(menu, c, onDelete, onSelect)
                            }
                        }
                    }
                }
            }
            groups.forEach { (letter, group) ->
                item(key = "letter/$letter") { ListHeading(letter) }
                items(group, key = { "c/${it.id}" }) { c ->
                    val menu = rememberPillMenu()
                    Box(Modifier.animateItem().then(menu.tracker)) {
                        PersonLine(
                            name = Contacts.shown(c, lastFirst),
                            photo = c.photo,
                            subtitle = c.company ?: c.phones.firstOrNull()?.let { Numbers.format(context, it) } ?: c.emails.firstOrNull(),
                            starred = c.starred,
                            onOpen = { onOpen(c.id) },
                            selected = c.id in selected,
                            look = c.look,
                            onLongPress = { if (selected.isEmpty()) menu.open() else onSelect(c.id) }
                        )
                        ContactMenu(menu, c, onDelete, onSelect)
                    }
                }
            }
            item(key = "count") {
                Text(
                    if (shown.size == 1) "1 contact" else "${shown.size} contacts",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                )
            }
        }
        if (rail) {
            val favCount = 1 + (if (grouped && me != null) 2 else 0) + (if (favorites.isNotEmpty()) 2 else 0) + (if (birthdays.isNotEmpty()) 1 else 0) + (if (me != null) 1 else 0)
            LetterRail(
                letters = groups.keys.toList(),
                onLetter = { letter ->
                    var at = favCount
                    for ((l, group) in groups) {
                        if (l == letter) break
                        at += 1 + group.size
                    }
                    scope.launch { list.scrollToItem(at) }
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding(), end = 6.dp)
            )
        }
    }
}

/** The long press menu of a person, two columns of tiles: call, write, star, share, choose, delete. */
@Composable
private fun ContactMenu(menu: com.yaz.contacts.ui.component.PillMenuState, c: Contact, onDelete: (Contact) -> Unit, onSelect: (Long) -> Unit) {
    val context = LocalContext.current
    val writer: ContactWriter = koinInject()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    PillMenu(
        menu,
        listOfNotNull(
            c.phones.firstOrNull()?.let { number -> PillItem(AppIcons.Call, "Call", PillMotion.BOUNCE, AnswerGreen) { Reach.call(context, number) } },
            c.phones.firstOrNull()?.takeIf { Reach.canMessage(context) }?.let { number -> PillItem(AppIcons.Message, "Message", PillMotion.WIGGLE) { Reach.message(context, listOf(number)) } },
            if (c.phones.isEmpty()) c.emails.firstOrNull()?.let { email -> PillItem(AppIcons.Email, "Email", PillMotion.WIGGLE) { Reach.email(context, listOf(email)) } } else null,
            PillItem(if (c.starred) AppIcons.StarOutline else AppIcons.Star, if (c.starred) "Unfavorite" else "Favorite", PillMotion.BOUNCE, StarGold) {
                haptics.toggle(!c.starred)
                scope.launch { writer.star(c.id, !c.starred) }
            },
            PillItem(AppIcons.Share, "Share", PillMotion.BOUNCE) { Reach.share(context, listOf(c.lookup), c.name) },
            PillItem(AppIcons.CheckCircle, "Select", PillMotion.BOUNCE) { onSelect(c.id) },
            PillItem(AppIcons.Delete, "Delete", PillMotion.DROP, AlertRed) { onDelete(c) }
        ),
        columns = 2
    )
}

/**
 * Cards people shared through the encrypted chat, on top of the list until
 * applied or ignored on their page; one from someone not saved opens as a
 * new contact to save.
 */
@Composable
private fun SharedWithYou(people: List<Contact>, onOpen: (Long) -> Unit) {
    val offers: com.yaz.contacts.data.contacts.Offers = koinInject()
    val all by offers.offers.collectAsState()
    if (all.isEmpty()) return
    val drafts: com.yaz.contacts.core.handoff.Drafts = koinInject()
    val nav = LocalImport.current
    com.yaz.contacts.ui.component.ZoneSurface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        accent = true,
        modifier = Modifier.widthIn(max = LineWidth).fillMaxWidth()
    ) {
        androidx.compose.foundation.layout.Column(Modifier.padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)) {
                androidx.compose.material3.Icon(AppIcons.Update, null)
                Text("Shared with you", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 10.dp))
            }
            all.forEach { offer ->
                val key = com.yaz.contacts.data.contacts.Offers.key(offer.number)
                val who = people.firstOrNull { c -> c.phones.any { com.yaz.contacts.data.contacts.Offers.key(it) == key } }
                val title = who?.name ?: com.yaz.contacts.core.vcard.VCard.parse(offer.card, max = 1).firstOrNull()?.title ?: offer.number
                val haptics = rememberHaptics()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable {
                        haptics.tick()
                        if (who != null) onOpen(who.id) else nav(drafts.put(offer.card))
                    }.padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    com.yaz.contacts.ui.component.ContactAvatar(title, who?.photo, 36.dp, look = who?.look?.forAvatar())
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(if (who != null) "Their card changed" else "Not in your contacts yet", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** Saves a number as a new contact (or into one); given by the app's navigation. */
val LocalSave = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.MutableState<((String) -> Unit)?>> { androidx.compose.runtime.mutableStateOf(null) }

/** Opens the import screen for cards held in Drafts; given by the app's navigation. */
val LocalImport = androidx.compose.runtime.staticCompositionLocalOf<(Long) -> Unit> { {} }

/**
 * Birthdays today and in the week, on top of the list: a word to them goes
 * through the messaging app, a call through the phone app.
 */
@Composable
private fun BirthdayCard(birthdays: List<Triple<Contact, Long, Int?>>, onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    com.yaz.contacts.ui.component.ZoneSurface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        modifier = Modifier.widthIn(max = LineWidth).fillMaxWidth()
    ) {
        androidx.compose.foundation.layout.Column(Modifier.padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)) {
                androidx.compose.material3.Icon(AppIcons.Cake, null, tint = MaterialTheme.colorScheme.primary)
                Text("Birthdays", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 10.dp))
            }
            birthdays.forEach { (c, wait, age) ->
                val haptics = rememberHaptics()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { haptics.tick(); onOpen(c.id) }.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
                ) {
                    com.yaz.contacts.ui.component.ContactAvatar(c.name, c.photo, 36.dp, look = c.look?.forAvatar())
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(c.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(
                            when (wait) {
                                0L -> "Today"
                                1L -> "Tomorrow"
                                else -> "In $wait days"
                            } + (age?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (wait == 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    c.phones.firstOrNull()?.let { number ->
                        if (Reach.canMessage(context)) androidx.compose.material3.IconButton(onClick = { haptics.tick(); Reach.message(context, listOf(number)) }) {
                            androidx.compose.material3.Icon(AppIcons.Message, "Write to ${c.name}", tint = MaterialTheme.colorScheme.primary)
                        }
                        androidx.compose.material3.IconButton(onClick = { haptics.firm(); Reach.call(context, number) }) {
                            androidx.compose.material3.Icon(AppIcons.Call, "Call ${c.name}", tint = AnswerGreen)
                        }
                    }
                }
            }
        }
    }
}

/** Stands for the user's card before they made one. */
private val NO_CARD = Contact(-1L, "", "", "", null, false)

/** From this many contacts, the letters stand at the edge of the list. */
private const val RAIL_FROM = 12


/** The people kept private, found by name, number or email. */
@Composable
private fun PrivateList(people: List<com.yaz.contacts.data.contacts.PrivateBook.Person>, query: String, padding: androidx.compose.foundation.layout.PaddingValues, onOpen: (String) -> Unit) {
    val q = query.trim().lowercase()
    val digits = q.filter(Char::isDigit)
    val shown = remember(people, q) {
        if (q.isBlank()) people else people.filter { p ->
            val d = p.details
            d.display.lowercase().contains(q) ||
                d.emails.any { it.value.lowercase().contains(q) } ||
                (digits.length >= 3 && d.phones.any { it.value.filter(Char::isDigit).contains(digits) })
        }
    }
    if (shown.isEmpty()) {
        EmptyZone(
            title = if (people.isEmpty()) "No one private" else "No one found",
            message = if (people.isEmpty()) "Keep someone private from their page: they stay only here, sealed." else "No private contact matches \"$query\".",
            icon = AppIcons.Lock,
            modifier = Modifier.fillMaxSize().padding(padding)
        )
        return
    }
    androidx.compose.foundation.lazy.LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 96.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize()
    ) {
        items(shown.size, key = { shown[it].id }) { i ->
            val p = shown[i]
            PersonLine(
                name = p.details.display,
                photo = null,
                subtitle = p.details.phones.firstOrNull()?.value ?: p.details.emails.firstOrNull()?.value,
                starred = false,
                onOpen = { onOpen(p.id) },
                look = p.details.look,
                modifier = Modifier.animateItem()
            )
        }
    }
}
