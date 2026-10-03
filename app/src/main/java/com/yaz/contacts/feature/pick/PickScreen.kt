package com.yaz.contacts.feature.pick

import android.content.ContentUris
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yaz.contacts.core.contacts.Contact
import com.yaz.contacts.core.contacts.Contacts
import com.yaz.contacts.core.contacts.Field
import com.yaz.contacts.core.dial.Numbers
import com.yaz.contacts.core.handoff.PickKind
import com.yaz.contacts.data.contacts.ContactStore
import com.yaz.contacts.data.settings.SettingsStore
import com.yaz.contacts.feature.common.PersonLine
import com.yaz.contacts.feature.main.SetupZone
import com.yaz.contacts.ui.component.EmptyZone
import com.yaz.contacts.ui.component.FloatingAction
import com.yaz.contacts.ui.component.FloatingFrame
import com.yaz.contacts.ui.component.FloatingTop
import com.yaz.contacts.ui.component.LoadingMark
import com.yaz.contacts.ui.component.SearchPill
import com.yaz.contacts.ui.component.ZoneAlertDialog
import com.yaz.contacts.ui.component.ZoneSurface
import com.yaz.contacts.ui.component.rememberHaptics
import com.yaz.contacts.ui.icon.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/** One row another app may receive: a number, an address, a postal address, with its label. */
data class PickRow(val uri: Uri, val value: String, val label: String)

/**
 * Choosing someone for another app: only those who have what it asked
 * for, and only the one row picked goes back to it.
 */
@Composable
fun PickScreen(kind: PickKind, title: String, onClose: () -> Unit, onPicked: (Uri) -> Unit) {
    val store: ContactStore = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settings.collectAsState()
    val all by store.contacts.collectAsState()
    LaunchedEffect(Unit) { store.refresh() }
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var query by rememberSaveable { mutableStateOf("") }
    var choosing by remember { mutableStateOf<Contact?>(null) }

    val withKind = remember(all, kind) {
        all?.let { list ->
            Contacts.sorted(
                when (kind) {
                    PickKind.CONTACT -> list
                    PickKind.PHONE -> list.filter { it.phones.isNotEmpty() }
                    PickKind.EMAIL -> list.filter { it.emails.isNotEmpty() }
                    PickKind.POSTAL -> list
                },
                settings.sortOrder
            )
        }
    }
    val shown = remember(withKind, query) { withKind?.let { Contacts.search(it, query) } }

    FloatingFrame(
        bottom = 24.dp,
        top = {
            FloatingTop(title = title, leading = { FloatingAction(AppIcons.Close, "Cancel", onClose) })
            Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.TopCenter) { SetupZone() }
            Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                SearchPill(query, { query = it }, hint = "Search", modifier = Modifier.widthIn(max = 560.dp).weight(1f, fill = false).fillMaxWidth(), floating = true)
            }
        }
    ) { padding ->
        val list = shown
        when {
            !store.canRead() -> Unit
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingMark(size = 72.dp) }
            list.isEmpty() -> EmptyZone(title = "No one to pick", message = "No contact has what was asked for.", icon = AppIcons.Search, modifier = Modifier.fillMaxSize().padding(padding))
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize()
            ) {
                items(list, key = { it.id }) { c ->
                    PersonLine(
                        name = Contacts.shown(c, settings.sortOrder),
                        photo = c.photo,
                        subtitle = when (kind) {
                            PickKind.PHONE -> c.phones.firstOrNull()?.let { Numbers.format(context, it) } + if (c.phones.size > 1) " +${c.phones.size - 1}" else ""
                            PickKind.EMAIL -> c.emails.firstOrNull() + if (c.emails.size > 1) " +${c.emails.size - 1}" else ""
                            else -> c.company ?: c.phones.firstOrNull()?.let { Numbers.format(context, it) }
                        },
                        starred = c.starred,
                        look = c.look,
                        onOpen = {
                            haptics.tick()
                            if (kind == PickKind.CONTACT) onPicked(ContactsContract.Contacts.getLookupUri(c.id, c.lookup)) else choosing = c
                        }
                    )
                }
            }
        }
    }

    choosing?.let { c ->
        val rows by produceState<List<PickRow>?>(null, c.id, kind) { value = rowsOf(context, c.id, kind) }
        val r = rows
        // One row: no question to ask.
        LaunchedEffect(r) {
            if (r != null && r.size == 1) {
                choosing = null
                onPicked(r[0].uri)
            }
            if (r != null && r.isEmpty()) choosing = null
        }
        if (r != null && r.size > 1) {
            ZoneAlertDialog(
                onDismissRequest = { choosing = null },
                title = { Text(c.name) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        r.forEach { row ->
                            ZoneSurface(shape = RoundedCornerShape(20.dp), onClick = {
                                choosing = null
                                onPicked(row.uri)
                            }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                                    Text(row.value, style = MaterialTheme.typography.titleMedium)
                                    Text(row.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { choosing = null }) { Text("Cancel") } }
            )
        }
    }
}

/** The rows of [kind] a contact has, each as the URI of its own row. */
private suspend fun rowsOf(context: android.content.Context, contactId: Long, kind: PickKind): List<PickRow> = withContext(Dispatchers.IO) {
    val (uri, value, field) = when (kind) {
        PickKind.PHONE -> Triple(Phone.CONTENT_URI, Phone.NUMBER, Field.PHONE)
        PickKind.EMAIL -> Triple(Email.CONTENT_URI, Email.ADDRESS, Field.EMAIL)
        PickKind.POSTAL -> Triple(StructuredPostal.CONTENT_URI, StructuredPostal.FORMATTED_ADDRESS, Field.POSTAL)
        PickKind.CONTACT -> return@withContext emptyList()
    }
    runCatching {
        context.contentResolver.query(uri, arrayOf(ContactsContract.Data._ID, value, ContactsContract.Data.DATA2, ContactsContract.Data.DATA3), "${ContactsContract.Data.CONTACT_ID} = ?", arrayOf(contactId.toString()), null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val v = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    add(PickRow(ContentUris.withAppendedId(uri, c.getLong(0)), if (kind == PickKind.PHONE) Numbers.format(context, v) else v, field.label(context.resources, c.getInt(2), c.getString(3))))
                }
            }
        }
    }.getOrNull().orEmpty().distinctBy { it.value }
}
