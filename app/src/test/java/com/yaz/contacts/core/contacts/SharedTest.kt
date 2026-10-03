package com.yaz.contacts.core.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedTest {

    private val base = Details(
        name = Name(rowId = 5, given = "Amelie", family = "P"),
        phones = listOf(Labelled(rowId = 9, kind = 2, value = "06 11 22 33 44")),
        note = Labelled(kind = 0, value = "met at the café")
    )

    @Test
    fun theirNameReplacesNumbersAddNotesStay() {
        val card = Details(name = Name(given = "Amélie", family = "Poulain"), phones = listOf(Labelled(kind = 2, value = "+33611223344"), Labelled(kind = 3, value = "0122334455")))
        assertEquals(listOf("photo", "name: Amélie Poulain", "a number"), Shared.changes(base, card, photo = true))
        val after = Shared.apply(base, card)
        assertEquals("Poulain", after.name.family)
        assertEquals(5L, after.name.rowId)
        assertEquals(2, after.phones.size)
        assertEquals("met at the café", after.note?.value)
    }

    @Test
    fun theSameCardChangesNothing() {
        val card = Details(name = Name(given = "Amelie", family = "P"), phones = listOf(Labelled(kind = 2, value = "0611223344")))
        assertTrue(Shared.changes(base, card, photo = false).isEmpty())
    }
}
