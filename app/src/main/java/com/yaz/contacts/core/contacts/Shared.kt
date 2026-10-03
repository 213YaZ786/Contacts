package com.yaz.contacts.core.contacts

/**
 * A card someone shared of themselves, laid over what the user keeps of
 * them: their own name and work replace the old ones, their numbers and
 * emails are added when new, nothing the user wrote is taken away.
 */
object Shared {

    /** What applying [card] to [base] would change, in short words for the user. */
    fun changes(base: Details, card: Details, photo: Boolean): List<String> = buildList {
        if (photo) add("photo")
        if (!card.name.isEmpty && card.name.display() != base.name.display()) add("name: " + card.name.display())
        if (!card.organization.isEmpty && card.organization.copy(rowId = 0) != base.organization.copy(rowId = 0)) add("work")
        val newPhones = card.phones.count { p -> base.phones.none { digits(it.value) == digits(p.value) } }
        if (newPhones > 0) add(if (newPhones == 1) "a number" else "$newPhones numbers")
        val newEmails = card.emails.count { e -> base.emails.none { it.value.equals(e.value, true) } }
        if (newEmails > 0) add(if (newEmails == 1) "an email" else "$newEmails emails")
        if (card.websites.any { w -> base.websites.none { it.value == w.value } }) add("a website")
    }

    fun apply(base: Details, card: Details): Details = base.copy(
        name = if (card.name.isEmpty) base.name else card.name.copy(rowId = base.name.rowId),
        organization = if (card.organization.isEmpty) base.organization else card.organization.copy(rowId = base.organization.rowId),
        phones = base.phones + card.phones.filter { p -> base.phones.none { digits(it.value) == digits(p.value) } }.map { it.copy(rowId = 0, rawId = 0) },
        emails = base.emails + card.emails.filter { e -> base.emails.none { it.value.equals(e.value, true) } }.map { it.copy(rowId = 0, rawId = 0) },
        websites = base.websites + card.websites.filter { w -> base.websites.none { it.value == w.value } }.map { it.copy(rowId = 0, rawId = 0) }
    )

    private fun digits(s: String) = s.filter(Char::isDigit).takeLast(9)
}
