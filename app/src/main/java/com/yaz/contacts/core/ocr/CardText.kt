package com.yaz.contacts.core.ocr

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.yaz.contacts.core.contacts.Address
import com.yaz.contacts.core.contacts.Details
import com.yaz.contacts.core.contacts.Labelled
import com.yaz.contacts.core.contacts.Name
import com.yaz.contacts.core.contacts.Organization

/**
 * The text read on a business card, sorted into a contact's fields: the
 * numbers (any country, through libphonenumber), emails, websites, the
 * company by its legal form, the job by the words of many languages, the
 * address by its postcode or street words, and the name from what is left.
 * Only a first draft: the user checks it in the editor before saving.
 */
object CardText {

    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val WEB = Regex("(?i)\\b((?:https?://)?(?:www\\.)?[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.(?:com|org|net|fr|de|es|it|pt|nl|be|ch|uk|co|io|eu|ma|dz|tn|ca|us|in|br|mx|ru|jp|cn|kr|tr|pl|ar|au|app|dev|info|biz|me)(?:/[\\w./-]*)?)\\b")
    private val LEGAL = Regex(
        "(?i)\\b(inc|llc|ltd|limited|corp|corporation|co\\.|company|gmbh|ag|kg|ug|sarl|sas|sasu|sa|eurl|sci|snc|s\\.?p\\.?a|s\\.?r\\.?l|s\\.?l|s\\.?a\\.?u|b\\.?v|n\\.?v|plc|pty|oy|ab|as|aps|kft|zrt|sp\\.? z o\\.?o|ooo|kk|group|groupe|grupo|holding|consulting|studio|agency|agence|cabinet|associates|partners)\\b\\.?"
    )
    private val JOB = Regex(
        "(?i)\\b(ceo|cto|cfo|coo|founder|co-founder|owner|president|director|manager|engineer|developer|designer|consultant|architect|analyst|officer|head of|lead|specialist|advisor|lawyer|attorney|doctor|dr\\.|professor|sales|marketing|" +
            "directeur|directrice|gérant|gérante|fondateur|fondatrice|ingénieur|ingénieure|développeur|développeuse|chef de|responsable|chargé|chargée|conseiller|conseillère|avocat|avocate|médecin|commercial|commerciale|" +
            "geschäftsführer|geschäftsführerin|leiter|leiterin|ingenieur|berater|beraterin|entwickler|inhaber|" +
            "director|directora|gerente|ingeniero|ingeniera|asesor|asesora|abogado|abogada|jefe|" +
            "direttore|direttrice|ingegnere|consulente|avvocato|responsabile|" +
            "diretor|diretora|engenheiro|engenheira|consultor|consultora|" +
            "directeur|eigenaar|adviseur|ontwikkelaar|manager)\\b"
    )
    private val STREET = Regex(
        "(?i)\\b(\\d+[a-z]?,? )?(rue|avenue|av\\.|boulevard|bd|chemin|place|allée|impasse|quai|route|street|st\\.|road|rd\\.|ave|lane|drive|way|square|calle|avenida|plaza|paseo|via|viale|piazza|corso|straße|strasse|str\\.|weg|platz|allee|gasse|straat|laan|plein|rua|travessa|largo)\\b"
    )
    private val POSTCODE = Regex("\\b(\\d{4,5}|[A-Z]{1,2}\\d[A-Z\\d]? ?\\d[A-Z]{2}|\\d{3}-\\d{4}|\\d{2}-\\d{3})\\b")

    fun parse(text: String, region: String): Details {
        val lines = text.lines().map { it.trim() }.filter { it.length >= 2 }.take(60)
        val util = PhoneNumberUtil.getInstance()
        val used = mutableSetOf<String>()

        val emails = lines.flatMap { line -> EMAIL.findAll(line).map { it.value.trimEnd('.') }.toList() }.distinct()
        emails.forEach { e -> lines.filter { it.contains(e) }.forEach { used += it } }

        // The card's own country, from the domains of its emails and website (.fr, .de, .ma…), tried before the phone's.
        val domains = (emails + lines.flatMap { line -> WEB.findAll(line.replace(EMAIL, " ")).map { it.value }.toList() })
            .mapNotNull { it.substringAfterLast('.').lowercase().takeIf { tld -> tld.length == 2 } }
            .map { if (it == "uk") "GB" else it.uppercase() }
            .filter { it in util.supportedRegions }
        val regions = (domains + region.uppercase()).distinct()
        val phones = mutableListOf<Labelled>()
        lines.forEach { line ->
            val found = regions.asSequence()
                .map { r -> runCatching { util.findNumbers(line, r).toList() }.getOrDefault(emptyList()) }
                .firstOrNull { it.isNotEmpty() }.orEmpty()
            if (found.isNotEmpty()) used += line
            found.forEach { m ->
                val raw = m.rawString().trim()
                val lower = line.lowercase()
                val kind = when {
                    Regex("\\b(fax|télécopie|telefax)\\b").containsMatchIn(lower) -> Phone.TYPE_FAX_WORK
                    (Regex("\\b(mob|mobile|cell|portable|port\\.|handy|móvil|movil|cellulare|celular|gsm)\\b").containsMatchIn(lower) || Regex("^(m|p)\\s*[:.]").containsMatchIn(lower)) -> Phone.TYPE_MOBILE
                    else -> Phone.TYPE_WORK
                }
                if (phones.none { it.value.filter(Char::isDigit) == raw.filter(Char::isDigit) }) phones += Labelled(kind = kind, value = raw)
            }
        }

        // Websites, once the emails are out of the line (their domain is not a website).
        val webs = lines.flatMap { line -> WEB.findAll(line.replace(EMAIL, " ")).map { it.value }.toList() }.distinct()
        webs.forEach { w -> lines.filter { it.contains(w) }.forEach { used += it } }

        val company = lines.firstOrNull { it !in used && LEGAL.containsMatchIn(it) }
        company?.let { used += it }
        val title = lines.firstOrNull { it !in used && JOB.containsMatchIn(it) && it.length < 60 }
        title?.let { used += it }

        val street = lines.indexOfFirst { it !in used && (STREET.containsMatchIn(it) || POSTCODE.containsMatchIn(it) && it.any(Char::isLetter)) }
        val address = if (street >= 0) {
            val parts = lines.drop(street).takeWhile { it !in used }.take(3)
            used += parts
            val joined = parts.joinToString("\n")
            val postcode = POSTCODE.find(parts.drop(1).joinToString(" ").ifBlank { joined })?.value.orEmpty()
            val cityLine = parts.firstOrNull { postcode.isNotEmpty() && it.contains(postcode) }
            Address(
                kind = StructuredPostal.TYPE_WORK,
                street = parts.filter { it != cityLine }.joinToString("\n"),
                postcode = postcode,
                city = cityLine?.replace(postcode, "")?.trim(' ', ',', '-').orEmpty()
            )
        } else null

        // The name: the first line left that reads like one (two to four words, letters only).
        val name = lines.firstOrNull { line ->
            line !in used && line.split(Regex("\\s+")).size in 2..4 && line.none { it.isDigit() || it == '@' } &&
                line.split(Regex("\\s+")).all { w -> w.first().isUpperCase() || w.all { it.isUpperCase() } }
        } ?: lines.firstOrNull { it !in used && it.none(Char::isDigit) && it.length < 40 }
        val words = name?.split(Regex("\\s+")).orEmpty().map { w -> if (w.length > 1 && w.all { it.isUpperCase() }) w.lowercase().replaceFirstChar(Char::titlecase) else w }

        return Details(
            name = when (words.size) {
                0 -> Name()
                1 -> Name(given = words[0])
                else -> Name(given = words.dropLast(1).joinToString(" "), family = words.last())
            },
            organization = Organization(company = company?.trim().orEmpty(), title = title?.trim().orEmpty()),
            phones = phones.take(6),
            emails = emails.take(4).map { Labelled(kind = Email.TYPE_WORK, value = it) },
            websites = webs.take(3).map { Labelled(kind = Website.TYPE_WORK, value = it) },
            addresses = listOfNotNull(address?.takeIf { !it.isEmpty })
        )
    }
}
