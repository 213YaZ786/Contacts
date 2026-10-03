package com.contact.app.core.contacts

import com.contact.app.core.dial.People

/**
 * People saved more than once: the same number (its last nine digits, so
 * +33 6 12 34 56 78 and 06 12 34 56 78 meet), the same email, or the same
 * full name. Each group is shown once, whatever brought it together.
 */
object Duplicates {

    fun find(all: List<Contact>): List<List<Contact>> {
        val parent = HashMap<Long, Long>()
        fun root(x: Long): Long {
            var r = x
            while (parent[r] != null && parent[r] != r) r = parent[r]!!
            return r
        }
        fun join(a: Long, b: Long) {
            val ra = root(a)
            val rb = root(b)
            if (ra != rb) parent[ra] = rb
        }
        all.forEach { parent[it.id] = it.id }
        val byKey = HashMap<String, Long>()
        fun see(key: String, id: Long) {
            val first = byKey.putIfAbsent(key, id)
            if (first != null && first != id) join(first, id)
        }
        all.forEach { c ->
            c.phones.map { it.filter(Char::isDigit) }.filter { it.length >= 6 }.forEach { see("tel:" + it.takeLast(9), c.id) }
            c.emails.forEach { see("mail:" + it.trim().lowercase(), c.id) }
            val name = People.plain(c.name).replace(Regex("[^\\p{L}\\p{N} ]"), "").split(' ').filter { it.isNotEmpty() }.sorted().joinToString(" ")
            // A name alone of one word ("Mom") is too thin to call two people the same.
            if (name.contains(' ')) see("name:$name", c.id)
        }
        return all.groupBy { root(it.id) }.values.filter { it.size > 1 }.sortedBy { People.plain(it.first().name) }
    }
}
