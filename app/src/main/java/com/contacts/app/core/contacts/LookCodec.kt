package com.contacts.app.core.contacts

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A person's look as one row of the contact, in this app's own kind of
 * row: DATA1 holds it as JSON, so Dialer and SMS read it with the contacts
 * permission they already have, and a field added later does not break an
 * older reader.
 */
object LookCodec {

    /** The kind of row, fixed: the debug build and the release read the same rows. */
    const val MIMETYPE = "vnd.android.cursor.item/vnd.com.contacts.app.look"

    @Serializable
    private data class Stored(
        val v: Int = 1,
        val color: Int = 0,
        val zoom: Float = 1f,
        val x: Float = 0.5f,
        val y: Float = 0.4f,
        val style: String = "classic",
        val vibration: String = "",
        val emoji: String = ""
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun encode(look: Look): String = json.encodeToString(
        Stored.serializer(),
        Stored(
            color = look.color,
            zoom = look.posterZoom,
            x = look.posterX,
            y = look.posterY,
            style = look.posterStyle,
            vibration = look.vibration,
            emoji = look.emoji
        )
    )

    /** Whatever the row holds, never a crash: a row written badly is the default look. */
    fun decode(text: String?): Look {
        if (text.isNullOrBlank() || text.length > MAX) return Look()
        val s = runCatching { json.decodeFromString(Stored.serializer(), text) }.getOrNull() ?: return Look()
        return Look(
            color = s.color,
            posterZoom = s.zoom.takeIf { it.isFinite() }?.coerceIn(1f, 4f) ?: 1f,
            posterX = s.x.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f,
            posterY = s.y.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.4f,
            posterStyle = s.style.takeIf { it in POSTER_STYLES } ?: "classic",
            vibration = s.vibration.takeIf { it in VIBRATIONS } ?: "",
            emoji = s.emoji.take(16)
        )
    }

    /** Any other app may write a row of this kind: a large one is not read. */
    private const val MAX = 4096
}

/** How the name is drawn on a poster. */
val POSTER_STYLES = listOf("classic", "bold", "light", "serif", "round")

/**
 * Vibrations a person can have, as on/off milliseconds: SMS's own
 * signatures, same names and patterns, so the one chosen here is the one
 * SMS plays.
 */
val VIBRATIONS: Map<String, LongArray> = linkedMapOf(
    "Heartbeat" to longArrayOf(0, 70, 110, 70, 700),
    "Double tap" to longArrayOf(0, 40, 90, 40),
    "Wave" to longArrayOf(0, 30, 40, 60, 40, 100, 40, 60, 40, 30),
    "Long" to longArrayOf(0, 550)
)
