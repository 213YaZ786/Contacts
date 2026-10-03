package com.yaz.contacts.feature.tidy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yaz.contacts.core.contacts.Snapshot
import com.yaz.contacts.data.contacts.Snapshots
import com.yaz.contacts.data.contacts.Trash
import com.yaz.contacts.feature.common.PersonLine
import com.yaz.contacts.feature.common.rememberUndo
import com.yaz.contacts.ui.component.EmptyZone
import com.yaz.contacts.ui.component.FloatingAction
import com.yaz.contacts.ui.component.FloatingFrame
import com.yaz.contacts.ui.component.FloatingTop
import com.yaz.contacts.ui.component.LoadingMark
import com.yaz.contacts.ui.component.ZoneAlertDialog
import com.yaz.contacts.ui.component.rememberHaptics
import com.yaz.contacts.ui.icon.AppIcons
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * The contacts as they were on a past day, up to 30 days back: what going
 * back would bring back, change back and take away is shown before
 * anything is done; the state of now is kept, so it can be undone too.
 */
@Composable
fun UndoScreen(onBack: () -> Unit) {
    val snapshots: Snapshots = koinInject()
    val trash: Trash = koinInject()
    val haptics = rememberHaptics()
    val undo = rememberUndo()
    val scope = rememberCoroutineScope()
    val times = remember { snapshots.times() }
    var asking by remember { mutableStateOf<Pair<Snapshots.Kept, Snapshot.Plan>?>(null) }
    var reading by remember { mutableStateOf<Long?>(null) }
    var working by remember { mutableStateOf(false) }

    FloatingFrame(
        bottom = 24.dp,
        top = { FloatingTop(title = "Undo changes", leading = { FloatingAction(AppIcons.ArrowBack, "Back", onBack) }) }
    ) { padding ->
        if (times.isEmpty()) {
            EmptyZone(
                title = "Nothing to go back to yet",
                message = "Each day you open Contacts, it keeps how your contacts are, up to 30 days.",
                icon = AppIcons.History,
                modifier = Modifier.fillMaxSize().padding(padding)
            )
            return@FloatingFrame
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                Text(
                    "Go back to how your contacts were on one of these days.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp)
                )
            }
            items(times, key = { it }) { at ->
                PersonLine(
                    name = DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT).format(Date(at)),
                    photo = null,
                    subtitle = if (reading == at) "Comparing with now…" else null,
                    starred = false,
                    onOpen = {
                        if (reading != null) return@PersonLine
                        reading = at
                        scope.launch {
                            val kept = snapshots.open(at)
                            val plan = kept?.let { snapshots.compare(it) }
                            reading = null
                            if (kept == null || plan == null) haptics.reject() else asking = kept to plan
                        }
                    }
                )
            }
        }
    }

    asking?.let { (kept, plan) ->
        var removeAdded by remember { mutableStateOf(true) }
        ZoneAlertDialog(
            onDismissRequest = { if (!working) asking = null },
            title = { Text(DateFormat.getDateInstance(DateFormat.LONG).format(Date(kept.at))) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (plan.isEmpty) Text("Your contacts are the same as then.")
                    else {
                        if (plan.back.isNotEmpty()) Text("${plan.back.size} come back: " + plan.back.take(4).joinToString(", ") { it.display.ifBlank { it.name.display() } } + if (plan.back.size > 4) "…" else "")
                        if (plan.changed.isNotEmpty()) Text("${plan.changed.size} are changed back: " + plan.changed.take(4).joinToString(", ") { it.second.display } + if (plan.changed.size > 4) "…" else "")
                        if (plan.added.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Checkbox(checked = removeAdded, onCheckedChange = { removeAdded = it })
                            Text("Also put the ${plan.added.size} added since in the trash")
                        }
                        Text("How they are now is kept, so this can be undone too.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (working) LoadingMark(size = 40.dp)
                }
            },
            confirmButton = {
                if (!plan.isEmpty) TextButton(enabled = !working, onClick = {
                    working = true
                    scope.launch {
                        val done = snapshots.restore(kept, plan, trash, removeAdded)
                        working = false
                        asking = null
                        haptics.done()
                        undo.show("$done contacts back as they were", null)
                    }
                }) { Text("Go back") }
            },
            dismissButton = { TextButton(enabled = !working, onClick = { asking = null }) { Text("Cancel") } }
        )
    }
}
