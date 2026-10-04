package com.yaz.contacts.feature.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yaz.contacts.ui.component.FloatingPane
import com.yaz.contacts.ui.component.ZoneSurface
import com.yaz.contacts.ui.component.rememberHaptics
import kotlin.math.ceil

/**
 * Items that fill the whole width: as many on a row as [minSlot] allows,
 * the rows balanced (seven make four and three, not six and one), and each
 * item stretched to its share, so no row ends on empty space.
 */
@Composable
fun EvenRows(modifier: Modifier = Modifier, minSlot: Dp = 52.dp, gap: Dp = 8.dp, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier.fillMaxWidth()) { measurables, constraints ->
        val n = measurables.size
        if (n == 0) return@Layout layout(constraints.maxWidth, 0) {}
        val width = constraints.maxWidth
        val g = gap.roundToPx()
        val fit = ((width + g) / (minSlot.roundToPx() + g)).coerceIn(1, n)
        val rows = ceil(n / fit.toFloat()).toInt()
        val perRow = ceil(n / rows.toFloat()).toInt()
        val placeables = measurables.chunked(perRow).map { row ->
            val slot = (width - g * (row.size - 1)) / row.size
            row.map { it.measure(Constraints.fixedWidth(slot).copy(minHeight = 0, maxHeight = constraints.maxHeight)) }
        }
        val heights = placeables.map { row -> row.maxOf { it.height } }
        val height = heights.sum() + g * (heights.size - 1)
        layout(width, height) {
            var y = 0
            placeables.forEachIndexed { r, row ->
                var x = 0
                for (p in row) {
                    p.placeRelative(x, y + (heights[r] - p.height) / 2)
                    x += p.width + g
                }
                y += heights[r] + g
            }
        }
    }
}

/**
 * An action of a person or a label: a tile of glass as wide as its share,
 * its icon over its word, sinking under the finger.
 */
@Composable
fun ActionTile(icon: ImageVector, label: String, tint: Color? = null, accent: Boolean = false, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val sink by animateFloatAsState(if (pressed) 0.92f else 1f, spring(dampingRatio = 0.45f, stiffness = 700f), label = "sink")
    val shape = RoundedCornerShape(22.dp)
    ZoneSurface(
        shape = shape,
        accent = accent,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = sink
                scaleY = sink
            }
            .clip(shape)
            .clickable(interactionSource = press, indication = null, role = Role.Button, onClickLabel = label) {
                haptics.tick()
                onClick()
            }
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp)) {
            Icon(icon, contentDescription = null, tint = tint ?: MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

/**
 * A choice of an [EvenRows] with a word in it, floating in glass: a filter.
 * 48 dp tall like every control here: a smaller one is grown to 48 by
 * Material for the finger and spills over the gap, and two rows touch.
 */
@Composable
fun TextControl(text: String, chosen: Boolean, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    FloatingPane(shape = CircleShape, accent = chosen, onClick = { haptics.tick(); onClick() }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

/** A control of an [EvenRows]: a pill of glass as wide as its share, an icon in it. */
@Composable
fun IconControl(icon: ImageVector, label: String, tint: Color = Color.Unspecified, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    FloatingPane(
        shape = CircleShape,
        onClick = { haptics.tick(); onClick() },
        modifier = Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = label }
    ) {
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint.takeOrElse { MaterialTheme.colorScheme.primary }, modifier = Modifier.size(22.dp))
        }
    }
}
