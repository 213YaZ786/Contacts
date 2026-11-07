/*
 * The welcome guide as tiles, after FlorisBoard's SetupScreen: every step is read from the system (what is held,
 * allowed) and shown done or not, the tiles are Modules' SetupTiles. Laid out as Language's guide.
 * not ported: Flow.awaitTransition (waits for an IME state to change; the tiles are read again on each resume instead)
 * not ported: SetupLinks (FlorisBoard's help and privacy links; the guide of Contacts has no page for them)
 * not ported: SetupScreen (kept under another name: WelcomeScreen and WelcomeContent below, the steps being the tiles)
 * not ported: Language's microphone, engine, voice input, voice keyboard and languages tiles (Language's own steps)
 *
 * Copyright (C) 2021-2025 The FlorisBoard Contributors, Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0), translated from
 * https://github.com/florisboard/florisboard/blob/main/app/src/main/kotlin/dev/patrickgold/florisboard/app/setup/SetupScreen.kt#SetupScreen
 */
package com.yaz.contacts.feature.main

import android.app.NotificationManager
import android.Manifest
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.yaz.contacts.core.update.StoreLink
import com.yaz.contacts.core.update.Updates
import com.yaz.contacts.ui.component.BoldButton
import com.yaz.contacts.ui.component.LoadingMark
import com.yaz.contacts.ui.component.SetupTile
import com.yaz.contacts.ui.component.SetupTileDialog
import com.yaz.contacts.ui.component.SetupTileGrid
import com.yaz.contacts.ui.component.leftText
import com.yaz.contacts.ui.component.requiredDone
import com.yaz.contacts.ui.component.requiredLeft
import com.yaz.contacts.ui.icon.AppIcons

/**
 * The few choices worth making, the best one already set, as tiles: each is read from the system again each time the
 * screen comes back (SetupScreen reads isFlorisBoardEnabled, isFlorisBoardSelected and hasNotificationPermission,
 * and sets the current step from them), and turns done by itself. Only a permission and what leaves the phone are
 * asked; everything else keeps its best default.
 * Ported from rememberSetupTiles of
 * https://github.com/213YaZ786/Language/blob/main/app/src/main/java/com/yaz/language/feature/welcome/WelcomeScreen.kt#rememberSetupTiles
 * Steps: the contacts permission (required), updates (only without Store: notifications, then installs).
 * [asked] tells that Android's question was put already (here or by the tile), so that a refusal Android no longer
 * asks again is told apart from a permission never asked; [onAsked] sets it. Not ported: the microphone, engine, voice
 * input, voice keyboard and languages tiles (Language's own steps, Contacts has none of them); the permission
 * launcher (Contacts' rememberSetup already holds it, with the opening of Android's app page after a refusal).
 */
@Composable
internal fun rememberSetupTiles(setup: Setup = rememberSetup(), asked: Boolean = false, onAsked: () -> Unit = {}): List<SetupTile> {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var checks by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        checks++
        onPauseOrDispose { }
    }
    val canInstall = remember(checks) { Updates.canInstall(context) }
    val notificationsAllowed = remember(checks) { context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() }
    // When Store installs the app's updates, the app asks nothing about them.
    val viaStore = remember(checks) { StoreLink.viaStore(context) }
    // Contacts: read and write held, as Setup's own step says (it is CONTACTS while one is missing).
    val contactsAllowed = setup.step != SetupStep.CONTACTS
    // Refused with Android's "don't ask again": asked before, not granted, and no rationale to show
    // (as Language's rememberMicrophone, after Accompanist's permissions state).
    val contactsRefused = !contactsAllowed && asked && activity != null &&
        !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_CONTACTS)

    return listOfNotNull(
        SetupTile(
            id = "contacts", name = "Contacts", title = "Contacts", icon = AppIcons.Contacts,
            state = when {
                contactsAllowed -> "Allowed"
                contactsRefused -> "Refused, open settings"
                else -> "Not allowed"
            },
            summary = "To show, add and change the people on this phone. They stay on the phone and in your accounts. " +
                if (contactsRefused) "It was refused, so Android's settings page for Contacts opens." else "Tap to allow it.",
            done = contactsAllowed, required = true, error = contactsRefused,
            onClick = {
                onAsked()
                setup.run(SetupStep.CONTACTS)
            }
        ),
        if (!viaStore) SetupTile(
            id = "updates", name = "Updates", title = "Updates", icon = AppIcons.Update,
            state = when {
                !notificationsAllowed -> "Notifications not allowed"
                canInstall -> "Allowed"
                else -> "Installs not allowed"
            },
            summary = "Contacts installs its new versions, checked on GitHub. It needs notifications to tell you of a download, " +
                "then Android's leave to install. Tap to allow it.",
            done = canInstall && notificationsAllowed, required = false,
            onClick = {
                if (!notificationsAllowed) setup.run(SetupStep.NOTIFICATIONS)
                else if (!canInstall) Updates.allowInstalls(context)
            }
        ) else null
    )
}

/**
 * The guide: the logo, the title, how many steps are left, the tiles, then Start (Done when reopened from Settings),
 * which waits for the required ones. Laid out as SetupScreen: header, steps, footer with the one button.
 * Ported from SetupScreen#SetupScreen of
 * https://github.com/florisboard/florisboard/blob/main/app/src/main/kotlin/dev/patrickgold/florisboard/app/setup/SetupScreen.kt#SetupScreen
 * and WelcomeScreen of
 * https://github.com/213YaZ786/Language/blob/main/app/src/main/java/com/yaz/language/feature/welcome/WelcomeScreen.kt#WelcomeScreen
 */
@Composable
fun WelcomeScreen(reopened: Boolean = false, onStart: () -> Unit) {
    val setup = rememberSetup()
    // Android's own question comes up by itself on arriving here, once;
    // the tile keeps its tap for whoever dismissed it.
    var asked by rememberSaveable { mutableStateOf(false) }
    val steps = rememberSetupTiles(setup, asked) { asked = true }
    // The tile whose long press is open (its id), "" when none.
    var explained by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (!asked && setup.step == SetupStep.CONTACTS) {
            asked = true
            setup.run(SetupStep.CONTACTS)
        }
    }

    WelcomeContent(steps, reopened, onStart) { explained = it.id }

    steps.firstOrNull { it.id == explained }?.let { tile ->
        SetupTileDialog(tile, onDismiss = { explained = "" })
    }
}

/**
 * What the guide draws from the tiles it is given: the layout of SetupScreen (header, steps, footer with the one
 * button), split from [WelcomeScreen] as Language's WelcomeContent is, so that a picture can be taken with fixed
 * tiles, without the system checks. Ported from
 * https://github.com/213YaZ786/Language/blob/main/app/src/main/java/com/yaz/language/feature/welcome/WelcomeScreen.kt#WelcomeContent
 */
@Composable
internal fun WelcomeContent(tiles: List<SetupTile>, reopened: Boolean, onStart: () -> Unit, onExplain: (SetupTile) -> Unit = {}) {
    val steps = tiles
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 8.dp)
        ) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(40.dp))
            // The app's own mark, large and moving, as the guide before the tiles drew it (the user, 2026-11-07):
            // https://github.com/213YaZ786/Contacts/blob/a633aa3/app/src/main/java/com/yaz/contacts/feature/main/WelcomeScreen.kt#WelcomeScreen
            LoadingMark(size = 160.dp)
            Spacer(Modifier.height(20.dp))
            // Every title in the accent (the user, 2026-10-31), as Modules' SettingsSectionTitle.
            Text("Welcome to Contacts", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(steps.leftText(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            SetupTileGrid(steps, onExplain)

            Spacer(Modifier.height(32.dp))
            // Without the contacts permission the app cannot work: Start waits for it (the required tile).
            val ready = steps.requiredDone()
            BoldButton(filled = true, enabled = ready, onClick = onStart) { Text(if (reopened) "Done" else "Start") }
            if (!ready) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Still needed: " + steps.requiredLeft().joinToString(", ") { it.name },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}
