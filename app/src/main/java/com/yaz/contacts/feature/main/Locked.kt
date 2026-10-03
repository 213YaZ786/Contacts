package com.yaz.contacts.feature.main

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.yaz.contacts.core.security.AppLock
import com.yaz.contacts.data.settings.SettingsStore
import com.yaz.contacts.ui.component.BoldButton
import com.yaz.contacts.ui.component.LoadingMark
import org.koin.compose.koinInject

/**
 * Shows [content] only once the user unlocked, when they chose to lock
 * Contacts; else straight away. Nothing of the contacts is drawn behind.
 */
@Composable
fun ComponentActivity.Locked(content: @Composable () -> Unit) {
    val store: SettingsStore = koinInject()
    val settings by store.settings.collectAsState()
    // Locked only while the phone has a lock of its own to open it with.
    if (!settings.lock || !AppLock.possible(this)) {
        content()
        return
    }
    val open by AppLock.open.collectAsState()
    LifecycleResumeEffect(Unit) {
        AppLock.back(settings.lockAfterSeconds * 1000L)
        onPauseOrDispose { AppLock.leave() }
    }
    if (open) {
        content()
        return
    }
    val activity = this
    LaunchedEffect(Unit) { AppLock.ask(activity) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        modifier = Modifier.fillMaxSize().padding(24.dp)
    ) {
        LoadingMark(size = 120.dp, running = false)
        Text("Contacts is locked", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        BoldButton(filled = true, onClick = { AppLock.ask(activity) }) { Text("Unlock") }
    }
}
