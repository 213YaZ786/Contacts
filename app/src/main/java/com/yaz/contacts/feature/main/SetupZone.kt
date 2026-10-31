package com.yaz.contacts.feature.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.yaz.contacts.ui.component.BoldButton

/** What Contacts needs before it can show and keep people. */
internal enum class SetupStep(val title: String, val message: String, val action: String, val refuse: String) {
    CONTACTS(
        "Allow contacts",
        "To show, add and change the people on this phone. They stay on the phone and in your accounts.",
        "Allow",
        "Don't allow"
    ),
    NOTIFICATIONS(
        "Allow notifications",
        "To see a new version download, then restart it with a tap.",
        "Allow",
        "Don't allow"
    )
}

/**
 * The step still missing, or null when Contacts can work, read again each
 * time the app comes back, since it can be changed in Android at any
 * time; and [run] to start it. [cardStep] is [step] without the steps the
 * user refused; [refuse] sets one aside for good.
 */
internal class Setup(
    val step: SetupStep?,
    val run: (SetupStep) -> Unit,
    val cardStep: SetupStep? = step,
    val refuse: (SetupStep) -> Unit = {}
)

@Composable
internal fun rememberSetup(): Setup {
    val context = LocalContext.current
    var checks by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        checks++
        onPauseOrDispose { }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        // Refused before: Android will not ask again, its own page is the
        // only way left, and the user decides there.
        if (granted.values.any { !it }) openAppSettings(context)
        checks++
    }
    // Read here so that every card on screen follows a refusal made on another at once.
    val refusals = refusalCount.intValue
    val step = remember(checks) { nextStep(context, skipRefused = false) }
    val cardStep = remember(checks, refusals) { nextStep(context, skipRefused = true) }
    return Setup(step, cardStep = cardStep, refuse = { refusedStep ->
        refuse(context, refusedStep)
        checks++
    }, run = { wanted ->
        when (wanted) {
            SetupStep.CONTACTS -> ask.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS))
            SetupStep.NOTIFICATIONS -> ask.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    })
}

/**
 * The step still missing, on the main screen, when the first launch page was skipped or a permission taken back,
 * laid straight on the page (a zone means a tap) with a button to refuse it: Element X's opt-in card, "Not now"
 * beside the confirm button, the refusal stored so the card never comes back (element-hq/element-x-android,
 * AGPL-3.0, behaviour only:
 * https://github.com/element-hq/element-x-android/blob/develop/features/ftue/impl/src/main/kotlin/io/element/android/features/ftue/impl/notifications/NotificationsOptInView.kt
 * and NotificationsOptInPresenter.kt, NotNowClicked), stored as our SMS's SetupZone.skip stores its "Not now"
 * (https://github.com/213YaZ786/SMS/blob/main/app/src/main/java/com/yaz/sms/feature/main/SetupZone.kt).
 */
@Composable
fun SetupZone(modifier: Modifier = Modifier) {
    val setup = rememberSetup()
    val step = setup.cardStep ?: return

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 16.dp).padding(horizontal = 24.dp, vertical = 22.dp)
    ) {
        // Every title in the accent (the user, 2026-10-31), as Modules' SettingsSectionTitle.
        Text(step.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
        Text(step.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BoldButton(filled = false, onClick = { setup.refuse(step) }) { Text(step.refuse) }
            BoldButton(filled = true, onClick = { setup.run(step) }) { Text(step.action) }
        }
    }
}

/** Steps refused so far: read by every card on screen, so that all follow a refusal at once. */
private val refusalCount = mutableIntStateOf(0)

/** The step set aside for good, one key per step (SMS's SetupZone.skip, SharedPreferences.Editor.putBoolean). */
private fun refuse(context: Context, step: SetupStep) {
    context.getSharedPreferences(SETUP, Context.MODE_PRIVATE).edit().putBoolean(REFUSED + step.name, true).apply()
    refusalCount.intValue++
}

private fun refused(context: Context, step: SetupStep): Boolean =
    context.getSharedPreferences(SETUP, Context.MODE_PRIVATE).getBoolean(REFUSED + step.name, false)

private const val SETUP = "setup"
private const val REFUSED = "refused_"

private fun nextStep(context: Context, skipRefused: Boolean): SetupStep? {
    fun open(step: SetupStep) = !(skipRefused && refused(context, step))
    val read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    val write = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
    if ((!read || !write) && open(SetupStep.CONTACTS)) return SetupStep.CONTACTS
    // Without it the updates download and wait unseen.
    if (!context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled() && open(SetupStep.NOTIFICATIONS)) return SetupStep.NOTIFICATIONS
    return null
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
