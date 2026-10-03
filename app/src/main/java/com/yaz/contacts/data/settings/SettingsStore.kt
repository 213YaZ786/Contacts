package com.yaz.contacts.data.settings

import android.content.Context
import com.yaz.contacts.core.common.writeTextAtomically
import com.yaz.contacts.core.update.UpdateMode
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
data class Settings(
    /**
     * What happens when a newer version is out, checked once when the app
     * opens. Installing by default: the first launch page says so, and that
     * this one request is the app's only use of the internet.
     */
    val updates: UpdateMode = UpdateMode.INSTALL,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** True black instead of dark grey in dark mode. */
    val pureBlack: Boolean = false,
    /** Zones and floating controls in liquid glass, over a soft light in the wallpaper's colours. */
    val glass: Boolean = true,
    /** Multiplier on every text style, one of the steps in ui.theme.TEXT_SCALES. */
    val textScale: Float = 1f,
    /** The first launch page was closed. */
    val welcomeSeen: Boolean = false,
    /** The app's picture in the recent apps screen stays blank: no contacts to be seen there. */
    val hideInRecents: Boolean = true,
    /** The list sorted by first name or by last name. */
    val sortOrder: com.yaz.contacts.core.contacts.SortOrder = com.yaz.contacts.core.contacts.SortOrder.FIRST_NAME,
    /** Names shown last name first ("Martin, Joëlle"). */
    val lastNameFirst: Boolean = false,
    /** The account new contacts go to, by its key ("" the phone itself); null until chosen, then the first sync account. */
    val defaultAccount: String? = null,
    /** Only the contacts of these accounts in the list; empty for all. */
    val shownAccounts: Set<String> = emptySet(),
    /** Where the user left the new contact button, fractions of its room; below 0, its usual place. */
    val addX: Float = -1f,
    val addY: Float = -1f,
    /** A deleted contact waits this many days in the trash before it is gone. */
    val trashDays: Int = 30,
    /** Only people with a phone number in the list. */
    val onlyWithNumbers: Boolean = false,
    /** What stays out of the user's own card when shared (QR, touching phones): "tel:…", "mail:…", "web:…", "adr", "work", "bday". */
    val keptBack: Set<String> = emptySet()
)

/**
 * Small preference file, plain JSON written atomically, like the other apps.
 * Nothing here is a secret, and none of it leaves the device: the
 * contacts themselves are only in Android's store.
 */
class SettingsStore(context: Context) {

    private val file = File(context.filesDir, "settings.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    val current: Settings get() = _settings.value

    private fun load(): Settings {
        if (!file.exists()) return Settings()
        return runCatching { json.decodeFromString<Settings>(file.readText()) }
            .getOrDefault(Settings())
    }

    fun update(transform: (Settings) -> Settings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        runCatching { file.writeTextAtomically(json.encodeToString(updated)) }
    }
}
