package com.contact.app.feature.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.contact.app.ui.component.FloatingAction
import com.contact.app.ui.component.FloatingFrame
import com.contact.app.ui.component.FloatingTop
import com.contact.app.ui.icon.AppIcons

/**
 * The main screen's frame. The content takes the whole screen, top to
 * bottom; its name, the way to tidy up and to Settings, the setup step
 * while it is missing and its own [controls] (search, filters) float over
 * it in glass.
 */
@Composable
fun TabFrame(
    title: String,
    onOpenSettings: () -> Unit,
    onOpenTidy: (() -> Unit)? = null,
    controls: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit
) {
    FloatingFrame(
        bottom = 120.dp,
        top = {
            FloatingTop(
                title = title,
                leading = onOpenTidy?.let { tidy -> { FloatingAction(AppIcons.Merge, "Tidy up", tidy) } },
                trailing = { FloatingAction(AppIcons.Settings, "Settings", onOpenSettings) }
            )
            Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.TopCenter) { SetupZone() }
            controls?.invoke()
        }
    ) { padding ->
        Box(Modifier.fillMaxSize()) { content(padding) }
    }
}
