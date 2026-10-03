package com.contact.app.feature.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.contact.app.ui.component.ContactAvatar
import com.contact.app.ui.component.FloatingPane
import com.contact.app.ui.component.ZoneAlertDialog
import com.contact.app.ui.component.ZoneSurface
import com.contact.app.ui.component.rememberHaptics
import com.contact.app.ui.icon.AppIcons
import com.contact.app.ui.theme.AlertRed
import com.contact.app.ui.theme.StarGold
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The widest a line of the list grows on a tablet. */
val LineWidth = 640.dp

/**
 * A person in a list: their face, their name, one line under it, the star;
 * a tap opens them, a long press offers the rest. [selected] marks it in a
 * choice of several.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PersonLine(
    name: String,
    photo: String?,
    subtitle: String?,
    starred: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val haptics = rememberHaptics()
    val shape = RoundedCornerShape(22.dp)
    ZoneSurface(
        shape = shape,
        accent = selected,
        modifier = modifier.widthIn(max = LineWidth).fillMaxWidth().clip(shape).combinedClickable(
            onClickLabel = "Open",
            onLongClickLabel = if (onLongPress != null) "More" else null,
            onClick = {
                haptics.tick()
                onOpen()
            },
            onLongClick = onLongPress
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)) {
            Box {
                ContactAvatar(name, photo, 44.dp)
                if (selected) {
                    Box(Modifier.align(Alignment.BottomEnd).size(18.dp).clip(CircleShape).graphicsLayer { }, contentAlignment = Alignment.Center) {
                        Icon(AppIcons.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (starred) {
                        Spacer(Modifier.width(6.dp))
                        Icon(AppIcons.Star, contentDescription = "Favorite", tint = StarGold, modifier = Modifier.size(16.dp))
                    }
                }
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            trailing?.invoke()
        }
    }
}

/** A favourite as a face with the first name under it, a tile of a grid that wraps. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FaceTile(name: String, photo: String?, onOpen: () -> Unit, onLongPress: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(
                onClick = {
                    haptics.tick()
                    onOpen()
                },
                onLongClick = onLongPress
            )
            .padding(vertical = 6.dp)
    ) {
        ContactAvatar(name, photo, 60.dp)
        Spacer(Modifier.size(6.dp))
        Text(
            name.substringBefore(' ').ifBlank { name },
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/** A heading over a part of a list: a letter, Favorites. */
@Composable
fun ListHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.widthIn(max = LineWidth).fillMaxWidth().padding(start = 8.dp, top = 10.dp)
    )
}

/** What was just done, with a way to take it back while the ring empties. */
@Stable
class UndoState {
    var shown by mutableStateOf<Undo?>(null)
        private set
    fun show(message: String, undo: (() -> Unit)?) {
        shown = Undo(message, undo, System.nanoTime())
    }
    fun clear() {
        shown = null
    }
    data class Undo(val message: String, val undo: (() -> Unit)?, val at: Long)
}

/** The one undo of the app, so a delete on a page can be taken back from the list it returns to. */
@Composable
fun rememberUndo(): UndoState = org.koin.compose.koinInject()

/** The undo pill of glass at the bottom, gone after five seconds. */
@Composable
fun BoxScope.UndoPill(state: UndoState, bottom: androidx.compose.ui.unit.Dp = 24.dp) {
    val shown = state.shown
    val haptics = rememberHaptics()
    val ring = remember { Animatable(1f) }
    LaunchedEffect(shown?.at) {
        if (shown == null) return@LaunchedEffect
        ring.snapTo(1f)
        ring.animateTo(0f, tween(UNDO_MS, easing = LinearEasing))
        state.clear()
    }
    AnimatedVisibility(
        visible = shown != null,
        enter = slideInVertically(spring(dampingRatio = 0.6f)) { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = bottom, start = 16.dp, end = 16.dp)
    ) {
        var last by remember { mutableStateOf<UndoState.Undo?>(null) }
        shown?.let { last = it }
        val current = last ?: return@AnimatedVisibility
        FloatingPane(shape = CircleShape) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)) {
                Text(current.message, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp))
                current.undo?.let { undo ->
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        haptics.firm()
                        state.clear()
                        undo()
                    }) {
                        Box(contentAlignment = Alignment.Center) {
                            androidx.compose.foundation.Canvas(Modifier.size(30.dp)) {
                                drawArc(
                                    color = androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.35f),
                                    startAngle = -90f, sweepAngle = 360f * ring.value, useCenter = false,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                                )
                            }
                            Icon(AppIcons.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text("Undo")
                    }
                } ?: Spacer(Modifier.width(14.dp))
            }
        }
    }
}

private const val UNDO_MS = 5000

/** "Delete N?" with what happens: kept in the trash, so it can come back. */
@Composable
fun DeleteQuestion(what: String, days: Int, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val haptics = rememberHaptics()
    ZoneAlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.Delete, contentDescription = null, tint = AlertRed) },
        title = { Text("Delete $what?") },
        text = { Text("Kept in the trash for $days days, then gone. A synced account deletes it there too.") },
        confirmButton = {
            TextButton(onClick = {
                haptics.reject()
                onDelete()
            }) { Text("Delete", color = AlertRed) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Runs [block] in a scope tied to the screen. */
@Composable
fun rememberLauncher(): (suspend () -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    return { block -> scope.launch { block() } }
}

/** A short wait before a motion that follows another. */
suspend fun settle(ms: Long = 120) = delay(ms)

/** Space between items of a list. */
val ListGap = Arrangement.spacedBy(8.dp)
