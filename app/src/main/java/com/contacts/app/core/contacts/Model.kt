package com.contacts.app.core.contacts

import com.contacts.app.core.dial.People
import kotlinx.serialization.Serializable

/**
 * A person in the list: what the list, the search and the letters need,
 * read from Android's contacts (the only store; nothing is copied).
 */
@Serializable
data class Contact(
    val id: Long,
    val lookup: String,
    /** The name as the user chose to see it, first name first or last name first. */
    val name: String,
    /** The other order, for sorting by the other name. */
    val alternative: String,
    val photo: String?,
    val starred: Boolean,
    val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    val company: String? = null,
    val nickname: String? = null,
    /** Where it is saved: "type/name" of each of its accounts, "" for the phone itself. */
    val accounts: Set<String> = emptySet(),
    /** Its labels (Android's groups), by group id. */
    val groups: Set<Long> = emptySet(),
    /** When Android last changed it, for "recently added". */
    val updated: Long = 0L,
    /** Birthday as Android keeps it, --MM-DD or YYYY-MM-DD, when there is one. */
    val birthday: String? = null
)

/** An account contacts are saved in: Google, CardDAV, the phone itself (type null). */
@Serializable
data class Account(val type: String?, val name: String?, val label: String) {
    val key: String get() = if (type == null) "" else "$type/$name"
}

/** A label (Android's contact group) of an account. */
@Serializable
data class Group(val id: Long, val title: String, val account: String, val count: Int)

/** How a row is labelled: one of Android's kinds, or the user's own word (kind 0, custom). */
@Serializable
data class Labelled(
    /** Android's data row id; 0 for a row not saved yet. */
    val rowId: Long = 0L,
    val rawId: Long = 0L,
    val kind: Int,
    val custom: String? = null,
    val value: String,
    val primary: Boolean = false
)

@Serializable
data class Address(
    val rowId: Long = 0L,
    val rawId: Long = 0L,
    val kind: Int,
    val custom: String? = null,
    val street: String = "",
    val pobox: String = "",
    val neighborhood: String = "",
    val city: String = "",
    val region: String = "",
    val postcode: String = "",
    val country: String = ""
) {
    /** The address on lines, as written on an envelope. */
    val lines: String
        get() = listOf(street, pobox, neighborhood, listOf(postcode, city).filter { it.isNotBlank() }.joinToString(" "), region, country)
            .map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    val isEmpty: Boolean get() = listOf(street, pobox, neighborhood, city, region, postcode, country).all { it.isBlank() }
}

/** Everything a contact holds, as the page shows it and the editor changes it. */
@Serializable
data class Details(
    val id: Long = 0L,
    val lookup: String = "",
    /** The raw contact new rows go to, and the account it is in. */
    val mainRaw: Long = 0L,
    val accounts: List<Account> = emptyList(),
    val readOnly: Boolean = false,
    /** Raw contacts of accounts that do not take changes back: their rows are shown, never written. */
    val readOnlyRaws: Set<Long> = emptySet(),
    val display: String = "",
    val name: Name = Name(),
    val nickname: Labelled? = null,
    val organization: Organization = Organization(),
    val phones: List<Labelled> = emptyList(),
    val emails: List<Labelled> = emptyList(),
    val addresses: List<Address> = emptyList(),
    val websites: List<Labelled> = emptyList(),
    val events: List<Labelled> = emptyList(),
    val relations: List<Labelled> = emptyList(),
    val messengers: List<Labelled> = emptyList(),
    val sips: List<Labelled> = emptyList(),
    val note: Labelled? = null,
    val groups: Set<Long> = emptySet(),
    /** The group memberships' rows, by group id, to remove one. */
    val groupRows: Map<Long, Long> = emptyMap(),
    val photo: String? = null,
    val thumbnail: String? = null,
    val starred: Boolean = false,
    val ringtone: String? = null,
    val toVoicemail: Boolean = false,
    /** The person's own look, kept in their contact (see Look). */
    val look: Look = Look(),
    val lookRow: Long = 0L,
    /** Raw contact ids merged into this one, for "Separate". */
    val raws: List<Long> = emptyList()
)

@Serializable
data class Name(
    val rowId: Long = 0L,
    val prefix: String = "",
    val given: String = "",
    val middle: String = "",
    val family: String = "",
    val suffix: String = "",
    val phoneticGiven: String = "",
    val phoneticMiddle: String = "",
    val phoneticFamily: String = ""
) {
    val isEmpty: Boolean get() = listOf(prefix, given, middle, family, suffix).all { it.isBlank() }
    val hasPhonetic: Boolean get() = listOf(phoneticGiven, phoneticMiddle, phoneticFamily).any { it.isNotBlank() }
    fun display(lastFirst: Boolean = false): String {
        val first = listOf(prefix, given, middle).filter { it.isNotBlank() }.joinToString(" ")
        val last = listOf(family, suffix).filter { it.isNotBlank() }.joinToString(" ")
        return if (lastFirst && last.isNotEmpty() && first.isNotEmpty()) "$last, $first"
        else listOf(first, last).filter { it.isNotEmpty() }.joinToString(" ")
    }
}

@Serializable
data class Organization(
    val rowId: Long = 0L,
    val company: String = "",
    val title: String = "",
    val department: String = ""
) {
    val isEmpty: Boolean get() = company.isBlank() && title.isBlank() && department.isBlank()
}

/**
 * What a person looks like on the user's phone, beyond what Android knows:
 * their colour (Dialer's call screen, SMS's conversation), how their photo
 * is framed and their name drawn full screen when they call (the poster),
 * and their vibration. Saved in the contact itself, in one row of this
 * app's own kind, so Dialer and SMS read it from the same place.
 */
@Serializable
data class Look(
    /** ARGB, 0 for the wallpaper's accent. */
    val color: Int = 0,
    /** The poster: how the photo fills the screen when they call. */
    val posterZoom: Float = 1f,
    val posterX: Float = 0.5f,
    val posterY: Float = 0.4f,
    /** The name's style on the poster: one of POSTER_STYLES. */
    val posterStyle: String = "classic",
    /** A vibration of their own for their calls and messages: one of VIBRATIONS' keys, "" for the phone's. */
    val vibration: String = "",
    /** An emoji shown when there is no photo. */
    val emoji: String = "",
    /** How they want to be spoken of (she/her, he/him, they/them, or their own words), shown under the name. */
    val pronouns: String = ""
) {
    val isDefault: Boolean get() = this == Look()
}

/** How the list orders and shows names. */
@Serializable
enum class SortOrder { FIRST_NAME, LAST_NAME }

object Contacts {

    /**
     * Birthdays within [days] of [today], soonest first, with the days to
     * wait and the age reached when the year is known.
     */
    fun birthdays(all: List<Contact>, today: java.time.LocalDate, days: Int = 7): List<Triple<Contact, Long, Int?>> =
        all.mapNotNull { c ->
            val date = c.birthday?.let { Dates.parse(it) } ?: return@mapNotNull null
            val wait = date.daysUntil(today)
            if (wait > days) null else Triple(c, wait, date.ageNext(today))
        }.sortedWith(compareBy({ it.second }, { People.plain(it.first.name) }))

    /** The letter a name files under; digits and signs under #. */
    fun initialOf(name: String): String =
        People.plain(name).firstOrNull()?.takeIf { it.isLetter() }?.uppercase() ?: "#"

    /**
     * Who matches [query]: every word of it found at the start of a word of
     * the name, the nickname or the company, accents and case aside; or the
     * digits in a number; or the text in an email.
     */
    fun search(all: List<Contact>, query: String): List<Contact> {
        val q = People.plain(query.trim())
        if (q.isEmpty()) return all
        val words = q.split(' ').filter { it.isNotEmpty() }
        val digits = query.filter { it.isDigit() }
        return all.filter { c ->
            val text = People.plain(listOfNotNull(c.name, c.alternative, c.nickname, c.company).joinToString(" "))
            val starts = text.split(' ', '-', '.', '\'')
            words.all { w -> starts.any { it.startsWith(w) } || text.contains(w) && w.length >= 3 } ||
                (digits.length >= 2 && digits.length >= query.count { !it.isWhitespace() } - 1 && c.phones.any { it.filter(Char::isDigit).contains(digits) }) ||
                (q.length >= 2 && c.emails.any { People.plain(it).contains(q) })
        }
    }

    /** The list's order: favourites apart, then everyone by the chosen name. */
    fun sorted(all: List<Contact>, order: SortOrder): List<Contact> {
        val key: (Contact) -> String = { if (order == SortOrder.LAST_NAME) it.alternative else it.name }
        return all.sortedWith(compareBy<Contact>({ initialOf(key(it)) == "#" }, { People.plain(key(it)) }, { it.id }))
    }

    /** Names a person goes by in the list: last name first when sorted that way. */
    fun shown(c: Contact, order: SortOrder): String = if (order == SortOrder.LAST_NAME) c.alternative.ifBlank { c.name } else c.name
}
