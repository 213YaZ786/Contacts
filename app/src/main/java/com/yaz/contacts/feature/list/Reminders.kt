package com.yaz.contacts.feature.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yaz.contacts.core.contacts.Contact
import com.yaz.contacts.core.dial.Numbers
import com.yaz.contacts.core.handoff.Reach
import com.yaz.contacts.core.handoff.Talks
import com.yaz.contacts.data.settings.SettingsStore
import com.yaz.contacts.feature.common.LineWidth
import com.yaz.contacts.ui.component.ContactAvatar
import com.yaz.contacts.ui.component.ZoneSurface
import com.yaz.contacts.ui.component.rememberHaptics
import com.yaz.contacts.ui.icon.AppIcons
import com.yaz.contacts.ui.theme.AnswerGreen
import org.koin.compose.koinInject

/**
 * People the user wants to keep in touch with and has not called or
 * written for longer than they chose, as Dialer and SMS know it; shown
 * when the list opens, no alarm of its own.
 */
@Composable
fun KeepInTouch(people: List<Contact>, onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val watched = people.filter { (it.look?.every ?: 0) > 0 && it.phones.isNotEmpty() }
    val due by produceState<List<Pair<Contact, Long>>>(emptyList(), watched.map { it.id }) {
        if (watched.isEmpty()) return@produceState
        val numbers = watched.map { it.phones }
        val flat = numbers.flatten()
        val at = Talks.last(context, flat) ?: return@produceState
        var i = 0
        val now = System.currentTimeMillis()
        value = watched.mapIndexedNotNull { k, c ->
            val last = numbers[k].maxOf { at.getOrElse(i++) { 0L } }
            val every = (c.look?.every ?: 0) * 24L * 3600 * 1000
            if (last > 0 && now - last > every) c to (now - last) / (24L * 3600 * 1000) else null
        }.sortedByDescending { it.second }
    }
    if (due.isEmpty()) return
    ZoneSurface(shape = RoundedCornerShape(24.dp), modifier = Modifier.widthIn(max = LineWidth).fillMaxWidth()) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)) {
                Icon(AppIcons.History, null, tint = MaterialTheme.colorScheme.primary)
                Text("Keep in touch", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 10.dp))
            }
            due.forEach { (c, days) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { haptics.tick(); onOpen(c.id) }.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)) {
                    ContactAvatar(c.name, c.photo, 36.dp, look = c.look?.forAvatar())
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(c.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(if (days < 14) "$days days since you talked" else "${days / 7} weeks since you talked", style = MaterialTheme.typography.bodySmall)
                    }
                    if (Reach.canMessage(context)) IconButton(onClick = { haptics.tick(); Reach.message(context, listOf(c.phones.first())) }) { Icon(AppIcons.Message, "Write to ${c.name}", tint = MaterialTheme.colorScheme.primary) }
                    IconButton(onClick = { haptics.firm(); Reach.call(context, c.phones.first()) }) { Icon(AppIcons.Call, "Call ${c.name}", tint = AnswerGreen) }
                }
            }
        }
    }
}

/** Numbers the user talks with often but never saved, with a way to save them or wave them away. */
@Composable
fun TalkedOften(people: List<Contact>, onSave: (String) -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val store: SettingsStore = koinInject()
    val settings by store.settings.collectAsState()
    val hidden by org.koin.compose.koinInject<com.yaz.contacts.data.contacts.PrivateBook>().people.collectAsState()
    val saved = (people.flatMap { c -> c.phones.map { it.filter(Char::isDigit).takeLast(9) } } +
        hidden.flatMap { p -> p.details.phones.map { it.value.filter(Char::isDigit).takeLast(9) } }).toSet()
    val often by produceState<List<Pair<String, Int>>>(emptyList(), saved.size) { value = Talks.frequent(context) }
    val shown = often.filter { (n, _) -> n.filter(Char::isDigit).takeLast(9) !in saved && n !in settings.notToSave }.take(5)
    if (shown.isEmpty()) return
    ZoneSurface(shape = RoundedCornerShape(24.dp), modifier = Modifier.widthIn(max = LineWidth).fillMaxWidth()) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)) {
                Icon(AppIcons.PersonAdd, null, tint = MaterialTheme.colorScheme.primary)
                Text("You talk with them often", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 10.dp))
            }
            shown.forEach { (number, count) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(Numbers.format(context, number), style = MaterialTheme.typography.titleMedium)
                        Text("$count calls and messages lately", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { haptics.tick(); onSave(number) }) { Icon(AppIcons.PersonAdd, "Save $number", tint = MaterialTheme.colorScheme.primary) }
                    IconButton(onClick = {
                        haptics.tick()
                        store.update { it.copy(notToSave = it.notToSave + number) }
                    }) { Icon(AppIcons.Close, "Not this one") }
                }
            }
        }
    }
}
