package com.yaz.contacts

import android.app.Application
import com.yaz.contacts.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class ContactsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The isolated picture decoder is a process of this app with no
        // rights at all: nothing of the app starts there.
        if (android.os.Process.isIsolated()) return
        startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.DEBUG else Level.NONE)
            androidContext(this@ContactsApplication)
            modules(appModule)
        }
    }
}
