package com.contacts.app.feature.main

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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.contacts.app.ui.component.BoldButton
import com.contacts.app.ui.component.ZoneSurface

/** What Contacts needs before it can show and keep people. */
internal enum class SetupStep(val title: String, val message: String, val action: String) {
    CONTACTS(
        "Allow contacts",
        "To show, add and change the people on this phone. They stay on the phone and in your accounts.",
        "Allow"
    )
}

/**
 * The step still missing, or null when Contacts can work, read again each
 * time the app comes back, since it can be changed in Android at any
 * time; and [run] to start it.
 */
internal class Setup(val step: SetupStep?, val run: (SetupStep) -> Unit)

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
    val step = remember(checks) { nextStep(context) }
    return Setup(step) { wanted ->
        when (wanted) {
            SetupStep.CONTACTS -> ask.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS))
        }
    }
}

/** The step still missing, on the main screen, when the first launch page was skipped or a permission taken back. */
@Composable
fun SetupZone(modifier: Modifier = Modifier) {
    val setup = rememberSetup()
    val step = setup.step ?: return

    ZoneSurface(
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 16.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp)
        ) {
            Text(step.title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(step.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Row(Modifier.padding(top = 4.dp)) {
                BoldButton(filled = true, onClick = { setup.run(step) }) { Text(step.action) }
            }
        }
    }
}

private fun nextStep(context: Context): SetupStep? {
    val read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    val write = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
    return if (read && write) null else SetupStep.CONTACTS
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
