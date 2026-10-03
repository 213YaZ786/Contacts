package com.contacts.app.di

import com.contacts.app.data.contacts.ContactStore
import com.contacts.app.data.contacts.ContactWriter
import com.contacts.app.data.contacts.Trash
import com.contacts.app.data.settings.SettingsStore
import com.contacts.app.feature.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/** Single composition root. */
val appModule = module {
    single { SettingsStore(androidContext()) }
    single { ContactStore(androidContext(), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) }
    single { ContactWriter(androidContext()) }
    single { Trash(androidContext(), get()) }
    single { com.contacts.app.core.handoff.Drafts() }
    single { com.contacts.app.feature.common.UndoState() }
    viewModelOf(::SettingsViewModel)
}
