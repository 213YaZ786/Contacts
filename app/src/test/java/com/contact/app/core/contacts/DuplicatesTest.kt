package com.contact.app.core.contacts

import org.junit.Assert.assertEquals
import org.junit.Test

class DuplicatesTest {

    private fun c(id: Long, name: String, phones: List<String> = emptyList(), emails: List<String> = emptyList()) =
        Contact(id = id, lookup = "$id", name = name, alternative = name, photo = null, starred = false, phones = phones, emails = emails)

    @Test
    fun sameNumberWrittenTwoWays() {
        val groups = Duplicates.find(listOf(c(1, "Jo", listOf("+33 6 12 34 56 78")), c(2, "Joëlle", listOf("06 12 34 56 78")), c(3, "Ahmed", listOf("0123456789"))))
        assertEquals(listOf(listOf(1L, 2L)), groups.map { g -> g.map { it.id } })
    }

    @Test
    fun sameEmailAnyCase() {
        val groups = Duplicates.find(listOf(c(1, "A", emails = listOf("Jo@Mail.com")), c(2, "B", emails = listOf("jo@mail.com "))))
        assertEquals(1, groups.size)
    }

    @Test
    fun sameFullNameAccentsAndOrderAside() {
        val groups = Duplicates.find(listOf(c(1, "Joëlle Martin"), c(2, "martin joelle"), c(3, "Mom"), c(4, "Mom")))
        assertEquals(listOf(listOf(1L, 2L)), groups.map { g -> g.map { it.id } })
    }

    @Test
    fun chainsJoinIntoOneGroup() {
        val groups = Duplicates.find(listOf(c(1, "A", listOf("0611111111")), c(2, "B", listOf("0611111111"), listOf("b@x.org")), c(3, "C", emails = listOf("b@x.org"))))
        assertEquals(listOf(listOf(1L, 2L, 3L)), groups.map { g -> g.map { it.id } })
    }
}
