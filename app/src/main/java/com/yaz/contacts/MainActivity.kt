package com.yaz.contacts

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.yaz.contacts.data.settings.SettingsStore
import com.yaz.contacts.navigation.ContactsApp
import com.yaz.contacts.navigation.Routes
import com.yaz.contacts.ui.theme.AppSurface
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/** The app opened from the launcher: the list of contacts. */
class MainActivity : ComponentActivity() {

    private val settings: SettingsStore by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Nothing of another app can float over Save or Delete.
        window.setHideOverlayWindows(true)
        setContent { AppSurface { ContactsApp(Routes.LIST) } }
        hideWhenAsked(this, settings)
    }
}

/**
 * The recent apps screen keeps a picture of the app: blank if the user
 * prefers (the default), so the contacts are not seen there.
 */
internal fun hideWhenAsked(activity: ComponentActivity, settings: SettingsStore) {
    // People who may be targeted (Android's Advanced Protection on): no
    // screenshot, recording or casting of the contacts at all.
    if (com.yaz.contacts.core.system.AdvancedProtection.isOn(activity)) {
        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    activity.lifecycleScope.launch {
        settings.settings.map { it.hideInRecents }.distinctUntilChanged().collect { hide -> activity.setRecentsScreenshotEnabled(!hide) }
    }
}
