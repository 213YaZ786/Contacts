package com.yaz.contacts.feature.pick

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yaz.contacts.core.contacts.Contacts
import com.yaz.contacts.core.contacts.Details
import com.yaz.contacts.core.dial.Numbers
import com.yaz.contacts.data.contacts.ContactStore
import com.yaz.contacts.data.settings.SettingsStore
import com.yaz.contacts.feature.common.LineWidth
import com.yaz.contacts.feature.common.PersonLine
import com.yaz.contacts.feature.main.SetupZone
import com.yaz.contacts.ui.component.FloatingAction
import com.yaz.contacts.ui.component.FloatingFrame
import com.yaz.contacts.ui.component.FloatingTop
import com.yaz.contacts.ui.component.LoadingMark
import com.yaz.contacts.ui.component.SearchPill
import com.yaz.contacts.ui.component.ZoneSurface
import com.yaz.contacts.ui.component.rememberHaptics
import com.yaz.contacts.ui.icon.AppIcons
import org.koin.compose.koinInject

/**
 * Another app gave a number or an address to keep: in a new contact, or
 * added to someone already here, chosen from the list.
 */
@Composable
fun ChooseScreen(adding: Details?, onClose: () -> Unit, onNew: () -> Unit, onExisting: (Long) -> Unit) {
    val store: ContactStore = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settings.collectAsState()
    val all by store.contacts.collectAsState()
    LaunchedEffect(Unit) { store.refresh() }
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(all, query, settings.sortOrder) { all?.let { Contacts.search(Contacts.sorted(it, settings.sortOrder), query) } }
    val what = adding?.phones?.firstOrNull()?.value?.let { Numbers.format(context, it) } ?: adding?.emails?.firstOrNull()?.value ?: adding?.name?.display()

    FloatingFrame(
        bottom = 24.dp,
        top = {
            FloatingTop(title = what?.let { "Add $it" } ?: "Add to contacts", leading = { FloatingAction(AppIcons.Close, "Cancel", onClose) })
            Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.TopCenter) { SetupZone() }
            Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                SearchPill(query, { query = it }, hint = "Find someone already saved", modifier = Modifier.widthIn(max = 560.dp).weight(1f, fill = false).fillMaxWidth(), floating = true)
            }
        }
    ) { padding ->
        val list = shown
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            // The usual case first and clearly: a new person, in a large filled card.
            item(key = "new") {
                val shape = RoundedCornerShape(28.dp)
                androidx.compose.material3.Surface(
                    shape = shape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shadowElevation = 4.dp,
                    onClick = {
                        haptics.firm()
                        onNew()
                    },
                    modifier = Modifier.widthIn(max = LineWidth).fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 22.dp, vertical = 22.dp)) {
                        Box(
                            Modifier.size(52.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) { Icon(AppIcons.PersonAdd, null, modifier = Modifier.size(28.dp)) }
                        Spacer(Modifier.width(18.dp))
                        Column {
                            Text("Create a new contact", style = MaterialTheme.typography.titleLarge)
                            what?.let { Text("With $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)) }
                        }
                    }
                }
            }
            // Then, apart: the people already saved, to give them this number too.
            item(key = "existing") {
                Text(
                    "Or add it to someone already saved",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = LineWidth).fillMaxWidth().padding(start = 8.dp, top = 18.dp, bottom = 2.dp)
                )
            }
            when {
                list == null -> item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { LoadingMark(size = 56.dp) } }
                else -> items(list, key = { it.id }) { c ->
                    PersonLine(
                        name = Contacts.shown(c, settings.lastNameFirst),
                        photo = c.photo,
                        subtitle = c.phones.firstOrNull()?.let { Numbers.format(context, it) } ?: c.emails.firstOrNull(),
                        starred = c.starred,
                        look = c.look,
                        onOpen = { onExisting(c.id) }
                    )
                }
            }
        }
    }
}
