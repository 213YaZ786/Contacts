package com.yaz.contacts.core.ocr

import android.provider.ContactsContract.CommonDataKinds.Phone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardTextTest {

    @Test
    fun aFrenchCard() {
        val text = """
            Amélie POULAIN
            Responsable commerciale
            Café des 2 Moulins SARL
            15 rue Lepic
            75018 Paris
            Tél. 01 42 54 90 50
            Mobile : 06 11 22 33 44
            amelie@deuxmoulins.fr
            www.deuxmoulins.fr
        """.trimIndent()
        // A phone set to the United States still reads a French card's numbers, from its .fr domain.
        val d = CardText.parse(text, "US")
        assertEquals("Amélie", d.name.given)
        assertEquals("Poulain", d.name.family)
        assertEquals("Café des 2 Moulins SARL", d.organization.company)
        assertEquals("Responsable commerciale", d.organization.title)
        assertEquals(2, d.phones.size)
        assertEquals(Phone.TYPE_MOBILE, d.phones[1].kind)
        assertEquals("amelie@deuxmoulins.fr", d.emails.single().value)
        assertEquals("www.deuxmoulins.fr", d.websites.single().value)
        val a = d.addresses.single()
        assertEquals("75018", a.postcode)
        assertEquals("Paris", a.city)
        assertTrue(a.street.contains("rue Lepic"))
    }

    @Test
    fun aGermanCard() {
        val text = "Dr. Dieter Müller\nGeschäftsführer\nMüller Bau GmbH\nHauptstraße 12\n10115 Berlin\n+49 30 1234567\nd.mueller@muellerbau.de"
        val d = CardText.parse(text, "DE")
        assertEquals("Müller Bau GmbH", d.organization.company)
        assertEquals("Geschäftsführer", d.organization.title)
        assertEquals(1, d.phones.size)
        assertEquals("10115", d.addresses.single().postcode)
    }

    @Test
    fun anEnglishCardWithoutLegalForm() {
        val text = "JOHN SMITH\nSenior Software Engineer\nM: +44 7911 123456\njohn.smith@example.co.uk"
        val d = CardText.parse(text, "GB")
        assertEquals("John", d.name.given)
        assertEquals("Smith", d.name.family)
        assertEquals("Senior Software Engineer", d.organization.title)
        assertEquals(Phone.TYPE_MOBILE, d.phones.single().kind)
    }
}
