package com.yaz.contacts.core.contacts

/**
 * Going back to how the contacts were: which pictures to keep, and what a
 * picture differs in from now. Pure, so it is tested on its own.
 */
object Snapshot {

    /** Who comes back, who is changed back (as it was, as it is), and who was added since. */
    data class Plan(val back: List<Details>, val changed: List<Pair<Details, Details>>, val added: List<Details>) {
        val isEmpty: Boolean get() = back.isEmpty() && changed.isEmpty() && added.isEmpty()
    }

    /** The pictures to keep: the newest of each of the last 7 days, then of each week, nothing past 30 days. */
    fun keep(times: List<Long>, now: Long): Set<Long> {
        val day = 24 * 60 * 60 * 1000L
        val kept = linkedMapOf<Long, Long>()
        times.sortedDescending().forEach { t ->
            val age = now - t
            if (age > 30 * day) return@forEach
            val slot = if (age < 7 * day) age / day else 100 + age / (7 * day)
            kept.putIfAbsent(slot, t)
        }
        return kept.values.toSet()
    }

    /**
     * Compares a picture with now, person by person through Android's
     * lookup key, which stays the same when a contact is changed or synced.
     */
    fun plan(then: List<Details>, now: List<Details>): Plan {
        val nowByKey = now.filter { it.lookup.isNotBlank() }.associateBy { it.lookup }
        val thenKeys = then.map { it.lookup }.toSet()
        val back = then.filter { it.lookup !in nowByKey }
        val changed = then.mapNotNull { t -> nowByKey[t.lookup]?.let { n -> if (content(t) != content(n)) t to n else null } }
        val added = now.filter { it.lookup.isNotBlank() && it.lookup !in thenKeys && it.id < android.provider.ContactsContract.Profile.MIN_ID }
        return Plan(back, changed, added)
    }

    /** What a person holds, without Android's own ids, which change on their own. */
    fun content(d: Details): Details = fresh(d).copy(photo = null, thumbnail = null, display = "", accounts = emptyList(), readOnly = false, readOnlyRaws = emptySet())

    /** [d] as new rows, to be saved as a new contact. */
    fun fresh(d: Details): Details = d.copy(
        id = 0L, lookup = "", mainRaw = 0L, lookRow = 0L, raws = emptyList(), groupRows = emptyMap(),
        name = d.name.copy(rowId = 0L),
        organization = d.organization.copy(rowId = 0L),
        nickname = d.nickname?.copy(rowId = 0L, rawId = 0L),
        note = d.note?.copy(rowId = 0L, rawId = 0L),
        phones = d.phones.map { it.copy(rowId = 0L, rawId = 0L) },
        emails = d.emails.map { it.copy(rowId = 0L, rawId = 0L) },
        addresses = d.addresses.map { it.copy(rowId = 0L, rawId = 0L) },
        websites = d.websites.map { it.copy(rowId = 0L, rawId = 0L) },
        events = d.events.map { it.copy(rowId = 0L, rawId = 0L) },
        relations = d.relations.map { it.copy(rowId = 0L, rawId = 0L) },
        messengers = d.messengers.map { it.copy(rowId = 0L, rawId = 0L) },
        sips = d.sips.map { it.copy(rowId = 0L, rawId = 0L) }
    )

    /**
     * How [then] is written over [now]: rows that still exist keep their
     * ids, so they are changed back in place; rows gone since are added
     * again; the contact's own ids are now's.
     */
    fun onto(then: Details, now: Details, liveGroups: Set<Long>): Details {
        fun rows(now: List<Labelled>): Set<Long> = now.map { it.rowId }.toSet()
        fun back(list: List<Labelled>, alive: Set<Long>) = list.map { if (it.rowId in alive) it else it.copy(rowId = 0L, rawId = 0L) }
        val alivePostal = now.addresses.map { it.rowId }.toSet()
        return then.copy(
            id = now.id, lookup = now.lookup, mainRaw = now.mainRaw, raws = now.raws, accounts = now.accounts,
            readOnly = now.readOnly, readOnlyRaws = now.readOnlyRaws, lookRow = now.lookRow, groupRows = now.groupRows,
            photo = now.photo, thumbnail = now.thumbnail,
            name = then.name.copy(rowId = now.name.rowId),
            organization = then.organization.copy(rowId = now.organization.rowId),
            nickname = then.nickname?.copy(rowId = now.nickname?.rowId ?: 0L, rawId = 0L),
            note = then.note?.copy(rowId = now.note?.rowId ?: 0L, rawId = 0L),
            phones = back(then.phones, rows(now.phones)),
            emails = back(then.emails, rows(now.emails)),
            addresses = then.addresses.map { if (it.rowId in alivePostal) it else it.copy(rowId = 0L, rawId = 0L) },
            websites = back(then.websites, rows(now.websites)),
            events = back(then.events, rows(now.events)),
            relations = back(then.relations, rows(now.relations)),
            messengers = back(then.messengers, rows(now.messengers)),
            sips = back(then.sips, rows(now.sips)),
            // Labels deleted since cannot come back.
            groups = then.groups.filter { it in liveGroups }.toSet()
        )
    }
}
