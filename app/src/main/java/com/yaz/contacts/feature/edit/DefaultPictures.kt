package com.yaz.contacts.feature.edit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yaz.contacts.ui.component.ZoneAlertDialog
import com.yaz.contacts.ui.component.rememberHaptics
import com.yaz.contacts.ui.icon.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A person's picture: one of the user's photos (the Photo Picker), or one
 * of the ready ones, Chromium's default profile pictures (BSD, notice in
 * assets/avatars/NOTICE.txt): origami, illustrations, abstract, patterns.
 */
@Composable
fun PictureChoice(onPhotos: () -> Unit, onPicked: (Bitmap) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val names = remember { context.assets.list("avatars").orEmpty().filter { it.endsWith(".webp") }.sorted() }
    val groups = remember(names) {
        listOf("origami" to "Origami", "illustration" to "Drawings", "abstract" to "Abstract", "geo" to "Patterns")
            .map { (key, title) -> title to names.filter { it.startsWith(key + "_") } }
            .filter { it.second.isNotEmpty() }
    }
    ZoneAlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.AddPhoto, null) },
        title = { Text("Their picture") },
        text = {
            // The kinds on top, each its own grid: no long scroll through all of them.
            var tab by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = { haptics.tick(); onPhotos() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(AppIcons.Photo, null)
                    Text("From your photos", modifier = Modifier.padding(start = 8.dp))
                }
                com.yaz.contacts.feature.common.EvenRows(minSlot = 88.dp) {
                    groups.forEachIndexed { i, (title, _) ->
                        com.yaz.contacts.feature.common.TextControl(title, tab == i) { haptics.tick(); tab = i }
                    }
                }
                val files = groups.getOrNull(tab)?.second.orEmpty()
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(64.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.heightIn(max = 380.dp)
                ) {
                    items(files, key = { it }) { name ->
                        val thumb by produceState<ImageBitmap?>(null, name) { value = withContext(Dispatchers.IO) { load(context, name)?.asImageBitmap() } }
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape).clickable(onClickLabel = name.substringAfter('_').substringBefore('.')) {
                                haptics.done()
                                load(context, name)?.let(onPicked)
                            },
                            contentAlignment = Alignment.Center
                        ) {
                            thumb?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** A ready picture, drawn at twice its size so it stays smooth on a large face. */
private fun load(context: android.content.Context, name: String): Bitmap? = runCatching {
    val small = context.assets.open("avatars/$name").use { BitmapFactory.decodeStream(it) } ?: return@runCatching null
    Bitmap.createScaledBitmap(small, small.width * 2, small.height * 2, true)
}.getOrNull()
