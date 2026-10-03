package com.yaz.contacts.core.handoff

import android.content.Context
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * When the user last talked with people, and whom they talk with often
 * without having saved them: asked of Dialer (calls) and SMS (messages)
 * through their doors for apps signed with the same key. Read only, never
 * kept: Contacts holds no call log and no messages.
 */
object Talks {

    private val dialer = listOf("com.yaz.dialer.people", "com.yaz.dialer.debug.people")
    private val sms = listOf("com.yaz.sms.people", "com.yaz.sms.debug.people")

    /**
     * The last call or message with each of [numbers], 0 when none; at most
     * 500 asked at once. Null when neither Dialer nor SMS answered, so no
     * one is shown as forgotten for want of an answer.
     */
    suspend fun last(context: Context, numbers: List<String>): LongArray? = withContext(Dispatchers.IO) {
        val asked = numbers.take(500)
        val out = LongArray(asked.size)
        if (asked.isEmpty()) return@withContext out
        val extras = Bundle().apply { putStringArrayList("numbers", ArrayList(asked)) }
        var answered = false
        for (side in listOf(dialer, sms)) {
            val at = call(context, side, "last", extras)?.getLongArray("at") ?: continue
            answered = true
            for (i in out.indices) if (i < at.size && at[i] in (out[i] + 1)..System.currentTimeMillis()) out[i] = at[i]
        }
        if (answered) out else null
    }

    /** Numbers not saved that the user called or wrote often lately, most first. */
    suspend fun frequent(context: Context, days: Int = 90): List<Pair<String, Int>> = withContext(Dispatchers.IO) {
        val counts = LinkedHashMap<String, Int>()
        val extras = Bundle().apply { putInt("days", days.coerceIn(1, 90)) }
        for (side in listOf(dialer, sms)) {
            val result = call(context, side, "frequent", extras) ?: continue
            val numbers = result.getStringArrayList("numbers").orEmpty()
            val n = result.getIntArray("counts") ?: continue
            numbers.forEachIndexed { i, number ->
                val key = number.filter { it.isDigit() || it == '+' }.take(20)
                if (key.count(Char::isDigit) >= 6 && i < n.size) counts[key] = (counts[key] ?: 0) + n[i].coerceIn(0, 10_000)
            }
        }
        counts.entries.sortedByDescending { it.value }.take(20).map { it.key to it.value }
    }

    private fun call(context: Context, authorities: List<String>, method: String, extras: Bundle): Bundle? {
        for (authority in authorities) {
            val result = runCatching { context.contentResolver.call(Uri.parse("content://$authority"), method, null, extras) }.getOrNull()
            if (result != null) return result
        }
        return null
    }
}
