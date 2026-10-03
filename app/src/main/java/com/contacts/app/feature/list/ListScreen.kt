package com.contacts.app.feature.list

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
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
import com.contacts.app.feature.common.FaceTile
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
    data class Label(val id: Long) : Filter
}

/**
 * Everyone in the phone's contacts: the favourites as faces on top, then
 * A to Z under their letter, with the search and the filters floating in
 * glass. A tap opens a person, a long press offers the rest.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ListScreen(onOpen: (Long) -> Unit, onOpenSettings: () -> Unit, onOpenTidy: () -> Unit) {
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
            is Filter.Label -> people.filter { filter.id in it.groups }
        }
    }
    val shown = remember(filtered, query) { Contacts.search(filtered, query) }
    val favorites = remember(people) { people.filter { it.starred } }
    val usedLabels = remember(groups, people) { groups.filter { g -> people.any { g.id in it.groups } } }

    val undo = rememberUndo()
    val trash: Trash = koinInject()
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<Contact?>(null) }

    Box(Modifier.fillMaxSize()) {
        TabFrame(
            title = "Contacts",
            onOpenSettings = onOpenSettings,
            onOpenTidy = onOpenTidy,
            controls = {
                if (canRead && people.isNotEmpty()) {
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
                    if (favorites.isNotEmpty() || usedLabels.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).animateContentSize()
                        ) {
                            FilterPill("All", filter == Filter.All) { filterKey = "all" }
                            if (favorites.isNotEmpty()) FilterPill("Favorites", filter == Filter.Favorites) { filterKey = "fav" }
                            usedLabels.forEach { g -> FilterPill(g.title, (filter as? Filter.Label)?.id == g.id) { filterKey = "label/${g.id}" } }
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
                    order = order,
                    padding = padding,
                    onOpen = onOpen,
                    onDelete = { deleting = it }
                )
            }
        }
    }

    deleting?.let { contact ->
        DeleteQuestion(contact.name.ifBlank { "this contact" }, settings.trashDays, onDismiss = { deleting = null }) {
            deleting = null
            scope.launch {
                val details = store.details(contact.id) ?: return@launch
                val account = details.accounts.firstOrNull()
                if (trash.delete(details, account)) {
                    undo.show("${contact.name.ifBlank { "Contact" }} deleted") {
                        scope.launch {
                            trash.entries.value.firstOrNull { it.details.id == details.id }?.let { trash.restore(it, store.accounts()) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun PeopleList(
    shown: List<Contact>,
    favorites: List<Contact>,
    order: com.contacts.app.core.contacts.SortOrder,
    padding: PaddingValues,
    onOpen: (Long) -> Unit,
    onDelete: (Contact) -> Unit
) {
    val groups = remember(shown, order) { shown.groupBy { Contacts.initialOf(Contacts.shown(it, order)) } }
    val rail = shown.size >= RAIL_FROM
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
            if (favorites.isNotEmpty()) {
                item(key = "fav/title") { ListHeading("Favorites") }
                item(key = "fav/tiles") {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.widthIn(max = LineWidth).fillMaxWidth()
                    ) {
                        favorites.forEach { c ->
                            val menu = rememberPillMenu()
                            Box(menu.tracker) {
                                FaceTile(c.name, c.photo, onOpen = { onOpen(c.id) }, onLongPress = menu::open)
                                ContactMenu(menu, c, onDelete)
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
                            onLongPress = menu::open
                        )
                        ContactMenu(menu, c, onDelete)
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
            val favCount = if (favorites.isNotEmpty()) 2 else 0
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
private fun ContactMenu(menu: com.contacts.app.ui.component.PillMenuState, c: Contact, onDelete: (Contact) -> Unit) {
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
            PillItem(AppIcons.Delete, "Delete", PillMotion.DROP, AlertRed) { onDelete(c) }
        )
    )
}

@Composable
private fun FilterPill(label: String, chosen: Boolean, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    FloatingPane(shape = CircleShape, accent = chosen, onClick = {
        haptics.tick()
        onClick()
    }) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
    }
}

/** From this many contacts, the letters stand at the edge of the list. */
private const val RAIL_FROM = 12

