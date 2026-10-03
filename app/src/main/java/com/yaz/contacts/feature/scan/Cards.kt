package com.yaz.contacts.feature.scan

/**
 * What a QR code holds, as a contact card if it is one: a vCard, a MECARD
 * (the short form many phones and printed cards use), or a bare number.
 */
object Cards {

    fun of(text: String): String? {
        val t = text.trim()
        if (t.length > 8000) return null
        return when {
            t.startsWith("BEGIN:VCARD", ignoreCase = true) -> t
            t.startsWith("MECARD:", ignoreCase = true) -> mecard(t.substring(7))
            t.startsWith("tel:", ignoreCase = true) -> t.substring(4).filter { it.isDigit() || it == '+' }.takeIf { it.length >= 3 }?.let { "BEGIN:VCARD\r\nVERSION:3.0\r\nTEL:$it\r\nEND:VCARD\r\n" }
            else -> null
        }
    }

    /** MECARD:N:Doe,John;TEL:+33…;EMAIL:…;ORG:…;URL:…;BDAY:19900312;; as a vCard 3.0. */
    private fun mecard(body: String): String? {
        val fields = mutableListOf<Pair<String, String>>()
        val sb = StringBuilder()
        var i = 0
        var key: String? = null
        while (i < body.length) {
            val c = body[i]
            when {
                c == '\\' && i + 1 < body.length -> { sb.append(body[i + 1]); i += 2; continue }
                c == ':' && key == null -> { key = sb.toString().uppercase(); sb.clear() }
                c == ';' -> { key?.let { fields += it to sb.toString() }; key = null; sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        key?.let { fields += it to sb.toString() }
        if (fields.isEmpty()) return null
        fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")
        return buildString {
            append("BEGIN:VCARD\r\nVERSION:3.0\r\n")
            fields.forEach { (k, v) ->
                when (k) {
                    "N" -> {
                        val parts = v.split(',')
                        val family = parts.getOrElse(0) { "" }.trim()
                        val given = parts.getOrElse(1) { "" }.trim()
                        append("N:${esc(family)};${esc(given)};;;\r\nFN:${esc(listOf(given, family).filter { it.isNotBlank() }.joinToString(" "))}\r\n")
                    }
                    "TEL" -> append("TEL:${v.filter { it.isDigit() || it == '+' }}\r\n")
                    "EMAIL" -> append("EMAIL:${esc(v)}\r\n")
                    "ORG" -> append("ORG:${esc(v)}\r\n")
                    "URL" -> append("URL:${esc(v)}\r\n")
                    "NOTE" -> append("NOTE:${esc(v)}\r\n")
                    "BDAY" -> append("BDAY:${v.filter { it.isDigit() }}\r\n")
                    "ADR" -> append("ADR:;;${esc(v)};;;;\r\n")
                    "NICKNAME" -> append("NICKNAME:${esc(v)}\r\n")
                }
            }
            append("END:VCARD\r\n")
        }
    }
}
