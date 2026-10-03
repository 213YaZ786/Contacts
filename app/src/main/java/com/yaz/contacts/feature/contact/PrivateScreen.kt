package com.yaz.contacts.feature.contact

import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yaz.contacts.core.handoff.Reach
import com.yaz.contacts.core.security.SafeImages
import com.yaz.contacts.data.contacts.ContactStore
import com.yaz.contacts.data.contacts.ContactWriter
import com.yaz.contacts.data.contacts.PrivateBook
import com.yaz.contacts.data.settings.SettingsStore
import com.yaz.contacts.feature.common.ActionTile
import com.yaz.contacts.feature.common.EvenRows
import com.yaz.contacts.feature.common.rememberUndo
import com.yaz.contacts.ui.component.ContactAvatar
import com.yaz.contacts.ui.component.EmptyZone
import com.yaz.contacts.ui.component.FloatingAction
import com.yaz.contacts.ui.component.FloatingFrame
import com.yaz.contacts.ui.component.FloatingTop
import com.yaz.contacts.ui.component.ZoneAlertDialog
import com.yaz.contacts.ui.component.rememberHaptics
import com.yaz.contacts.ui.icon.AppIcons
import com.yaz.contacts.ui.theme.AlertRed
import com.yaz.contacts.ui.theme.AnswerGreen
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * A person kept private: only in this app, sealed, out of Android's
 * contacts. Reached, read and shared like anyone; made visible again to
 * the other apps (and to be edited) in one tap.
 */
@Composable
fun PrivateScreen(id: String, onBack: () -> Unit, onPublic: (Long) -> Unit) {
    val book: PrivateBook = koinInject()
    val people by book.people.collectAsState()
    val person = people.firstOrNull { it.id == id }
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val undo = rememberUndo()
    val writer: ContactWriter = koinInject()
    val store: ContactStore = koinInject()
    val settings: SettingsStore = koinInject()
    var deleting by remember { mutableStateOf(false) }

    FloatingFrame(bottom = 24.dp, top = { FloatingTop(title = "Private", leading = { FloatingAction(AppIcons.ArrowBack, "Back", onBack) }) }) { padding ->
        if (person == null) {
            EmptyZone(title = "Not here", message = "This private contact was made visible or deleted.", icon = AppIcons.Lock, modifier = Modifier.fillMaxSize().padding(padding))
            return@FloatingFrame
        }
        val d = person.details
        val photo by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, person.photo) {
            value = person.photo?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }?.let { SafeImages.decode(context, it, 720) }?.asImageBitmap()
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(padding.calculateTopPadding() + 8.dp))
            Box(contentAlignment = Alignment.BottomEnd) {
                photo.let { p -> if (p != null) Image(p, null, contentScale = ContentScale.Crop, modifier = Modifier.size(132.dp).clip(CircleShape))
                else ContactAvatar(d.display, null, 132.dp, look = d.look.forAvatar()) }
                Icon(AppIcons.Lock, "Private", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(d.display.ifBlank { d.name.display() }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text("Only in Contacts: other apps do not see them", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            val numbers = d.phones.map { it.value }
            EvenRows(minSlot = 64.dp, modifier = Modifier.widthIn(max = 640.dp)) {
                if (numbers.isNotEmpty()) ActionTile(AppIcons.Call, "Call", AnswerGreen) { Reach.call(context, numbers.first()) }
                if (numbers.isNotEmpty() && Reach.canMessage(context)) ActionTile(AppIcons.Message, "Message") { Reach.message(context, numbers.take(1)) }
                if (d.emails.isNotEmpty() && Reach.canEmail(context)) ActionTile(AppIcons.Email, "Email") { Reach.email(context, listOf(d.emails.first().value)) }
                ActionTile(AppIcons.Contacts, "Make visible") {
                    scope.launch {
                        val id = book.show(context, person, writer, store, settings.current.defaultAccount)
                        if (id != null) {
                            haptics.done()
                            undo.show("${d.display} is visible again", null)
                            onPublic(id)
                        } else haptics.reject()
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Fields(d)
            Spacer(Modifier.height(8.dp))
            InfoZone("More") {
                InfoRow(AppIcons.Delete, "Delete for good", null, onClick = { deleting = true }, tint = AlertRed)
            }
            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
    if (deleting && person != null) ZoneAlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Delete ${person.details.display}?") },
        text = { Text("A private contact is only here: it will be gone for good.") },
        confirmButton = {
            TextButton(onClick = {
                deleting = false
                haptics.reject()
                book.remove(person.id)
                onBack()
            }) { Text("Delete", color = AlertRed) }
        },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } }
    )
}

