package com.yaz.contacts.core.vcard

import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Phone
import com.yaz.contacts.core.contacts.Details
import com.yaz.contacts.core.contacts.Labelled
import com.yaz.contacts.core.contacts.Name
import com.yaz.contacts.core.contacts.Organization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VCardTest {

    @Test
    fun shortCardReadsBack() {
        val d = Details(
            display = "Amélie Poulain",
            name = Name(given = "Amélie", family = "Poulain"),
            organization = Organization(company = "Café; des 2, Moulins", title = "Serveuse"),
            phones = listOf(Labelled(kind = Phone.TYPE_MOBILE, value = "+33 6 11 22 33 44")),
            emails = listOf(Labelled(kind = 1, value = "amelie@example.fr"))
        )
        val card = VCard.parse(VCard.short(d)).single().details
        assertEquals("Amélie", card.name.given)
        assertEquals("Poulain", card.name.family)
        assertEquals("Café; des 2, Moulins", card.organization.company)
        assertEquals("Serveuse", card.organization.title)
        assertEquals("+33611223344", card.phones.single().value)
        assertEquals(Phone.TYPE_MOBILE, card.phones.single().kind)
        assertEquals("amelie@example.fr", card.emails.single().value)
    }

    @Test
    fun oldPhonesQuotedPrintable() {
        val text = "BEGIN:VCARD\r\nVERSION:2.1\r\nN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:Lef=C3=A8vre;Zo=C3=A9;;;\r\nTEL;CELL;PREF:0600000001\r\nTEL;WORK:0100000002\r\nBDAY:1990-03-12\r\nEND:VCARD\r\n"
        val d = VCard.parse(text).single().details
        assertEquals("Zoé", d.name.given)
        assertEquals("Lefèvre", d.name.family)
        assertEquals(listOf(Phone.TYPE_MOBILE, Phone.TYPE_WORK), d.phones.map { it.kind })
        assertTrue(d.phones[0].primary)
        assertEquals("1990-03-12", d.events.single { it.kind == Event.TYPE_BIRTHDAY }.value)
    }

    @Test
    fun foldedLinesAndSeveralCards() {
        val text = "BEGIN:VCARD\nVERSION:3.0\nFN:Jan de Vries\nNOTE:a long note that\n  goes on\\nand on\nEND:VCARD\nBEGIN:VCARD\nVERSION:4.0\nFN:Wei Zhang\nTEL;VALUE=uri;TYPE=cell:tel:+86-138-0013-8000\nBDAY:--0512\nEND:VCARD\n"
        val cards = VCard.parse(text)
        assertEquals(2, cards.size)
        assertEquals("Jan de", cards[0].details.name.given)
        assertEquals("a long note that goes on\nand on", cards[0].details.note?.value)
        assertEquals("+86-138-0013-8000", cards[1].details.phones.single().value)
        assertEquals("--05-12", cards[1].details.events.single().value)
    }

    @Test
    fun brokenInputNeverCrashes() {
        assertEquals(0, VCard.parse("").size)
        assertEquals(0, VCard.parse("BEGIN:VCARD\nEND:VCARD").size)
        assertEquals(0, VCard.parse(":::;;;\n\u0000\u0001BEGIN:VCARD\nN:;;;;\nEND:VCARD").size)
        val photoAtUrl = "BEGIN:VCARD\nVERSION:3.0\nFN:X Y\nPHOTO;VALUE=uri:https://example.com/x.jpg\nEND:VCARD"
        assertNull(VCard.parse(photoAtUrl).single().photo)
        val many = buildString { repeat(500) { append("BEGIN:VCARD\nFN:P$it Q\nEND:VCARD\n") } }
        assertEquals(100, VCard.parse(many, max = 100).size)
        val longField = "BEGIN:VCARD\nFN:" + "a".repeat(100_000) + " b\nEND:VCARD"
        assertTrue(VCard.parse(longField).single().details.name.given.length <= 300)
    }

    @Test
    fun theChatInviteGoesAndComesBackOthersAreIgnored() {
        val link = "https://i.delta.chat/#ABCDEF0123456789&a=me%40relay.example&n=Yaz&i=xyz&s=abc"
        val text = VCard.short(Details(display = "Yaz", name = Name(given = "Yaz")), chat = link)
        assertEquals(link, VCard.parse(text).single().chat)
        val other = "BEGIN:VCARD\r\nFN:X Y\r\nX-YAZ-CHAT:javascript:alert(1)\r\nEND:VCARD\r\n"
        assertNull(VCard.parse(other).single().chat)
    }
}
