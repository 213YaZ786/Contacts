package com.yaz.contacts.navigation

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yaz.contacts.BuildConfig
import com.yaz.contacts.core.contacts.Details
import com.yaz.contacts.core.handoff.Drafts
import com.yaz.contacts.core.handoff.PickKind
import com.yaz.contacts.data.settings.SettingsStore
import com.yaz.contacts.feature.common.UndoPill
import com.yaz.contacts.feature.common.rememberUndo
import com.yaz.contacts.feature.contact.ContactScreen
import com.yaz.contacts.feature.edit.EditorScreen
import com.yaz.contacts.feature.list.ListScreen
import com.yaz.contacts.feature.main.WelcomeScreen
import com.yaz.contacts.feature.pick.ChooseScreen
import com.yaz.contacts.feature.pick.ImportScreen
import com.yaz.contacts.feature.pick.PickScreen
import com.yaz.contacts.feature.settings.SettingsScreen
import com.yaz.contacts.feature.tidy.LabelScreen
import com.yaz.contacts.feature.tidy.TidyScreen
import com.yaz.contacts.feature.tidy.TrashScreen
import com.yaz.contacts.ui.component.UpdatePrompt
import com.yaz.contacts.ui.component.rememberHaptics
import com.yaz.contacts.ui.glass.LocalGlass
import com.yaz.contacts.ui.glass.LocalGlassBackdrop
import com.yaz.contacts.ui.glass.glassFloating
import com.yaz.contacts.ui.glass.glassGround
import com.yaz.contacts.ui.glass.glassSource
import com.yaz.contacts.ui.glass.rememberGlassBackdrop
import com.yaz.contacts.ui.icon.AppIcons
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/**
 * How the app was opened: from the launcher (the list), or by another app
 * asking for one thing, which ends by going back to that app with [finish]
 * (a result for a pick or a save, nothing when cancelled).
 */
@Composable
fun ContactsApp(start: String, finish: ((Intent?) -> Unit)? = null, then: androidx.compose.runtime.MutableState<String?>? = null) {
    val navController = rememberNavController()
    // A screen asked from outside the list (an icon shortcut), opened over it once.
    val next = then?.value
    androidx.compose.runtime.LaunchedEffect(next) {
        if (next != null) {
            navController.popBackStack(start, inclusive = false)
            navController.navigate(next)
            then.value = null
        }
    }
    // The only owner of the window insets: screens below draw under the bars
    // and take them as padding themselves. Transparent, because the page's
    // ground with its ambient light is painted once under the whole app.
    Scaffold(containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground) { _ ->
        Box(Modifier.fillMaxSize()) {
            ContactsNavHost(navController, start, finish)
            UndoPill(rememberUndo())
        }
    }
}

@Composable
private fun ContactsNavHost(nav: NavHostController, start: String, finish: ((Intent?) -> Unit)?) {
    val drafts: Drafts = koinInject()
    /** Back: the screen before, or the app that asked when this was its only screen. */
    fun back() {
        if (nav.previousBackStackEntry != null) nav.popBackStack() else finish?.invoke(null)
    }

    NavHost(
        navController = nav,
        startDestination = start,
        // Opening scales up from slightly small, going back scales down, the
        // same motion as the other apps.
        enterTransition = { scaleIn(initialScale = 0.94f, animationSpec = tween(NAV_MS)) + fadeIn(animationSpec = tween(NAV_MS)) },
        exitTransition = { fadeOut(animationSpec = tween(NAV_MS)) },
        popEnterTransition = { fadeIn(animationSpec = tween(NAV_MS)) },
        popExitTransition = { scaleOut(targetScale = 0.94f, animationSpec = tween(NAV_MS)) + fadeOut(animationSpec = tween(NAV_MS)) },
        modifier = Modifier.fillMaxSize()
    ) {
        composable(Routes.LIST) {
            androidx.compose.runtime.CompositionLocalProvider(
                com.yaz.contacts.feature.list.LocalImport provides { held -> nav.navigate("import?uri=" + Uri.encode("text:$held")) }
            ) {
            Main(
                onOpen = { nav.navigate(Routes.contact(it)) },
                onMakeMe = { nav.navigate(Routes.edit(me = true)) },
                onEdit = { nav.navigate(Routes.edit(it)) },
                onScan = { nav.navigate(Routes.SCAN) },
                onPoster = { nav.navigate(Routes.poster(it)) },
                onTap = { nav.navigate(Routes.tap(true)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenTidy = { nav.navigate(Routes.TIDY) },
                onAdd = { nav.navigate(Routes.edit()) }
            )
            }
        }
        composable(Routes.CONTACT, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@composable
            ReadableScroll {
                ContactScreen(
                    id = id,
                    onBack = ::back,
                    onEdit = { nav.navigate(Routes.edit(it)) },
                    onDeleted = ::back,
                    onScan = { nav.navigate(Routes.SCAN) },
                    onTap = { nav.navigate(Routes.tap(true)) },
                    onPoster = { nav.navigate(Routes.poster(it)) },
                    onMoved = { moved ->
                        nav.popBackStack()
                        nav.navigate(Routes.contact(moved))
                    }
                )
            }
        }
        composable(
            Routes.EDIT,
            arguments = listOf(
                navArgument("id") { type = NavType.LongType; defaultValue = -1L },
                navArgument("draft") { type = NavType.LongType; defaultValue = -1L },
                navArgument("me") { type = NavType.BoolType; defaultValue = false }
            )
        ) { entry ->
            val id = entry.arguments?.getLong("id")?.takeIf { it >= 0 }
            val me = entry.arguments?.getBoolean("me") == true
            val prefill = entry.arguments?.getLong("draft")?.takeIf { it >= 0 }?.let { drafts.peek(it) as? Details }
            ReadableScroll {
                EditorScreen(
                    contactId = id,
                    prefill = prefill,
                    me = me,
                    onClose = ::back,
                    onSaved = { saved ->
                        if (finish != null) {
                            // Asked by another app: back to it, with the contact saved.
                            finish(Intent().setData(saved.uri))
                        } else if (id == null) {
                            nav.popBackStack()
                            nav.navigate(Routes.contact(saved.contactId))
                        } else nav.popBackStack()
                    }
                )
            }
        }
        composable(Routes.CHOOSE, arguments = listOf(navArgument("draft") { type = NavType.LongType; defaultValue = -1L })) { entry ->
            val draft = entry.arguments?.getLong("draft") ?: -1L
            ReadableScroll {
                ChooseScreen(
                    adding = drafts.peek(draft) as? Details,
                    onClose = ::back,
                    onNew = { nav.navigate(Routes.edit(null, draft)) },
                    onExisting = { nav.navigate(Routes.edit(it, draft)) }
                )
            }
        }
        composable(Routes.PICK, arguments = listOf(navArgument("kind") { type = NavType.StringType })) { entry ->
            val kind = runCatching { PickKind.valueOf(entry.arguments?.getString("kind").orEmpty()) }.getOrDefault(PickKind.CONTACT)
            ReadableScroll {
                PickScreen(
                    kind = kind,
                    title = when (kind) {
                        PickKind.CONTACT -> "Choose a contact"
                        PickKind.PHONE -> "Choose a number"
                        PickKind.EMAIL -> "Choose an email"
                        PickKind.POSTAL -> "Choose an address"
                    },
                    onClose = ::back,
                    onPicked = { uri ->
                        // Only this row, and only to read.
                        finish?.invoke(Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }
                )
            }
        }
        composable(Routes.IMPORT, arguments = listOf(navArgument("uri") { type = NavType.StringType; defaultValue = "" })) { entry ->
            val raw = entry.arguments?.getString("uri").orEmpty()
            // "text:N" is cards held in Drafts (the SIM's), anything else a file.
            val held = raw.removePrefix("text:").takeIf { raw.startsWith("text:") }?.toLongOrNull()?.let { drafts.peek(it) as? String }
            ReadableScroll {
                ImportScreen(
                    source = if (held == null && raw.isNotBlank()) Uri.parse(raw) else null,
                    text = held,
                    onClose = ::back,
                    onDone = { if (finish != null) finish(null) else back() }
                )
            }
        }
        composable(Routes.SETTINGS) { ReadableScroll { SettingsScreen(onBack = ::back) } }
        composable(Routes.TIDY) {
            ReadableScroll {
                TidyScreen(
                    onBack = ::back,
                    onOpen = { nav.navigate(Routes.contact(it)) },
                    onTrash = { nav.navigate(Routes.TRASH) },
                    onUndo = { nav.navigate(Routes.UNDO) },
                    onScan = { nav.navigate(Routes.SCAN) },
                    onTap = { nav.navigate(Routes.tap(false)) },
                    onLabel = { nav.navigate(Routes.label(it)) },
                    onImport = { nav.navigate(Routes.import(it)) },
                    onImportText = { text -> nav.navigate("import?uri=" + Uri.encode("text:" + drafts.put(text))) }
                )
            }
        }
        composable(Routes.POSTER, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@composable
            com.yaz.contacts.feature.contact.PosterScreen(id, onBack = ::back)
        }
        composable(Routes.TAP, arguments = listOf(navArgument("give") { type = NavType.BoolType; defaultValue = true })) { entry ->
            val give = entry.arguments?.getBoolean("give") != false
            val store: com.yaz.contacts.data.contacts.ContactStore = koinInject()
            // The user's own card as it goes to the other phone, and their poster, read once.
            val context = androidx.compose.ui.platform.LocalContext.current
            val me by androidx.compose.runtime.produceState<com.yaz.contacts.core.contacts.Details?>(null) {
                if (give) value = store.me()?.let { store.details(it.id) }
            }
            val mePhoto by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, me) {
                val d = me ?: return@produceState
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        android.provider.ContactsContract.Contacts.openContactPhotoInputStream(context.contentResolver, android.provider.ContactsContract.Profile.CONTENT_URI, true)?.use { it.readBytes() }
                    }.getOrNull()?.let { com.yaz.contacts.core.security.SafeImages.decode(context, it, 1440) }
                }?.asImageBitmap()
            }
            val card = me?.let { com.yaz.contacts.core.vcard.VCard.short(it, look = true) }
            com.yaz.contacts.feature.nfc.TapScreen(give = give, me = me, mePhoto = mePhoto, card = card, onBack = ::back, onCard = { text ->
                nav.popBackStack()
                nav.navigate("import?uri=" + Uri.encode("text:" + drafts.put(text)))
            })
        }
        composable(Routes.SCAN) {
            com.yaz.contacts.feature.scan.ScanScreen(onBack = ::back, onCard = { text ->
                nav.popBackStack()
                nav.navigate("import?uri=" + Uri.encode("text:" + drafts.put(text)))
            })
        }
        composable(Routes.UNDO) { ReadableScroll { com.yaz.contacts.feature.tidy.UndoScreen(onBack = ::back) } }
        composable(Routes.TRASH) { ReadableScroll { TrashScreen(onBack = ::back, onRestored = { }) } }
        composable(Routes.LABEL, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@composable
            ReadableScroll { LabelScreen(id, onBack = ::back, onOpen = { nav.navigate(Routes.contact(it)) }) }
        }
    }
    // After the NavHost, so it takes the back gesture before the NavHost's
    // predictive pop can.
    PlainBack(nav)
}

/** The list, the way to a new contact floating over it, the first launch page until closed. */
@Composable
private fun Main(onOpen: (Long) -> Unit, onMakeMe: () -> Unit, onEdit: (Long) -> Unit, onScan: () -> Unit, onPoster: (Long) -> Unit, onTap: () -> Unit, onOpenSettings: () -> Unit, onOpenTidy: () -> Unit, onAdd: () -> Unit) {
    val store: SettingsStore = koinInject()
    var showWelcome by rememberSaveable { mutableStateOf(!store.current.welcomeSeen) }
    val settings by store.settings.collectAsState()
    if (!showWelcome && !BuildConfig.DEBUG) UpdatePrompt(settings.updates, BuildConfig.VERSION_NAME)

    val look = LocalGlass.current
    val backdrop = rememberGlassBackdrop()
    // A wide window shows the list and the person side by side.
    var picked by rememberSaveable { mutableStateOf<Long?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        Box(Modifier.fillMaxSize().then(if (look != null) Modifier.glassSource(backdrop, look) else Modifier)) {
            if (!wide) {
                ReadableScroll { ListScreen(onOpen = onOpen, onMakeMe = onMakeMe, onOpenSettings = onOpenSettings, onOpenTidy = onOpenTidy) }
            } else androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(0.42f).fillMaxSize()) {
                    ListScreen(onOpen = { picked = it }, onMakeMe = { picked = null; onMakeMe() }, onOpenSettings = onOpenSettings, onOpenTidy = onOpenTidy)
                }
                Box(Modifier.weight(0.58f).fillMaxSize()) {
                    androidx.compose.animation.Crossfade(picked, label = "pane") { id ->
                        if (id == null) com.yaz.contacts.ui.component.EmptyZone(
                            title = "Choose someone",
                            message = "Their page opens here.",
                            icon = AppIcons.Person,
                            modifier = Modifier.fillMaxSize()
                        ) else ContactScreen(id = id, onBack = { picked = null }, onEdit = onEdit, onDeleted = { picked = null }, onMoved = { picked = it }, onScan = onScan, onPoster = onPoster, onTap = onTap)
                    }
                }
            }
        }
        CompositionLocalProvider(LocalGlassBackdrop provides backdrop.takeIf { look != null }) {
            MovableAddButton(onClick = onAdd, above = 16.dp)
        }
        if (showWelcome) {
            Surface(
                Modifier.fillMaxSize().glassGround(LocalGlass.current, MaterialTheme.colorScheme.background),
                color = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onBackground
            ) {
                Readable {
                    WelcomeScreen(onStart = {
                        showWelcome = false
                        store.update { it.copy(welcomeSeen = true) }
                    })
                }
            }
        }
    }
}

/**
 * The way to a new contact: a round pane of glass washed with the accent.
 * A tap opens it; held, it lifts and follows the finger anywhere, and
 * stays where it is let go, kept for next time. Until moved it sits at
 * the thumb's side, [above] the bottom edge.
 */
@Composable
private fun MovableAddButton(onClick: () -> Unit, above: Dp) {
    val haptics = rememberHaptics()
    val store: SettingsStore = koinInject()
    val settings by store.settings.collectAsState()
    val look = LocalGlass.current
    val backdrop = LocalGlassBackdrop.current
    val density = LocalDensity.current
    val longPress = LocalViewConfiguration.current.longPressTimeoutMillis

    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(12.dp)) {
        val side = with(density) { ButtonSize.toPx() }
        val roomX = (constraints.maxWidth - side).coerceAtLeast(0f)
        val roomY = (constraints.maxHeight - side).coerceAtLeast(0f)
        val usual = with(density) { Offset(roomX - 8.dp.toPx(), roomY - above.toPx()) }
        val saved = if (settings.addX >= 0f) Offset(settings.addX * roomX, settings.addY * roomY) else null
        var dragging by remember { mutableStateOf<Offset?>(null) }
        val at = dragging ?: saved ?: usual
        val current by rememberUpdatedState(at)
        val lift by animateFloatAsState(if (dragging != null) 1.14f else 1f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "lift")
        val base = Modifier
            .offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }
            .size(ButtonSize)
            .graphicsLayer {
                scaleX = lift
                scaleY = lift
            }
            .clip(CircleShape)
        Box(
            contentAlignment = Alignment.Center,
            modifier = when {
                look != null && backdrop != null -> base.glassFloating(backdrop, CircleShape, look, tint = look.accentTint, lens = 1.4f)
                else -> base.background(MaterialTheme.colorScheme.primaryContainer)
            }
                .semantics {
                    role = Role.Button
                    contentDescription = "New contact"
                }
                .pointerInput(roomX, roomY) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val up = withTimeoutOrNull(longPress) { waitForUpOrCancellation() }
                        if (up != null) {
                            haptics.firm()
                            onClick()
                            return@awaitEachGesture
                        }
                        haptics.firm()
                        var where = current
                        dragging = where
                        drag(down.id) { change ->
                            val d = change.positionChange()
                            change.consume()
                            where = Offset((where.x + d.x).coerceIn(0f, roomX), (where.y + d.y).coerceIn(0f, roomY))
                            dragging = where
                        }
                        haptics.tick()
                        val placed = where
                        store.update {
                            it.copy(
                                addX = if (roomX > 0f) placed.x / roomX else 1f,
                                addY = if (roomY > 0f) placed.y / roomY else 1f
                            )
                        }
                        dragging = null
                    }
                }
        ) {
            Icon(AppIcons.PersonAdd, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

private val ButtonSize = 64.dp

/** Long enough to be read as motion, short enough not to be waited on. */
private const val NAV_MS = 260
