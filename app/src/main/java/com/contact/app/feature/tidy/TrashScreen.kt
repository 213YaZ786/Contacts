package com.contact.app.feature.tidy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.contact.app.data.contacts.ContactStore
import com.contact.app.data.contacts.Trash
import com.contact.app.data.settings.SettingsStore
import com.contact.app.feature.common.PersonLine
import com.contact.app.feature.common.rememberUndo
import com.contact.app.ui.component.EmptyZone
import com.contact.app.ui.component.FloatingAction
import com.contact.app.ui.component.FloatingFrame
import com.contact.app.ui.component.FloatingTop
import com.contact.app.ui.component.ZoneAlertDialog
import com.contact.app.ui.component.rememberHaptics
import com.contact.app.ui.icon.AppIcons
import com.contact.app.ui.theme.AlertRed
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Deleted contacts while they wait: each can come back as it was, or go for good. */
@Composable
fun TrashScreen(onBack: () -> Unit, onRestored: (Long) -> Unit) {
    val trash: Trash = koinInject()
    val store: ContactStore = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settings.collectAsState()
    val entries by trash.entries.collectAsState()
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val undo = rememberUndo()
    var emptying by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { trash.load(settings.trashDays) }

    FloatingFrame(
        bottom = 24.dp,
        top = {
            FloatingTop(
                title = "Trash",
                leading = { FloatingAction(AppIcons.ArrowBack, "Back", onBack) },
                trailing = { if (entries.isNotEmpty()) FloatingAction(AppIcons.Delete, "Empty the trash", { emptying = true }, tint = AlertRed) }
            )
        }
    ) { padding ->
        if (entries.isEmpty()) {
            EmptyZone(title = "The trash is empty", message = "Deleted contacts wait here ${settings.trashDays} days.", icon = AppIcons.DeleteOutline, modifier = Modifier.fillMaxSize().padding(padding))
            return@FloatingFrame
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            items(entries, key = { it.id }) { e ->
                Box(Modifier.animateItem()) {
                    PersonLine(
                        name = e.name.ifBlank { "No name" },
                        photo = null,
                        subtitle = "Deleted " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(e.deletedAt)),
                        starred = false,
                        onOpen = {},
                        trailing = {
                            Row {
                                TextButton(onClick = {
                                    scope.launch {
                                        val saved = trash.restore(e, store.accounts())
                                        if (saved != null) {
                                            haptics.done()
                                            undo.show("${e.name} is back", null)
                                            onRestored(saved.contactId)
                                        } else haptics.reject()
                                    }
                                }) { Text("Restore") }
                                IconButton(onClick = {
                                    haptics.reject()
                                    scope.launch { trash.forget(e) }
                                }) { Icon(AppIcons.Close, "Delete for good") }
                            }
                        }
                    )
                }
            }
        }
    }
    if (emptying) {
        ZoneAlertDialog(
            onDismissRequest = { emptying = false },
            title = { Text("Empty the trash?") },
            text = { Text("${entries.size} contacts will be gone for good.") },
            confirmButton = {
                TextButton(onClick = {
                    emptying = false
                    haptics.reject()
                    scope.launch { trash.empty() }
                }) { Text("Empty", color = AlertRed) }
            },
            dismissButton = { TextButton(onClick = { emptying = false }) { Text("Cancel") } }
        )
    }
}

