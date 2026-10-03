package com.yaz.contacts.feature.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.core.animateFloat
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
    /** Something waits in Tidy up (people saved twice): a red dot on its button. */
    tidyWaiting: Boolean = false,
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
                leading = onOpenTidy?.let { tidy -> { TidyButton(tidyWaiting, tidy) } },
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

/**
 * The way to Tidy up. While something waits there, a red dot sits on the
 * button's upper right, breathing softly, and the icon gives a small shake
 * every few seconds, enough to be noticed, never a bother.
 */
@Composable
private fun TidyButton(waiting: Boolean, onClick: () -> Unit) {
    val loop = androidx.compose.animation.core.rememberInfiniteTransition(label = "tidy")
    val breath by loop.animateFloat(
        0.55f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "dot"
    )
    val shake = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(waiting) {
        while (waiting) {
            kotlinx.coroutines.delay(4_000)
            for (angle in listOf(-14f, 12f, -9f, 6f, -3f, 0f)) shake.animateTo(angle, androidx.compose.animation.core.tween(60))
        }
        shake.snapTo(0f)
    }
    Box(Modifier.graphicsLayer { rotationZ = shake.value }) {
        FloatingAction(AppIcons.Merge, if (waiting) "Tidy up, something to look at" else "Tidy up", onClick)
        if (waiting) Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 2.dp, end = 2.dp)
                .size(14.dp)
                .graphicsLayer { scaleX = 0.85f + 0.15f * breath; scaleY = 0.85f + 0.15f * breath; alpha = 0.6f + 0.4f * breath }
                .background(androidx.compose.ui.graphics.Color(0xFFE53935), androidx.compose.foundation.shape.CircleShape)
                .border(2.dp, androidx.compose.material3.MaterialTheme.colorScheme.surface, androidx.compose.foundation.shape.CircleShape)
        )
    }
}
