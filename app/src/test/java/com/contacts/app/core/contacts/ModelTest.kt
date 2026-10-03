package com.contacts.app.core.contacts

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelTest {

    private fun c(id: Long, name: String, alt: String = name, phones: List<String> = emptyList(), company: String? = null) =
        Contact(id = id, lookup = "$id", name = name, alternative = alt, photo = null, starred = false, phones = phones, company = company)

    @Test
    fun searchFindsWordStartsAccentsAsideNumbersAndCompany() {
        val all = listOf(c(1, "Joëlle Martin", phones = listOf("06 98 76 54 32")), c(2, "Ahmed Benali", company = "Tizi Ouzou University"), c(3, "John Smith"))
        assertEquals(listOf(1L), Contacts.search(all, "joe").map { it.id })
        assertEquals(listOf(1L), Contacts.search(all, "mar jo").map { it.id })
        assertEquals(listOf(1L), Contacts.search(all, "9876").map { it.id })
        assertEquals(listOf(2L), Contacts.search(all, "ouzou").map { it.id })
        assertEquals(listOf(1L, 3L), Contacts.search(all, "j").map { it.id })
    }

    @Test
    fun sortedByChosenNameSignsLast() {
        val all = listOf(c(1, "Zoé Lefèvre", "Lefèvre, Zoé"), c(2, "+33 1 23", "+33 1 23"), c(3, "Amélie Poulain", "Poulain, Amélie"))
        assertEquals(listOf(3L, 1L, 2L), Contacts.sorted(all, SortOrder.FIRST_NAME).map { it.id })
        assertEquals(listOf(1L, 3L, 2L), Contacts.sorted(all, SortOrder.LAST_NAME).map { it.id })
    }

    @Test
    fun datesAsAccountsWriteThem() {
        assertEquals(EventDate(1990, 3, 12), Dates.parse("1990-03-12"))
        assertEquals(EventDate(null, 3, 12), Dates.parse("--03-12"))
        assertEquals(EventDate(null, 3, 12), Dates.parse("--0312"))
        assertEquals(EventDate(1990, 3, 12), Dates.parse("19900312"))
        assertEquals(EventDate(1990, 3, 12), Dates.parse("1990-03-12T00:00:00Z"))
        assertEquals(EventDate(1990, 3, 12), Dates.parse("12.03.1990"))
        assertEquals(EventDate(null, 2, 29), Dates.parse("--02-29"))
        assertNull(Dates.parse("1990-02-29"))
        assertNull(Dates.parse("1990-13-01"))
        assertNull(Dates.parse("soon"))
    }

    @Test
    fun nextBirthdayAndAge() {
        val today = LocalDate.of(2026, 10, 3)
        assertEquals(0L, EventDate(1985, 10, 3).daysUntil(today))
        assertEquals(41, EventDate(1985, 10, 3).ageNext(today))
        assertEquals(3L, EventDate(null, 10, 6).daysUntil(today))
        assertEquals(LocalDate.of(2027, 10, 2), EventDate(2000, 10, 2).next(today))
        // 29 February in a year without it comes on 1 March.
        assertEquals(LocalDate.of(2027, 3, 1), EventDate(null, 2, 29).next(today))
    }

    @Test
    fun lookRowWrittenBadlyIsTheDefault() {
        assertEquals(Look(), LookCodec.decode("not json"))
        assertEquals(Look(), LookCodec.decode("x".repeat(10_000)))
        val look = Look(color = 0xFF1E88E5.toInt(), vibration = "Wave", posterZoom = 2f)
        assertEquals(look, LookCodec.decode(LookCodec.encode(look)))
        assertEquals("", LookCodec.decode("""{"vibration":"Unknown"}""").vibration)
        assertEquals(4f, LookCodec.decode("""{"zoom":99}""").posterZoom)
    }
}
