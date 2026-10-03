package com.yaz.contacts.feature.tidy

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.yaz.contacts.core.contacts.Account
import com.yaz.contacts.data.contacts.Backups
import com.yaz.contacts.data.contacts.ContactStore
import com.yaz.contacts.feature.common.rememberUndo
import com.yaz.contacts.feature.contact.InfoRow
import com.yaz.contacts.feature.contact.InfoZone
import com.yaz.contacts.feature.contact.accountLabel
import com.yaz.contacts.ui.component.LoadingMark
import com.yaz.contacts.ui.component.ZoneAlertDialog
import com.yaz.contacts.ui.component.rememberHaptics
import com.yaz.contacts.ui.icon.AppIcons
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * The encrypted backup in Tidy up: every contact in a file only a
 * passphrase opens, made here and read back on any phone with this app.
 */
@Composable
fun BackupZone(accounts: List<Account>) {
    val backups: Backups = koinInject()
    val store: ContactStore = koinInject()
    val haptics = rememberHaptics()
    val undo = rememberUndo()
    val scope = rememberCoroutineScope()
    var making by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf<CharArray?>(null) }
    var opening by remember { mutableStateOf<Uri?>(null) }
    var book by remember { mutableStateOf<Backups.Book?>(null) }
    var working by remember { mutableStateOf<String?>(null) }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pass = passphrase
        passphrase = null
        if (uri == null || pass == null) return@rememberLauncherForActivityResult
        working = "Sealing your contacts…"
        scope.launch {
            val n = backups.make(uri, pass)
            pass.fill(' ')
            working = null
            if (n >= 0) {
                haptics.done()
                undo.show(if (n == 1) "1 contact in the backup" else "$n contacts in the backup", null)
            } else haptics.reject()
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> opening = uri }

    InfoZone("Encrypted backup") {
        InfoRow(AppIcons.Lock, "Make an encrypted backup", "Every contact in one file only your passphrase opens, for a new phone", onClick = { making = true })
        InfoRow(AppIcons.Restore, "Restore an encrypted backup", null, onClick = { open.launch(arrayOf("*/*")) })
    }

    if (making) PassphraseDialog(twice = true, title = "Passphrase for the backup", onDismiss = { making = false }) { pass ->
        making = false
        passphrase = pass
        val day = java.time.LocalDate.now().toString()
        create.launch("contacts-$day.yazbackup")
    }
    opening?.let { uri ->
        PassphraseDialog(twice = false, title = "Passphrase of the backup", onDismiss = { opening = null }) { pass ->
            opening = null
            working = "Opening the backup…"
            scope.launch {
                val read = backups.read(uri, pass)
                pass.fill(' ')
                working = null
                if (read == null) {
                    haptics.reject()
                    undo.show("Wrong passphrase, or not a backup", null)
                } else book = read
            }
        }
    }
    book?.let { b ->
        var account by remember { mutableStateOf(accounts.firstOrNull { it.type != null } ?: accounts.firstOrNull()) }
        ZoneAlertDialog(
            onDismissRequest = { book = null },
            icon = { Icon(AppIcons.Restore, null) },
            title = { Text("${b.people.size} contacts") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Backup of " + DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date(b.at)))
                    if (accounts.size > 1) accounts.forEach { a ->
                        TextButton(onClick = { account = a }) { Text((if (a.key == account?.key) "● " else "○ ") + accountLabel(a)) }
                    }
                    Text("They are added as new contacts; Tidy up finds any saved twice.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val chosen = account
                    book = null
                    working = "Restoring…"
                    scope.launch {
                        val n = backups.restore(b, chosen)
                        working = null
                        haptics.done()
                        undo.show("$n contacts restored", null)
                        store.refresh()
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { book = null }) { Text("Cancel") } }
        )
    }
    working?.let { what ->
        ZoneAlertDialog(
            onDismissRequest = {},
            icon = { LoadingMark(size = 48.dp) },
            title = { Text(what) },
            confirmButton = {}
        )
    }
}

/** A passphrase typed out of sight; asked twice when it is being chosen, at least 10 characters. */
@Composable
fun PassphraseDialog(twice: Boolean, title: String, onDismiss: () -> Unit, onDone: (CharArray) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val ready = if (twice) first.length >= 10 && first == second else first.isNotEmpty()
    ZoneAlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.Lock, null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (twice) Text("At least 10 characters. Without it the backup cannot be opened, by anyone: keep it safe.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    first, { first = it.take(200) }, label = { Text("Passphrase") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
                )
                if (twice) OutlinedTextField(
                    second, { second = it.take(200) }, label = { Text("Once more") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = second.isNotEmpty() && second != first,
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(enabled = ready, onClick = { onDone(first.toCharArray()) }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
