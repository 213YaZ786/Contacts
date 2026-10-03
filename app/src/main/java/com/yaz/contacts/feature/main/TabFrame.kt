package com.yaz.contacts.feature.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yaz.contacts.ui.component.FloatingAction
import com.yaz.contacts.ui.component.FloatingFrame
import com.yaz.contacts.ui.component.FloatingTop
import com.yaz.contacts.ui.icon.AppIcons

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
    onTitle: (() -> Unit)? = null,
    onOpenTidy: (() -> Unit)? = null,
    controls: (@Composable () -> Unit)? = null,
    overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    FloatingFrame(
        bottom = 120.dp,
        top = {
            FloatingTop(
                title = title,
                center = onTitle?.let { open -> { TitleChoice(title, open) } },
                leading = onOpenTidy?.let { tidy -> { FloatingAction(AppIcons.Merge, "Tidy up", tidy) } },
                trailing = { FloatingAction(AppIcons.Settings, "Settings", onOpenSettings) }
            )
            Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.TopCenter) { SetupZone() }
            controls?.invoke()
        },
        overlay = overlay
    ) { padding ->
        Box(Modifier.fillMaxSize()) { content(padding) }
    }
}

/** The name of what is shown, a tap away from the other views. */
@Composable
private fun TitleChoice(title: String, onClick: () -> Unit) {
    val haptics = com.yaz.contacts.ui.component.rememberHaptics()
    com.yaz.contacts.ui.component.FloatingPane(shape = androidx.compose.foundation.shape.CircleShape, onClick = { haptics.tick(); onClick() }) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 18.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
        ) {
            androidx.compose.material3.Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, maxLines = 1)
            androidx.compose.material3.Icon(AppIcons.ExpandMore, null, modifier = Modifier.padding(start = 4.dp).size(20.dp))
        }
    }
}
