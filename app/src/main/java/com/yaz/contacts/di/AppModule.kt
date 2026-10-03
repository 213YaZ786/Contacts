package com.yaz.contacts.di

import com.yaz.contacts.data.contacts.ContactStore
import com.yaz.contacts.data.contacts.ContactWriter
import com.yaz.contacts.data.contacts.Trash
import com.yaz.contacts.data.settings.SettingsStore
import com.yaz.contacts.feature.settings.SettingsViewModel
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
    single { com.yaz.contacts.data.contacts.Snapshots(androidContext(), get(), get()) }
    single { com.yaz.contacts.core.handoff.Drafts() }
    single { com.yaz.contacts.feature.common.UndoState() }
    viewModelOf(::SettingsViewModel)
}
