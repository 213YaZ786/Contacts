package com.yaz.contacts.feature.edit

import com.yaz.contacts.core.contacts.Details
import com.yaz.contacts.core.contacts.Labelled
import com.yaz.contacts.core.contacts.Name
import org.junit.Assert.assertEquals
import org.junit.Test

class MergeTest {

    private fun phone(v: String) = Labelled(kind = 2, value = v)

    @Test
    fun anotherAppsNumberAddedOnlyWhenNew() {
        val base = Details(name = Name(given = "Joëlle"), phones = listOf(phone("06 98 76 54 32")), emails = listOf(Labelled(kind = 1, value = "jo@mail.fr")))
        val add = Details(name = Name(given = "Unknown"), phones = listOf(phone("+33698765432"), phone("0611111111")), emails = listOf(Labelled(kind = 1, value = "JO@mail.fr")))
        val merged = merge(base, add)
        assertEquals("Joëlle", merged.name.given)
        assertEquals(listOf("06 98 76 54 32", "0611111111"), merged.phones.map { it.value })
        assertEquals(1, merged.emails.size)
    }

    @Test
    fun aNewContactTakesTheGivenName() {
        val merged = merge(Details(), Details(name = Name(given = "Test", family = "Handoff"), phones = listOf(phone("0611111111"))))
        assertEquals("Handoff", merged.name.family)
        assertEquals(1, merged.phones.size)
    }
}
