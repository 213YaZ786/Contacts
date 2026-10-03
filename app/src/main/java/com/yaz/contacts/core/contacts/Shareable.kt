package com.yaz.contacts.core.contacts

/**
 * The user's own card as it goes out (a QR code, touching phones): only
 * what they chose to give, like NameDrop's choice of number and email.
 */
object Shareable {

    fun key(kind: String, value: String) = when (kind) {
        "tel" -> "tel:" + value.filter(Char::isDigit).takeLast(9)
        "mail" -> "mail:" + value.trim().lowercase()
        else -> "$kind:" + value.trim()
    }

    fun keep(d: Details, keptBack: Set<String>): Details = if (keptBack.isEmpty()) d else d.copy(
        phones = d.phones.filter { key("tel", it.value) !in keptBack },
        emails = d.emails.filter { key("mail", it.value) !in keptBack },
        websites = d.websites.filter { key("web", it.value) !in keptBack },
        organization = if (WORK in keptBack) Organization() else d.organization
    )

    const val WORK = "work"
}
