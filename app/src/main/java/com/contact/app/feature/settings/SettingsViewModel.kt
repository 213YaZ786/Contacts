package com.contact.app.feature.settings

import androidx.lifecycle.ViewModel
import com.contact.app.core.contacts.SortOrder
import com.contact.app.core.update.UpdateMode
import com.contact.app.data.settings.Settings
import com.contact.app.data.settings.SettingsStore
import com.contact.app.data.settings.ThemeMode
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(val store: SettingsStore) : ViewModel() {

    val settings: StateFlow<Settings> = store.settings

    fun setTheme(mode: ThemeMode) = store.update { it.copy(themeMode = mode) }
    fun setPureBlack(on: Boolean) = store.update { it.copy(pureBlack = on) }
    fun setGlass(on: Boolean) = store.update { it.copy(glass = on) }
    fun setTextScale(scale: Float) = store.update { it.copy(textScale = scale) }
    fun setUpdates(mode: UpdateMode) = store.update { it.copy(updates = mode) }
    fun setHideInRecents(on: Boolean) = store.update { it.copy(hideInRecents = on) }
    fun setSortOrder(order: SortOrder) = store.update { it.copy(sortOrder = order) }
    fun setLastNameFirst(on: Boolean) = store.update { it.copy(lastNameFirst = on) }
    fun setDefaultAccount(key: String) = store.update { it.copy(defaultAccount = key) }
    fun setTrashDays(days: Int) = store.update { it.copy(trashDays = days) }
}
