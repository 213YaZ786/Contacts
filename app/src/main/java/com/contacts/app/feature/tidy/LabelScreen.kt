package com.contacts.app.feature.tidy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import com.contacts.app.core.contacts.Contacts
import com.contacts.app.core.dial.Numbers
import com.contacts.app.core.handoff.Reach
import com.contacts.app.data.contacts.ContactStore
import com.contacts.app.data.contacts.ContactWriter
import com.contacts.app.data.settings.SettingsStore
import com.contacts.app.feature.common.PersonLine
import com.contacts.app.ui.component.EmptyZone
import com.contacts.app.ui.component.FloatingAction
import com.contacts.app.ui.component.FloatingFrame
import com.contacts.app.ui.component.FloatingTop
import com.contacts.app.feature.common.ActionTile
import com.contacts.app.feature.common.EvenRows
import com.contacts.app.ui.component.SearchPill
import com.contacts.app.ui.component.ZoneAlertDialog
import com.contacts.app.ui.component.rememberHaptics
import com.contacts.app.ui.icon.AppIcons
import com.contacts.app.ui.theme.AlertRed
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * One label: the people in it, a way to write to all of them at once in
 * the messaging app or by mail, to add or take out people, rename or
 * delete it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LabelScreen(id: Long, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val store: ContactStore = koinInject()
    val writer: ContactWriter = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settings.collectAsState()
    val all by store.contacts.collectAsState()
    val groups by store.groups.collectAsState()
    LaunchedEffect(Unit) { store.refresh() }
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val label = groups.firstOrNull { it.id == id }
    val members = remember(all, id, settings.sortOrder) { Contacts.sorted(all.orEmpty().filter { id in it.groups }, settings.sortOrder) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    FloatingFrame(
        bottom = 24.dp,
        top = {
            FloatingTop(
                title = label?.title ?: "Label",
                leading = { FloatingAction(AppIcons.ArrowBack, "Back", onBack) },
                trailing = { FloatingAction(AppIcons.Edit, "Rename", { renaming = true }) }
            )
        }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "actions") {
                EvenRows(minSlot = 72.dp, modifier = Modifier.widthIn(max = 640.dp).padding(bottom = 8.dp)) {
                    val numbers = members.mapNotNull { it.phones.firstOrNull() }
                    val emails = members.mapNotNull { it.emails.firstOrNull() }
                    // Everyone at once: a group conversation in the messaging app.
                    if (numbers.isNotEmpty() && Reach.canMessage(context)) ActionTile(AppIcons.Message, "Message all") { Reach.message(context, numbers) }
                    if (emails.isNotEmpty() && Reach.canEmail(context)) ActionTile(AppIcons.Email, "Email all") { Reach.email(context, emails) }
                    ActionTile(AppIcons.PersonAdd, "Add people") { adding = true }
                    ActionTile(AppIcons.Delete, "Delete label", AlertRed) { deleting = true }
                }
            }
            if (members.isEmpty()) item { EmptyZone(title = "No one here yet", message = "Add people to this label.", icon = AppIcons.Label) }
            items(members, key = { it.id }) { c ->
                PersonLine(
                    name = Contacts.shown(c, settings.sortOrder),
                    photo = c.photo,
                    subtitle = c.phones.firstOrNull()?.let { Numbers.format(context, it) } ?: c.emails.firstOrNull(),
                    starred = c.starred,
                    onOpen = { onOpen(c.id) },
                    trailing = {
                        IconButton(onClick = {
                            haptics.tick()
                            scope.launch { writer.setInGroup(id, listOf(c.id), false) }
                        }) { Icon(AppIcons.PersonRemove, "Take out of the label") }
                    }
                )
            }
        }
    }

    if (adding) {
        var query by remember { mutableStateOf("") }
        var picked by remember { mutableStateOf<Set<Long>>(emptySet()) }
        val others = remember(all, query) { Contacts.search(Contacts.sorted(all.orEmpty().filter { id !in it.groups }, settings.sortOrder), query) }
        ZoneAlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add to ${label?.title ?: "the label"}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchPill(query, { query = it }, hint = "Search")
                    LazyColumn(Modifier.widthIn(max = 520.dp).heightIn(max = 380.dp).fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(others, key = { it.id }) { c ->
                            PersonLine(
                                name = c.name, photo = c.photo, subtitle = null, starred = false, selected = c.id in picked,
                                onOpen = {
                                    haptics.toggle(c.id !in picked)
                                    picked = if (c.id in picked) picked - c.id else picked + c.id
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    adding = false
                    if (picked.isNotEmpty()) scope.launch { if (writer.setInGroup(id, picked, true)) haptics.done() }
                }) { Text(if (picked.isEmpty()) "Cancel" else "Add ${picked.size}") }
            }
        )
    }
    if (renaming) {
        var title by remember { mutableStateOf(label?.title.orEmpty()) }
        ZoneAlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename") },
            text = { OutlinedTextField(title, { title = it.take(60) }, singleLine = true, shape = RoundedCornerShape(16.dp)) },
            confirmButton = {
                TextButton(onClick = {
                    renaming = false
                    if (title.isNotBlank()) scope.launch { writer.renameGroup(id, title) }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } }
        )
    }
    if (deleting) {
        ZoneAlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete ${label?.title ?: "this label"}?") },
            text = { Text("The people in it stay in your contacts.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    scope.launch {
                        if (writer.deleteGroup(id)) {
                            haptics.reject()
                            onBack()
                        }
                    }
                }) { Text("Delete", color = AlertRed) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } }
        )
    }
}
