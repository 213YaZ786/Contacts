package com.yaz.contacts.feature.scan

import com.yaz.contacts.core.vcard.VCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardsTest {

    @Test
    fun mecardBecomesAContact() {
        val card = Cards.of("MECARD:N:Martin,Joëlle;TEL:+33 6 98 76 54 32;EMAIL:jo@mail.fr;ORG:Café\\; bar;;")!!
        val d = VCard.parse(card).single().details
        assertEquals("Joëlle", d.name.given)
        assertEquals("Martin", d.name.family)
        assertEquals("+33698765432", d.phones.single().value)
        assertEquals("jo@mail.fr", d.emails.single().value)
        assertEquals("Café; bar", d.organization.company)
    }

    @Test
    fun vcardAndNumbersPassOthersDoNot() {
        assertEquals(1, VCard.parse(Cards.of("BEGIN:VCARD\nFN:A B\nTEL:1234\nEND:VCARD")!!).size)
        assertEquals("+33611111111", VCard.parse(Cards.of("tel:+33 6 11 11 11 11")!!).single().details.phones.single().value)
        assertNull(Cards.of("https://example.com"))
        assertNull(Cards.of("WIFI:S:home;P:secret;;"))
    }
}
