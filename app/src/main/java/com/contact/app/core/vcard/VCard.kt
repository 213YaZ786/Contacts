package com.contact.app.core.vcard

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Relation
import android.provider.ContactsContract.CommonDataKinds.SipAddress
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import com.contact.app.core.contacts.Address
import com.contact.app.core.contacts.Dates
import com.contact.app.core.contacts.Details
import com.contact.app.core.contacts.Labelled
import com.contact.app.core.contacts.Name
import com.contact.app.core.contacts.Organization
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.Base64

/** One person read from a card: their fields, their photo, their labels by name. */
data class Card(val details: Details, val photo: ByteArray?, val categories: List<String>) {
    val title: String get() = details.name.display().ifBlank { details.organization.company }.ifBlank { details.phones.firstOrNull()?.value ?: details.emails.firstOrNull()?.value ?: "" }
}

/**
 * Contact cards (vCard 2.1, 3.0 and 4.0, as phones, Google and Apple
 * write them) read into contacts, and a contact written as a short card
 * for a QR code. Written for this app: a file from anywhere is read with
 * limits, a broken line skipped, never a crash.
 */
object VCard {

    /** Every card in [text], at most [max]. */
    fun parse(text: String, max: Int = MAX_CARDS): List<Card> {
        if (text.length > MAX_CHARS) return emptyList()
        val lines = unfold(text)
        val cards = mutableListOf<Card>()
        var current: MutableList<Property>? = null
        for (line in lines) {
            val p = property(line) ?: continue
            when {
                p.name == "BEGIN" && p.value.equals("VCARD", true) -> current = mutableListOf()
                p.name == "END" && p.value.equals("VCARD", true) -> {
                    current?.let { props -> card(props)?.let { cards += it } }
                    current = null
                    if (cards.size >= max) break
                }
                else -> current?.let { if (it.size < MAX_PROPS) it += p }
            }
        }
        return cards
    }

    private data class Property(val name: String, val params: Map<String, List<String>>, val types: Set<String>, val value: String, val raw: String)

    /**
     * Lines joined back: a line starting with a space or a tab continues
     * the one before (3.0, 4.0); a quoted-printable line ending in = goes on
     * on the next (2.1).
     */
    private fun unfold(text: String): List<String> {
        val out = mutableListOf<String>()
        val src = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var i = 0
        while (i < src.size) {
            var line = src[i]
            i++
            while (i < src.size && (src[i].startsWith(" ") || src[i].startsWith("\t"))) {
                line += src[i].substring(1)
                i++
            }
            if (line.contains("QUOTED-PRINTABLE", ignoreCase = true)) {
                while (line.endsWith("=") && i < src.size) {
                    line = line.dropLast(1) + src[i]
                    i++
                }
            }
            if (line.isNotBlank()) out += line
        }
        return out
    }

    private fun property(line: String): Property? {
        // The value starts after the first colon outside quotes.
        var inQuote = false
        var colon = -1
        for ((k, ch) in line.withIndex()) {
            if (ch == '"') inQuote = !inQuote
            if (ch == ':' && !inQuote) {
                colon = k
                break
            }
        }
        if (colon <= 0) return null
        val head = line.substring(0, colon)
        val rawValue = line.substring(colon + 1)
        val parts = splitUnquoted(head, ';')
        val name = parts[0].substringAfterLast('.').uppercase()
        val params = mutableMapOf<String, MutableList<String>>()
        val types = mutableSetOf<String>()
        parts.drop(1).forEach { part ->
            val eq = part.indexOf('=')
            if (eq < 0) {
                // vCard 2.1: ;HOME;CELL with no TYPE=.
                types += part.uppercase()
            } else {
                val key = part.substring(0, eq).uppercase()
                val values = splitUnquoted(part.substring(eq + 1), ',').map { it.trim('"') }
                params.getOrPut(key) { mutableListOf() } += values
                if (key == "TYPE") types += values.map { it.uppercase() }
                if (key == "PREF") types += "PREF"
            }
        }
        val encoding = params["ENCODING"]?.firstOrNull()?.uppercase()
        val charset = params["CHARSET"]?.firstOrNull()
        val value = when (encoding) {
            "QUOTED-PRINTABLE" -> decodeQuoted(rawValue, charset)
            else -> rawValue
        }
        return Property(name, params, types, value, rawValue)
    }

    private fun splitUnquoted(text: String, by: Char): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuote = false
        for (ch in text) {
            if (ch == '"') inQuote = !inQuote
            if (ch == by && !inQuote) {
                out += sb.toString()
                sb.clear()
            } else sb.append(ch)
        }
        out += sb.toString()
        return out
    }

    private fun decodeQuoted(text: String, charset: String?): String {
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '=' && i + 2 < text.length + 0 && i + 2 <= text.length - 1) {
                val hex = text.substring(i + 1, i + 3)
                val b = hex.toIntOrNull(16)
                if (b != null) {
                    bytes.write(b)
                    i += 3
                    continue
                }
            }
            bytes.write(ch.code and 0xFF)
            i++
        }
        val cs = runCatching { Charset.forName(charset ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)
        return String(bytes.toByteArray(), cs)
    }

    /** \n \, \; \\ back to their characters. */
    private fun unescape(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\\' && i + 1 < text.length) {
                when (val next = text[i + 1]) {
                    'n', 'N' -> sb.append('\n')
                    else -> sb.append(next)
                }
                i += 2
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }

    /** A structured value split on its unescaped semicolons, each part unescaped. */
    private fun parts(value: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '\\' && i + 1 < value.length) {
                sb.append(ch).append(value[i + 1])
                i += 2
                continue
            }
            if (ch == ';') {
                out += unescape(sb.toString())
                sb.clear()
            } else sb.append(ch)
            i++
        }
        out += unescape(sb.toString())
        return out
    }

    private fun card(props: List<Property>): Card? {
        var name = Name()
        var formatted = ""
        var org = Organization()
        var nickname: String? = null
        var note: String? = null
        var photo: ByteArray? = null
        val phones = mutableListOf<Labelled>()
        val emails = mutableListOf<Labelled>()
        val addresses = mutableListOf<Address>()
        val websites = mutableListOf<Labelled>()
        val events = mutableListOf<Labelled>()
        val relations = mutableListOf<Labelled>()
        val messengers = mutableListOf<Labelled>()
        val sips = mutableListOf<Labelled>()
        val categories = mutableListOf<String>()

        fun clip(s: String, max: Int = 300) = s.filter { it == '\n' || !it.isISOControl() }.trim().take(max)

        for (p in props) {
            val types = p.types
            when (p.name) {
                "FN" -> formatted = clip(unescape(p.value))
                "N" -> {
                    val n = parts(p.value).map { clip(it) }
                    name = name.copy(family = n.getOrElse(0) { "" }, given = n.getOrElse(1) { "" }, middle = n.getOrElse(2) { "" }, prefix = n.getOrElse(3) { "" }, suffix = n.getOrElse(4) { "" })
                }
                "X-PHONETIC-FIRST-NAME" -> name = name.copy(phoneticGiven = clip(unescape(p.value)))
                "X-PHONETIC-MIDDLE-NAME" -> name = name.copy(phoneticMiddle = clip(unescape(p.value)))
                "X-PHONETIC-LAST-NAME" -> name = name.copy(phoneticFamily = clip(unescape(p.value)))
                "NICKNAME" -> nickname = clip(unescape(p.value).substringBefore(','))
                "ORG" -> {
                    val o = parts(p.value).map { clip(it) }
                    org = org.copy(company = o.getOrElse(0) { "" }, department = o.drop(1).filter { it.isNotBlank() }.joinToString(", "))
                }
                "TITLE", "ROLE" -> if (org.title.isBlank()) org = org.copy(title = clip(unescape(p.value)))
                "NOTE" -> note = clip(unescape(p.value), 5000)
                "TEL" -> clip(unescape(p.value).removePrefix("tel:"), 80).takeIf { it.isNotBlank() }?.let {
                    phones += Labelled(kind = phoneKind(types), custom = p.params["X-LABEL"]?.firstOrNull(), value = it, primary = "PREF" in types)
                }
                "EMAIL" -> clip(unescape(p.value).removePrefix("mailto:"), 200).takeIf { it.isNotBlank() }?.let {
                    emails += Labelled(kind = when { "WORK" in types -> Email.TYPE_WORK; "HOME" in types -> Email.TYPE_HOME; "CELL" in types -> Email.TYPE_MOBILE; else -> Email.TYPE_OTHER }, value = it, primary = "PREF" in types)
                }
                "ADR" -> {
                    val a = parts(p.value).map { clip(it, 200) }
                    val address = Address(
                        kind = when { "WORK" in types -> StructuredPostal.TYPE_WORK; "HOME" in types -> StructuredPostal.TYPE_HOME; else -> StructuredPostal.TYPE_OTHER },
                        pobox = a.getOrElse(0) { "" },
                        street = listOf(a.getOrElse(1) { "" }, a.getOrElse(2) { "" }).filter { it.isNotBlank() }.joinToString("\n"),
                        city = a.getOrElse(3) { "" }, region = a.getOrElse(4) { "" }, postcode = a.getOrElse(5) { "" }, country = a.getOrElse(6) { "" }
                    )
                    if (!address.isEmpty) addresses += address
                }
                "URL" -> clip(unescape(p.value), 500).takeIf { it.isNotBlank() }?.let {
                    websites += Labelled(kind = when { "WORK" in types -> Website.TYPE_WORK; "HOME" in types -> Website.TYPE_HOME; else -> Website.TYPE_HOMEPAGE }, value = it)
                }
                "BDAY" -> Dates.parse(unescape(p.value))?.let { events += Labelled(kind = Event.TYPE_BIRTHDAY, value = it.stored()) }
                "ANNIVERSARY", "X-ANNIVERSARY" -> Dates.parse(unescape(p.value))?.let { events += Labelled(kind = Event.TYPE_ANNIVERSARY, value = it.stored()) }
                "RELATED" -> clip(unescape(p.value), 200).takeIf { it.isNotBlank() }?.let {
                    relations += Labelled(kind = relationKind(types), value = it)
                }
                "IMPP" -> {
                    val v = unescape(p.value)
                    val service = v.substringBefore(':', "").takeIf { v.contains(':') }
                    clip(v.substringAfter(':'), 200).takeIf { it.isNotBlank() }?.let { messengers += Labelled(kind = Im.PROTOCOL_CUSTOM, custom = service, value = it) }
                }
                "X-SIP" -> clip(unescape(p.value).removePrefix("sip:"), 200).takeIf { it.isNotBlank() }?.let { sips += Labelled(kind = SipAddress.TYPE_OTHER, value = it) }
                "CATEGORIES" -> categories += unescape(p.value).split(',').map { clip(it, 60) }.filter { it.isNotBlank() && it.lowercase() != "mycontacts" && it.lowercase() != "starred" }
                "PHOTO" -> if (photo == null) photo = photoOf(p)
                // Android writes its own kinds as X-ANDROID-CUSTOM:mimetype;value;...
                "X-ANDROID-CUSTOM" -> {
                    val a = parts(p.value)
                    when (a.firstOrNull()) {
                        "vnd.android.cursor.item/nickname" -> if (nickname == null) nickname = a.getOrNull(1)?.let { clip(it) }
                        "vnd.android.cursor.item/relation" -> a.getOrNull(1)?.let { clip(it, 200) }?.takeIf { it.isNotBlank() }?.let {
                            relations += Labelled(kind = a.getOrNull(2)?.toIntOrNull() ?: Relation.TYPE_CUSTOM, custom = a.getOrNull(3)?.takeIf { l -> l.isNotBlank() }, value = it)
                        }
                        "vnd.android.cursor.item/contact_event" -> a.getOrNull(1)?.let { Dates.parse(it) }?.let {
                            events += Labelled(kind = a.getOrNull(2)?.toIntOrNull() ?: Event.TYPE_OTHER, custom = a.getOrNull(3)?.takeIf { l -> l.isNotBlank() }, value = it.stored())
                        }
                    }
                }
            }
        }
        if (name.isEmpty && formatted.isNotBlank()) name = splitName(formatted)
        val details = Details(
            name = name,
            display = formatted.ifBlank { name.display() },
            organization = org,
            nickname = nickname?.takeIf { it.isNotBlank() }?.let { Labelled(kind = 1, value = it) },
            note = note?.takeIf { it.isNotBlank() }?.let { Labelled(kind = 0, value = it) },
            phones = phones.take(ROWS), emails = emails.take(ROWS), addresses = addresses.take(ROWS), websites = websites.take(ROWS),
            events = events.take(ROWS), relations = relations.take(ROWS), messengers = messengers.take(ROWS), sips = sips.take(ROWS)
        )
        val empty = name.isEmpty && org.isEmpty && phones.isEmpty() && emails.isEmpty() && addresses.isEmpty()
        return if (empty) null else Card(details, photo, categories.distinct().take(ROWS))
    }

    private fun splitName(full: String): Name {
        val words = full.trim().split(Regex("\\s+"))
        return if (words.size == 1) Name(given = words[0]) else Name(given = words.dropLast(1).joinToString(" "), family = words.last())
    }

    private fun phoneKind(types: Set<String>): Int = when {
        "FAX" in types && "HOME" in types -> Phone.TYPE_FAX_HOME
        "FAX" in types -> Phone.TYPE_FAX_WORK
        "PAGER" in types -> Phone.TYPE_PAGER
        "CELL" in types && "WORK" in types -> Phone.TYPE_WORK_MOBILE
        "CELL" in types || "MOBILE" in types || "IPHONE" in types -> Phone.TYPE_MOBILE
        "WORK" in types -> Phone.TYPE_WORK
        "HOME" in types -> Phone.TYPE_HOME
        "MAIN" in types -> Phone.TYPE_MAIN
        "VOICE" in types && types.size <= 2 -> Phone.TYPE_MOBILE
        else -> Phone.TYPE_OTHER
    }

    private fun relationKind(types: Set<String>): Int = when {
        "SPOUSE" in types -> Relation.TYPE_SPOUSE
        "CHILD" in types -> Relation.TYPE_CHILD
        "PARENT" in types -> Relation.TYPE_PARENT
        "SIBLING" in types -> Relation.TYPE_BROTHER
        "FRIEND" in types -> Relation.TYPE_FRIEND
        "KIN" in types -> Relation.TYPE_RELATIVE
        else -> Relation.TYPE_CUSTOM
    }

    /** A photo in the card itself (base64, or a data: URI); one at an address is not fetched. */
    private fun photoOf(p: Property): ByteArray? = runCatching {
        val v = p.raw.trim()
        val encoded = when {
            v.startsWith("data:", true) -> v.substringAfter(',')
            p.params["ENCODING"]?.firstOrNull()?.uppercase() in setOf("B", "BASE64") -> v
            else -> return@runCatching null
        }
        if (encoded.length > MAX_PHOTO_CHARS) return@runCatching null
        Base64.getMimeDecoder().decode(encoded)
    }.getOrNull()

    /**
     * A short card (vCard 3.0) of what reaches someone, for a QR code: name,
     * company, numbers, emails, websites; no photo, no notes.
     */
    fun short(d: Details): String = buildString {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")
        append("BEGIN:VCARD\r\nVERSION:3.0\r\n")
        val n = d.name
        append("N:${esc(n.family)};${esc(n.given)};${esc(n.middle)};${esc(n.prefix)};${esc(n.suffix)}\r\n")
        append("FN:${esc(d.display.ifBlank { n.display() })}\r\n")
        if (!d.organization.isEmpty) {
            append("ORG:${esc(d.organization.company)}${if (d.organization.department.isNotBlank()) ";" + esc(d.organization.department) else ""}\r\n")
            if (d.organization.title.isNotBlank()) append("TITLE:${esc(d.organization.title)}\r\n")
        }
        d.phones.forEach { append("TEL;TYPE=${phoneType(it.kind)}:${it.value.filter { c -> c.isDigit() || c == '+' }}\r\n") }
        d.emails.forEach { append("EMAIL;TYPE=INTERNET:${esc(it.value)}\r\n") }
        d.websites.forEach { append("URL:${esc(it.value)}\r\n") }
        append("END:VCARD\r\n")
    }

    private fun phoneType(kind: Int) = when (kind) {
        Phone.TYPE_HOME -> "HOME"
        Phone.TYPE_WORK -> "WORK"
        Phone.TYPE_WORK_MOBILE -> "WORK,CELL"
        Phone.TYPE_FAX_WORK, Phone.TYPE_FAX_HOME -> "FAX"
        Phone.TYPE_MAIN -> "MAIN"
        Phone.TYPE_MOBILE -> "CELL"
        else -> "VOICE"
    }

    private const val MAX_CHARS = 20_000_000
    private const val MAX_CARDS = 20_000
    private const val MAX_PROPS = 400
    private const val MAX_PHOTO_CHARS = 7_000_000
    private const val ROWS = 30
}
