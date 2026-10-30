package com.yaz.contacts.feature.list

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ShapeDefaults
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yaz.contacts.ui.component.BoldButton
import com.yaz.contacts.ui.component.QuietButton
import com.yaz.contacts.ui.component.ZoneSurface
import com.yaz.contacts.ui.component.rememberHaptics
import com.yaz.contacts.ui.icon.AppIcons

/**
 * No contact yet: importing them is the first thing to do, so it stands in
 * the middle, with how to take them out of the phone they were on.
 */
@Composable
internal fun FirstImport(padding: PaddingValues, onImportFile: () -> Unit, onImportSim: () -> Unit) {
    val haptics = rememberHaptics()
    // Centred while it fits; longer (the steps open), it scrolls under the floating panes.
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
    Box(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(padding).padding(horizontal = 16.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(AppIcons.Contacts, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(12.dp))
            Text("No contacts yet", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            BoldButton(onClick = { haptics.firm(); onImportFile() }, filled = true, modifier = Modifier.heightIn(min = 56.dp)) {
                Icon(AppIcons.Download, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text("Import contacts", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(4.dp))
            QuietButton(onClick = { haptics.tick(); onImportSim() }) {
                Icon(AppIcons.SimCard, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("From the SIM card")
            }
            Spacer(Modifier.height(24.dp))
            Text("Take them out of your old phone into a .vcf file, then import it here", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Steps("Android, Google Contacts", listOf("Open Contacts", "Fix & manage", "Export to file", "Choose the account, then save the .vcf"))
                Steps("Samsung Galaxy", listOf("Open Contacts", "Menu ☰", "Manage contacts", "Import or export contacts", "Export"))
                Steps("iPhone", listOf("Open Contacts", "Lists", "Hold All contacts", "Export, then send the file to this phone"))
            }
            // Room for the round add button floating over the end of the page.
            Spacer(Modifier.height(96.dp))
        }
    }
    }
}

/** A phone's way out, folded to its name; a tap shows the steps. */
@Composable
private fun Steps(title: String, steps: List<String>) {
    val haptics = rememberHaptics()
    var open by rememberSaveable(title) { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "turn")
    // A card on the page: Material 3's extra large corner, 28 dp (ShapeDefaults,
    // https://developer.android.com/reference/kotlin/androidx/compose/material3/ShapeDefaults).
    ZoneSurface(shape = ShapeDefaults.ExtraLarge, onClick = { haptics.tick(); open = !open }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(AppIcons.ExpandMore, contentDescription = if (open) "Hide the steps" else "Show the steps", modifier = Modifier.graphicsLayer { rotationZ = turn })
            }
            AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    steps.forEachIndexed { i, step ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(22.dp))
                            Text(step, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
