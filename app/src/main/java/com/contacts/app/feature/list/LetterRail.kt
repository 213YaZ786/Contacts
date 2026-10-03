package com.contacts.app.feature.list

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.contacts.app.ui.component.ZoneSurface
import com.contacts.app.ui.component.rememberHaptics
import kotlin.math.roundToInt

/**
 * The letters of the list on a slim pane of glass at the edge: the finger
 * slides along it and the list jumps from letter to letter, a tick each,
 * the letter large beside the finger.
 */
@Composable
fun LetterRail(letters: List<String>, onLetter: (String) -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val jump by rememberUpdatedState(onLetter)
    var height by remember { mutableFloatStateOf(1f) }
    var touched by remember { mutableStateOf<String?>(null) }
    var fingerY by remember { mutableFloatStateOf(0f) }

    Row(modifier, verticalAlignment = Alignment.Top) {
        // The letter under the finger, large, level with it.
        Box(Modifier.width(64.dp)) {
            androidx.compose.animation.AnimatedVisibility(
                visible = touched != null,
                enter = scaleIn(initialScale = 0.6f) + fadeIn(),
                exit = scaleOut(targetScale = 0.6f) + fadeOut(),
                modifier = Modifier.offset { IntOffset(0, (fingerY - with(density) { 28.dp.toPx() }).roundToInt()) }
            ) {
                var last by remember { mutableStateOf("") }
                touched?.let { last = it }
                ZoneSurface(shape = CircleShape, accent = true, modifier = Modifier.size(56.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(last, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        ZoneSurface(
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .width(28.dp)
                .onSizeChanged { height = it.height.toFloat().coerceAtLeast(1f) }
                .semantics { contentDescription = "Jump to a letter" }
                .pointerInput(letters) {
                    fun at(y: Float): String {
                        fingerY = y.coerceIn(0f, height)
                        val i = (fingerY / height * letters.size).toInt().coerceIn(0, letters.size - 1)
                        return letters[i]
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        var current = at(down.position.y)
                        touched = current
                        haptics.tick()
                        jump(current)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            val letter = at(change.position.y)
                            if (letter != current) {
                                current = letter
                                touched = letter
                                haptics.tick()
                                jump(letter)
                            }
                        }
                        touched = null
                    }
                }
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 8.dp)) {
                letters.forEach { letter ->
                    Text(
                        letter,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (letter == touched) FontWeight.Bold else FontWeight.Medium,
                        color = if (letter == touched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
}
