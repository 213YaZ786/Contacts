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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import com.yaz.contacts.ui.component.groupLabelSize
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
 * Items that fill the whole width: as many on a row as the slot allows,
 * the rows balanced (seven make four and three, not six and one), and each
 * item stretched to its share, so no row ends on empty space. The slot is
 * [minSlot] or, when larger, what the widest item needs at the current font
 * scale and language: its minimum intrinsic width, the widest word of its text
 * plus its padding, as androidx's own FlowLayout asks its children
 * (https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation-layout/src/commonMain/kotlin/androidx/compose/foundation/layout/FlowLayout.kt,
 * https://developer.android.com/develop/ui/compose/layouts/intrinsic-measurements).
 */
@Composable
fun EvenRows(modifier: Modifier = Modifier, minSlot: Dp = 52.dp, gap: Dp = 8.dp, content: @Composable () -> Unit) {
    // The labels of its tiles and the one size they share (see GroupText).
    val group = remember { GroupText() }
    val measurer = rememberTextMeasurer()
    CompositionLocalProvider(LocalGroupText provides group) {
    Layout(content = content, modifier = modifier.fillMaxWidth()) { measurables, constraints ->
        val n = measurables.size
        if (n == 0) return@Layout layout(constraints.maxWidth, 0) {}
        val width = constraints.maxWidth
        val g = gap.roundToPx()
        // The widest word of any item, asked of the items themselves; one that cannot answer counts for nothing.
        val word = measurables.maxOf { runCatching { it.minIntrinsicWidth(constraints.maxHeight) }.getOrDefault(0) }
        val fit = ((width + g) / (maxOf(minSlot.roundToPx(), word) + g)).coerceIn(1, n)
        val rows = ceil(n / fit.toFloat()).toInt()
        val perRow = ceil(n / rows.toFloat()).toInt()
        group.fit(measurer, this, ((width - g * (perRow - 1)) / perRow).toDp())
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
}

/**
 * The words of an [EvenRows]' tiles and the ONE size they are all drawn at: every TileText registers its
 * label here, the rows (which know the slot a tile gets) compute the largest size at which the longest word of
 * every label fits the slot less the tile's padding, groupLabelSize in ui/component/GroupLabelSize.kt (the ladder of
 * TextAutoSize.StepBased, asked of the whole group,
 * https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/text/AutoSize.kt).
 * The size is a state the tiles read, written from the rows' layout as Modifier.onSizeChanged feeds a state
 * (https://developer.android.com/develop/ui/compose/state); it depends on the labels and the slot only, so it settles at once.
 */
private class GroupText {
    class Label(val text: String, val style: TextStyle, val side: Dp)

    val labels = mutableStateMapOf<Any, Label>()
    var size by mutableStateOf(TextUnit.Unspecified)

    /** The size for a tile [slot] wide (measured with TextMeasurer, https://developer.android.com/develop/ui/compose/text/measure-text-layout). */
    fun fit(measurer: TextMeasurer, density: Density, slot: Dp) {
        val all = labels.values.toList()
        if (all.isEmpty()) return
        val style = all.maxBy { it.style.fontSize.value }.style
        val next = groupLabelSize(measurer, density, all.map { it.text }, style, slot - all.maxOf { it.side } * 2)
        if (next != size) size = next
    }
}

private val LocalGroupText = compositionLocalOf<GroupText?> { null }

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
    // A tile on the page: Material 3's extra large corner, 28 dp (ShapeDefaults,
    // https://developer.android.com/reference/kotlin/androidx/compose/material3/ShapeDefaults).
    val shape = ShapeDefaults.ExtraLarge
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
            TileText(label, MaterialTheme.typography.labelMedium, 4.dp)
        }
    }
}

/**
 * The words of a tile or a pill: lines break between words only, and a single
 * word still too wide shrinks as the last resort, Compose's TextAutoSize.StepBased
 * (https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/text/TextAutoSize.kt),
 * as the label of PersonActionTile in ui/component/PersonActions.kt. In an [EvenRows] the tile has no
 * autosize of its own: it registers its label and draws at the one size the rows computed for the whole
 * group ([GroupText]); [side] is the tile's padding each side of the word.
 */
@Composable
private fun TileText(text: String, style: TextStyle, side: Dp) {
    val group = LocalGroupText.current
    if (group == null) {
        BasicText(
            text,
            style = style.copy(color = LocalContentColor.current, textAlign = TextAlign.Center),
            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp)
        )
        return
    }
    DisposableEffect(text, style, side) {
        val key = Any()
        group.labels[key] = GroupText.Label(text, style, side)
        onDispose { group.labels.remove(key) }
    }
    val size = if (group.size.isSpecified && group.size.value < style.fontSize.value) group.size else style.fontSize
    BasicText(text, style = style.copy(color = LocalContentColor.current, textAlign = TextAlign.Center, fontSize = size))
}

/**
 * A choice of an [EvenRows] with a word in it, floating in glass: a filter.
 * At least 48 dp tall like every control here (a smaller one is grown to 48 by
 * Material for the finger and spills over the gap, and two rows touch), taller
 * when its word wraps at a larger font or in a longer language.
 */
@Composable
fun TextControl(text: String, chosen: Boolean, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    FloatingPane(shape = CircleShape, accent = chosen, onClick = { haptics.tick(); onClick() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            TileText(text, MaterialTheme.typography.labelLarge, 8.dp)
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
