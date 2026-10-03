package com.contacts.app.feature.list

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
import com.contacts.app.core.contacts.Contact
import com.contacts.app.core.contacts.Contacts
import com.contacts.app.core.dial.Numbers
import com.contacts.app.core.handoff.Reach
import com.contacts.app.data.contacts.ContactStore
import com.contacts.app.data.contacts.ContactWriter
import com.contacts.app.data.contacts.Trash
import com.contacts.app.data.settings.SettingsStore
import com.contacts.app.feature.common.DeleteQuestion
import com.contacts.app.feature.common.EvenRows
import com.contacts.app.feature.common.FaceTile
import com.contacts.app.feature.common.IconControl
import com.contacts.app.feature.common.TextControl
import com.contacts.app.feature.common.LineWidth
import com.contacts.app.feature.common.ListHeading
import com.contacts.app.feature.common.PersonLine
import com.contacts.app.feature.common.rememberUndo
import com.contacts.app.feature.main.TabFrame
import com.contacts.app.ui.component.EmptyZone
import com.contacts.app.ui.component.FloatingPane
import com.contacts.app.ui.component.LoadingMark
import com.contacts.app.ui.component.PillItem
import com.contacts.app.ui.component.PillMenu
import com.contacts.app.ui.component.PillMotion
import com.contacts.app.ui.component.SearchPill
import com.contacts.app.ui.component.rememberHaptics
import com.contacts.app.ui.component.rememberPillMenu
import com.contacts.app.ui.icon.AppIcons
import com.contacts.app.ui.theme.AlertRed
import com.contacts.app.ui.theme.AnswerGreen
import com.contacts.app.ui.theme.StarGold
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** What the list shows: everyone, the favourites, or one label. */
sealed interface Filter {
    data object All : Filter
    data object Favorites : Filter
    data object Recent : Filter
    data class Label(val id: Long) : Filter
}

/**
 * Everyone in the phone's contacts: the favourites as faces on top, then
 * A to Z under their letter, with the search and the filters floating in
 * glass. A tap opens a person, a long press offers the rest.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ListScreen(onOpen: (Long) -> Unit, onMakeMe: () -> Unit, onOpenSettings: () -> Unit, onOpenTidy: () -> Unit) {
    val store: ContactStore = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settings.collectAsState()
    val all by store.contacts.collectAsState()
    val groups by store.groups.collectAsState()
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
        filterKey.startsWith("label/") -> filterKey.removePrefix("label/").toLongOrNull()?.let { Filter.Label(it) } ?: Filter.All
        else -> Filter.All
    }
    val order = settings.sortOrder
    val people = remember(all, order, settings.shownAccounts) {
        val list = all.orEmpty().let { list ->
            if (settings.shownAccounts.isEmpty()) list else list.filter { c -> c.accounts.any { it in settings.shownAccounts } }
        }
        Contacts.sorted(list, order)
    }
    val filtered = remember(people, filter) {
        when (filter) {
            Filter.All -> people
            Filter.Favorites -> people.filter { it.starred }
            // Changed in the last 30 days, newest first.
            Filter.Recent -> people.filter { it.updated > System.currentTimeMillis() - 30L * 24 * 3600 * 1000 }.sortedByDescending { it.updated }
            is Filter.Label -> people.filter { filter.id in it.groups }
        }
    }
    val shown = remember(filtered, query) { Contacts.search(filtered, query) }
    val favorites = remember(people) { people.filter { it.starred } }
    val usedLabels = remember(groups, people) { groups.filter { g -> people.any { g.id in it.groups } } }

    val changes by store.changes.collectAsState()
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
    val writer: ContactWriter = koinInject()
    val context = LocalContext.current
    val haptics = rememberHaptics()

    Box(Modifier.fillMaxSize()) {
        TabFrame(
            title = "Contacts",
            onOpenSettings = onOpenSettings,
            onOpenTidy = onOpenTidy,
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
                    // Filters wrap onto a second line rather than scroll sideways.
                    run {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                            EvenRows(minSlot = 96.dp, modifier = Modifier.widthIn(max = 560.dp).animateContentSize()) {
                                TextControl("All", filter == Filter.All) { filterKey = "all" }
                                if (favorites.isNotEmpty()) TextControl("Favorites", filter == Filter.Favorites) { filterKey = "fav" }
                                TextControl("Recent", filter == Filter.Recent) { filterKey = "recent" }
                                usedLabels.forEach { g -> TextControl(g.title, (filter as? Filter.Label)?.id == g.id) { filterKey = "label/${g.id}" } }
                            }
                        }
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
private fun LabelChoice(groups: List<com.contacts.app.core.contacts.Group>, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    com.contacts.app.ui.component.ZoneAlertDialog(
        onDismissRequest = onDismiss,
        icon = { androidx.compose.material3.Icon(AppIcons.Label, null) },
        title = { Text("Add to a label") },
        text = {
            if (groups.isEmpty()) Text("No label yet: make one in Tidy up.")
            else androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.forEach { g ->
                    com.contacts.app.ui.component.ZoneSurface(shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), onClick = { onPick(g.id) }, modifier = Modifier.fillMaxWidth()) {
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
    me: Contact?,
    onMe: () -> Unit,
    order: com.contacts.app.core.contacts.SortOrder,
    padding: PaddingValues,
    onOpen: (Long) -> Unit,
    onDelete: (Contact) -> Unit,
    selected: Set<Long>,
    onSelect: (Long) -> Unit
) {
    // Recent ones keep their order, newest first, under one heading.
    val groups = remember(shown, order, grouped) { if (grouped) shown.groupBy { Contacts.initialOf(Contacts.shown(it, order)) } else mapOf("Changed lately" to shown) }
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
                                FaceTile(c.name, c.photo, onOpen = { onOpen(c.id) }, onLongPress = { if (selected.isEmpty()) menu.open() else onSelect(c.id) })
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
                            name = Contacts.shown(c, order),
                            photo = c.photo,
                            subtitle = c.company ?: c.phones.firstOrNull()?.let { Numbers.format(context, it) } ?: c.emails.firstOrNull(),
                            starred = c.starred,
                            onOpen = { onOpen(c.id) },
                            selected = c.id in selected,
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
            val favCount = (if (favorites.isNotEmpty()) 2 else 0) + (if (birthdays.isNotEmpty()) 1 else 0) + (if (me != null) 1 else 0)
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

/** The long press pill of a person: call, write, star, share, delete. */
@Composable
private fun ContactMenu(menu: com.contacts.app.ui.component.PillMenuState, c: Contact, onDelete: (Contact) -> Unit, onSelect: (Long) -> Unit) {
    val context = LocalContext.current
    val writer: ContactWriter = koinInject()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    PillMenu(
        menu,
        listOfNotNull(
            c.phones.firstOrNull()?.let { number -> PillItem(AppIcons.Call, "Call", PillMotion.BOUNCE, AnswerGreen) { Reach.call(context, number) } },
            c.phones.firstOrNull()?.takeIf { Reach.canMessage(context) }?.let { number -> PillItem(AppIcons.Message, "Send a message", PillMotion.WIGGLE) { Reach.message(context, listOf(number)) } },
            if (c.phones.isEmpty()) c.emails.firstOrNull()?.let { email -> PillItem(AppIcons.Email, "Email", PillMotion.WIGGLE) { Reach.email(context, listOf(email)) } } else null,
            PillItem(if (c.starred) AppIcons.StarOutline else AppIcons.Star, if (c.starred) "Remove from favorites" else "Add to favorites", PillMotion.BOUNCE, StarGold) {
                haptics.toggle(!c.starred)
                scope.launch { writer.star(c.id, !c.starred) }
            },
            PillItem(AppIcons.Share, "Share", PillMotion.BOUNCE) { Reach.share(context, listOf(c.lookup), c.name) },
            PillItem(AppIcons.CheckCircle, "Choose several", PillMotion.BOUNCE) { onSelect(c.id) },
            PillItem(AppIcons.Delete, "Delete", PillMotion.DROP, AlertRed) { onDelete(c) }
        )
    )
}

/**
 * Birthdays today and in the week, on top of the list: a word to them goes
 * through the messaging app, a call through the phone app.
 */
@Composable
private fun BirthdayCard(birthdays: List<Triple<Contact, Long, Int?>>, onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    com.contacts.app.ui.component.ZoneSurface(
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
                    com.contacts.app.ui.component.ContactAvatar(c.name, c.photo, 36.dp)
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

