package com.yaz.contacts.core.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotTest {

    private val day = 24 * 60 * 60 * 1000L

    private fun person(lookup: String, id: Long, given: String, phone: String, row: Long) =
        Details(id = id, lookup = lookup, mainRaw = id, name = Name(rowId = row, given = given), phones = listOf(Labelled(rowId = row + 1, rawId = id, kind = 2, value = phone)))

    @Test
    fun keepsADayAWeekThenAWeekToAMonth() {
        val now = 100 * day
        // Every half day for 30 days.
        val times = (0..60).map { now - it * day / 2 }
        val kept = Snapshot.keep(times, now)
        assertTrue(kept.all { now - it <= 30 * day })
        assertEquals(7 + 4, kept.size)
        assertTrue(now in kept)
    }

    @Test
    fun planFindsBackChangedAndAdded() {
        val then = listOf(person("a", 1, "Ann", "061", 10), person("b", 2, "Bob", "062", 20))
        val now = listOf(person("b", 7, "Bobby", "062", 70), person("c", 3, "Cy", "063", 30))
        val plan = Snapshot.plan(then, now)
        assertEquals(listOf("a"), plan.back.map { it.lookup })
        assertEquals(listOf("b"), plan.changed.map { it.first.lookup })
        assertEquals(listOf("c"), plan.added.map { it.lookup })
    }

    @Test
    fun idsAloneAreNoChange() {
        val plan = Snapshot.plan(listOf(person("a", 1, "Ann", "061", 10)), listOf(person("a", 9, "Ann", "061", 90)))
        assertTrue(plan.isEmpty)
    }

    @Test
    fun ontoKeepsLiveRowsAndAddsGoneOnes() {
        val then = person("b", 2, "Bob", "062", 20).copy(groups = setOf(5L, 6L))
        val now = person("b", 7, "Bobby", "062", 70).copy(phones = emptyList())
        val written = Snapshot.onto(then, now, liveGroups = setOf(5L))
        assertEquals(7L, written.id)
        assertEquals(70L, written.name.rowId)
        assertEquals("Bob", written.name.given)
        assertEquals(0L, written.phones.single().rowId)
        assertEquals(setOf(5L), written.groups)
    }
}
