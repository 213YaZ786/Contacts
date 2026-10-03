package com.yaz.contacts.core.system

import android.content.Context
import android.os.Build
import android.security.advancedprotection.AdvancedProtectionManager

/**
 * Android's Advanced Protection (Android 16 and later), the switch for
 * people who may be targeted. When it is on, Contacts keeps the screen out of
 * screenshots and recordings, whatever its own settings say.
 */
object AdvancedProtection {

    fun isOn(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
        return runCatching {
            context.getSystemService(AdvancedProtectionManager::class.java)?.isAdvancedProtectionEnabled == true
        }.getOrDefault(false)
    }
}
