package com.yaz.contacts.feature.list

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yaz.contacts.core.contacts.Contact
import com.yaz.contacts.core.contacts.Contacts
import com.yaz.contacts.core.dial.Numbers
import com.yaz.contacts.core.handoff.Reach
import com.yaz.contacts.feature.common.ListHeading
import com.yaz.contacts.feature.common.PersonLine
import com.yaz.contacts.ui.component.ContactAvatar
import com.yaz.contacts.ui.component.ZoneSurface
import com.yaz.contacts.ui.component.rememberHaptics
import com.yaz.contacts.ui.icon.AppIcons
import com.yaz.contacts.ui.theme.AnswerGreen

/**
 * The left tab: everything that changes, the list of everyone stays plain.
 * The user's card, cards shared with them, people to keep in touch with,
 * numbers talked with often but not saved, the birthdays of the week, and
 * the people talked with or added lately.
 */
@Composable
fun ForYouPage(
    people: List<Contact>,
    lately: List<Contact>,
    me: Contact?,
    onMe: () -> Unit,
    lastFirst: Boolean,
    padding: PaddingValues,
    onOpen: (Long) -> Unit
) {
    val context = LocalContext.current
    val birthdays = remember(people) { Contacts.birthdays(people, java.time.LocalDate.now()) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item(key = "me") {
            val card = me ?: NO_CARD
            PersonLine(
                name = if (card === NO_CARD) "My card" else card.name.ifBlank { "My card" },
                photo = card.photo,
                subtitle = if (card === NO_CARD) "Make yours, to share it as a QR code" else "My card",
                starred = false,
                onOpen = onMe
            )
        }
        item(key = "shared") { SharedWithYouCard(people, onOpen) }
        item(key = "touch") { KeepInTouch(people, onOpen) }
        item(key = "often") {
            val save = LocalSave.current
            TalkedOften(people, onSave = { number -> save.value?.invoke(number) })
        }
        if (birthdays.isNotEmpty()) item(key = "bday") { BirthdayCardShown(birthdays, onOpen) }
        if (lately.isNotEmpty()) {
            item(key = "lately/title") { ListHeading("Talked with or added lately") }
            items(lately, key = { "l/${it.id}" }) { c ->
                PersonLine(
                    name = Contacts.shown(c, lastFirst),
                    photo = c.photo,
                    subtitle = c.phones.firstOrNull()?.let { Numbers.format(context, it) } ?: c.emails.firstOrNull(),
                    starred = c.starred,
                    onOpen = { onOpen(c.id) },
                    look = c.look
                )
            }
        }
    }
}

/**
 * The right tab: the few favourites as large cards, two to a row (one on a
 * narrow screen is never the case: a phone is wide enough for two), their
 * face big, their name, and a call or a message in one tap.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FavoriteCards(favorites: List<Contact>, padding: PaddingValues, onOpen: (Long) -> Unit, onLongPress: (Contact) -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        items(favorites.chunked(2), key = { row -> "fav/" + row.joinToString("-") { it.id.toString() } }) { row ->
            // The two of a row as tall as each other, the actions on one line.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Max)) {
                row.forEach { c ->
                    val shape = RoundedCornerShape(28.dp)
                    ZoneSurface(
                        shape = shape,
                        modifier = Modifier.weight(1f).fillMaxHeight().clip(shape).combinedClickable(
                            onClick = { haptics.tick(); onOpen(c.id) },
                            onLongClick = { haptics.firm(); onLongPress(c) }
                        )
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize().padding(top = 18.dp, bottom = 14.dp, start = 10.dp, end = 10.dp)) {
                            ContactAvatar(c.name, c.photo, 104.dp, look = c.look?.forAvatar())
                            Spacer(Modifier.height(10.dp))
                            Text(
                                c.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.weight(1f).height(10.dp))
                            val number = c.phones.firstOrNull()
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (number != null) RoundAction(AppIcons.Call, "Call", AnswerGreen) { Reach.call(context, number) }
                                if (number != null && Reach.canMessage(context)) RoundAction(AppIcons.Message, "Message", MaterialTheme.colorScheme.primary) { Reach.message(context, listOf(number)) }
                                if (number == null) c.emails.firstOrNull()?.let { email -> RoundAction(AppIcons.Email, "Email", MaterialTheme.colorScheme.primary) { Reach.email(context, listOf(email)) } }
                            }
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    ZoneSurface(shape = CircleShape, modifier = Modifier.size(48.dp).clip(CircleShape).combinedClickableCompat(label) { haptics.tick(); onClick() }) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(icon, contentDescription = label, tint = tint) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(label: String, onClick: () -> Unit) = this.combinedClickable(onClickLabel = label, onClick = onClick)
