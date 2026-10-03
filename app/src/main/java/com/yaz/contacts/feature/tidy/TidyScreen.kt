package com.yaz.contacts.feature.tidy

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yaz.contacts.core.contacts.Account
import com.yaz.contacts.core.contacts.Duplicates
import com.yaz.contacts.data.contacts.ContactStore
import com.yaz.contacts.data.contacts.ContactWriter
import com.yaz.contacts.data.contacts.Transfer
import com.yaz.contacts.data.contacts.Trash
import com.yaz.contacts.data.settings.SettingsStore
import com.yaz.contacts.feature.common.rememberUndo
import com.yaz.contacts.feature.contact.InfoRow
import com.yaz.contacts.feature.contact.InfoZone
import com.yaz.contacts.feature.contact.accountLabel
import com.yaz.contacts.ui.component.ContactAvatar
import com.yaz.contacts.ui.component.FloatingAction
import com.yaz.contacts.ui.component.FloatingFrame
import com.yaz.contacts.ui.component.FloatingTop
import com.yaz.contacts.ui.component.ZoneAlertDialog
import com.yaz.contacts.ui.component.ZoneSurface
import com.yaz.contacts.ui.component.rememberHaptics
import com.yaz.contacts.ui.icon.AppIcons
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Keeping the contacts in order: people saved twice joined into one,
 * labels, the trash, cards in and out of the phone, the SIM's contacts.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TidyScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, onTrash: () -> Unit, onUndo: () -> Unit, onScan: () -> Unit, onLabel: (Long) -> Unit, onImport: (Uri) -> Unit, onImportText: (String) -> Unit) {
    val store: ContactStore = koinInject()
    val writer: ContactWriter = koinInject()
    val trash: Trash = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settings.collectAsState()
    val all by store.contacts.collectAsState()
    val groups by store.groups.collectAsState()
    val inTrash by trash.entries.collectAsState()
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val undo = rememberUndo()
    LaunchedEffect(Unit) {
        store.refresh()
        trash.load(settings.trashDays)
    }
    val duplicates = remember(all) { Duplicates.find(all.orEmpty()) }
    var naming by remember { mutableStateOf(false) }
    val known by androidx.compose.runtime.produceState(emptyList<Account>()) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.accounts() } }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(onImport) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-vcard")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val keys = all.orEmpty().map { it.lookup }.filter { it.isNotBlank() }
        scope.launch {
            val count = Transfer.export(context, keys, uri)
            if (count > 0) haptics.done() else haptics.reject()
            undo.show(if (count == 1) "1 contact saved to the file" else "$count contacts saved to the file", null)
        }
    }

    FloatingFrame(
        bottom = 24.dp,
        top = { FloatingTop(title = "Tidy up", leading = { FloatingAction(AppIcons.ArrowBack, "Back", onBack) }) }
    ) { padding ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(padding.calculateTopPadding()))
            InfoZone(if (duplicates.isEmpty()) "Saved twice" else "Saved twice · ${duplicates.size}") {
                if (duplicates.isEmpty()) InfoRow(AppIcons.CheckCircle, "No one is saved twice", null, onClick = null)
                duplicates.forEach { group ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            group.forEach { c ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                                    ContactAvatar(c.name, c.photo, 32.dp)
                                    TextButton(onClick = { onOpen(c.id) }) { Text(c.name, maxLines = 1) }
                                }
                            }
                        }
                        TextButton(onClick = {
                            scope.launch {
                                if (writer.merge(group.map { it.id })) {
                                    haptics.done()
                                    undo.show("Merged into one", null)
                                } else haptics.reject()
                            }
                        }) { Text("Merge") }
                    }
                }
                if (duplicates.size > 1) InfoRow(AppIcons.Merge, "Merge all", "Each account keeps its own copy, shown as one person", onClick = {
                    scope.launch {
                        var done = 0
                        duplicates.forEach { if (writer.merge(it.map { c -> c.id })) done++ }
                        haptics.done()
                        undo.show("$done merged", null)
                    }
                })
            }
            // How many contacts each account holds, as Google's sync card shows.
            InfoZone("Accounts") {
                val counts = remember(all, known) {
                    known.map { a -> a to all.orEmpty().count { c -> a.key in c.accounts } }.filter { it.second > 0 || it.first.type != null }
                }
                counts.forEach { (a, n) -> InfoRow(AppIcons.Account, accountLabel(a), if (n == 1) "1 contact" else "$n contacts", onClick = null) }
            }
            InfoZone("Labels") {
                groups.forEach { g -> InfoRow(AppIcons.Label, g.title, "${g.count} · " + accountName(g.account, known), onClick = { onLabel(g.id) }) }
                InfoRow(AppIcons.Add, "New label", null, onClick = { naming = true })
            }
            InfoZone("In and out") {
                InfoRow(AppIcons.PhotoCamera, "Scan a contact's QR code", "From another phone or a printed card", onClick = onScan)
                InfoRow(AppIcons.Download, "Import from a file", "Contact cards (.vcf)", onClick = { importer.launch(arrayOf("text/x-vcard", "text/vcard", "text/directory", "text/plain", "application/octet-stream")) })
                InfoRow(AppIcons.Upload, "Export to a file", "All ${all.orEmpty().size} contacts as cards (.vcf)", onClick = { exporter.launch("contacts.vcf") })
                InfoRow(AppIcons.SimCard, "Import from the SIM card", null, onClick = {
                    scope.launch {
                        val text = Transfer.simCards(context)
                        if (text.isBlank()) {
                            haptics.reject()
                            undo.show("No contact on the SIM card", null)
                        } else onImportText(text)
                    }
                })
            }
            InfoZone("Going back") {
                InfoRow(AppIcons.History, "Undo changes", "Your contacts as they were, up to 30 days ago", onClick = onUndo)
                InfoRow(AppIcons.DeleteOutline, if (inTrash.isEmpty()) "Trash: empty" else "Trash: ${inTrash.size} deleted", "Kept ${settings.trashDays} days, then gone", onClick = onTrash)
            }
            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }

    if (naming) {
        var title by remember { mutableStateOf("") }
        val accounts = known
        var account by remember { mutableStateOf<Account?>(accounts.firstOrNull { it.key == settings.defaultAccount } ?: accounts.firstOrNull { it.type != null } ?: accounts.firstOrNull()) }
        ZoneAlertDialog(
            onDismissRequest = { naming = false },
            icon = { androidx.compose.material3.Icon(AppIcons.Label, null) },
            title = { Text("New label") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(title, { title = it.take(60) }, label = { Text("Name") }, singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                    if (accounts.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        accounts.forEach { a ->
                            ZoneSurface(shape = RoundedCornerShape(50), accent = a.key == account?.key, onClick = { account = a }) {
                                Text(accountLabel(a), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 240.dp), maxLines = 1)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val a = account ?: return@TextButton
                    if (title.isBlank()) return@TextButton
                    naming = false
                    scope.launch { writer.createGroup(title, a)?.let { haptics.done(); onLabel(it) } }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } }
        )
    }
}

private fun accountName(key: String, accounts: List<Account>): String =
    accounts.firstOrNull { it.key == key }?.let(::accountLabel) ?: if (key.isEmpty()) "This phone" else key.substringAfter('/')
