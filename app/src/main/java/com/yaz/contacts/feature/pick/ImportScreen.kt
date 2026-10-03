package com.yaz.contacts.feature.pick

import android.content.Context
import android.net.Uri
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yaz.contacts.core.contacts.Account
import com.yaz.contacts.core.dial.Numbers
import com.yaz.contacts.core.vcard.Card
import com.yaz.contacts.core.vcard.VCard
import com.yaz.contacts.data.contacts.ContactStore
import com.yaz.contacts.data.contacts.ContactWriter
import com.yaz.contacts.data.settings.SettingsStore
import com.yaz.contacts.feature.common.PersonLine
import com.yaz.contacts.feature.contact.accountLabel
import com.yaz.contacts.ui.component.BoldButton
import com.yaz.contacts.ui.component.EmptyZone
import com.yaz.contacts.ui.component.FloatingAction
import com.yaz.contacts.ui.component.FloatingFrame
import com.yaz.contacts.ui.component.FloatingPane
import com.yaz.contacts.ui.component.FloatingTop
import com.yaz.contacts.ui.component.LoadingMark
import com.yaz.contacts.ui.component.ZoneAlertDialog
import com.yaz.contacts.ui.component.rememberHaptics
import com.yaz.contacts.ui.icon.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Contact cards from a file, a message or a QR code: read on the phone,
 * shown first, saved only when the user says so, in the account chosen.
 */
@Composable
fun ImportScreen(source: Uri?, text: String? = null, inPerson: Boolean = false, onClose: () -> Unit, onDone: (Int) -> Unit) {
    val context = LocalContext.current
    val store: ContactStore = koinInject()
    val writer: ContactWriter = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var cards by remember { mutableStateOf<List<Card>?>(null) }
    var chosen by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var accounts by remember { mutableStateOf<List<Account>>(emptyList()) }
    var account by remember { mutableStateOf<Account?>(null) }
    var choosingAccount by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }

    LaunchedEffect(source, text) {
        accounts = store.accounts()
        val saved = settingsStore.current.defaultAccount
        account = accounts.firstOrNull { it.key == saved } ?: accounts.firstOrNull { it.type != null } ?: accounts.firstOrNull()
        val read = withContext(Dispatchers.IO) { VCard.parse(text ?: source?.let { readText(context, it) }.orEmpty()) }
        cards = read
        chosen = read.indices.toSet()
    }

    FloatingFrame(
        bottom = 24.dp,
        top = {
            FloatingTop(
                title = cards?.let { if (it.size == 1) "Contact card" else "${it.size} contacts" } ?: "Contact card",
                leading = { FloatingAction(AppIcons.Close, "Cancel", onClose) }
            )
            if (accounts.size > 1 && !cards.isNullOrEmpty()) {
                Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    FloatingPane(shape = CircleShape, onClick = { choosingAccount = true }) {
                        Text("Save in " + (account?.let(::accountLabel) ?: "This phone"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
                    }
                }
            }
        }
    ) { padding ->
        val list = cards
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingMark(size = 72.dp) }
            list.isEmpty() -> EmptyZone(title = "No contact here", message = "The file holds no contact card that can be read.", icon = AppIcons.Contacts, modifier = Modifier.fillMaxSize().padding(padding))
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(list) { i, card ->
                    PersonLine(
                        name = card.title.ifBlank { "No name" },
                        photo = null,
                        subtitle = card.details.phones.firstOrNull()?.value?.let { Numbers.format(context, it) } ?: card.details.emails.firstOrNull()?.value ?: card.details.organization.company.ifBlank { null },
                        starred = false,
                        selected = i in chosen,
                        look = card.details.look.takeIf { !it.isDefault },
                        onOpen = {
                            haptics.toggle(i !in chosen)
                            chosen = if (i in chosen) chosen - i else chosen + i
                        }
                    )
                }
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                        if (saving) {
                            LoadingMark(size = 48.dp)
                            Text("$done of ${chosen.size}", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            BoldButton(filled = true, enabled = chosen.isNotEmpty(), onClick = {
                                saving = true
                                scope.launch {
                                    var count = 0
                                    for (i in chosen.sorted()) {
                                        val card = list[i]
                                        val photo = card.photo?.let { bytes -> com.yaz.contacts.core.security.SafeImages.decode(context, bytes) }
                                        if (writer.save(null, card.details, account, photo) != null) {
                                            count++
                                            // Met in person: their encrypted chat starts in SMS, already proven.
                                            val number = card.details.phones.firstOrNull()?.value
                                            if (inPerson && card.chat != null && number != null) com.yaz.contacts.core.handoff.ChatLink.join(context, card.chat, number)
                                        }
                                        done = count
                                    }
                                    saving = false
                                    if (count > 0) haptics.done() else haptics.reject()
                                    onDone(count)
                                }
                            }) { Text(if (chosen.size == 1) "Save contact" else "Save ${chosen.size} contacts") }
                        }
                    }
                }
            }
        }
    }
    if (choosingAccount) {
        ZoneAlertDialog(
            onDismissRequest = { choosingAccount = false },
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
                            Text(accountLabel(a), modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingAccount = false }) { Text("Cancel") } }
        )
    }
}

/** The text of a card file, at most 20 MB, as UTF-8 (2.1 cards say their own charset per line). */
fun readText(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > 20 * 1024 * 1024) return@runCatching null
            out.write(buffer, 0, n)
        }
        out.toByteArray().decodeToString()
    }
}.getOrNull()
