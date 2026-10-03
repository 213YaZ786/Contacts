package com.yaz.contacts.feature.settings

import com.yaz.contacts.core.contacts.SortOrder
import com.yaz.contacts.data.contacts.ContactStore
import com.yaz.contacts.feature.contact.accountLabel
import com.yaz.contacts.ui.component.FloatingAction
import com.yaz.contacts.ui.component.FloatingFrame
import com.yaz.contacts.ui.component.FloatingTop
import android.content.Context
import android.content.Intent
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import org.koin.compose.koinInject
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yaz.contacts.BuildConfig
import com.yaz.contacts.core.update.UpdateMode
import com.yaz.contacts.core.update.Updates
import com.yaz.contacts.data.settings.ThemeMode
import com.yaz.contacts.navigation.LocalReadableInset
import com.yaz.contacts.ui.component.ZoneAlertDialog
import com.yaz.contacts.ui.component.ZoneSurface
import com.yaz.contacts.ui.component.rememberHaptics
import com.yaz.contacts.ui.icon.AppIcons
import com.yaz.contacts.ui.theme.TEXT_SCALES
import com.yaz.contacts.ui.theme.textScaleLabel
import org.koin.androidx.compose.koinViewModel

private enum class OpenDialog { NONE, THEME, TEXT_SIZE, UPDATES, SORT, ACCOUNT, TRASH, SHOWN }

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = koinViewModel()) {
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var dialog by rememberSaveable { mutableStateOf(OpenDialog.NONE) }

    // Scrolls at full width, rows pushed in by the readable inset, so the
    // margins of a tablet scroll like the rest. See ReadableScroll.
    FloatingFrame(
        bottom = 0.dp,
        top = { FloatingTop("Settings", leading = { FloatingAction(AppIcons.ArrowBack, "Back", onBack) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LocalReadableInset.current)
        ) {
            Spacer(Modifier.height(padding.calculateTopPadding()))

            Section("Contacts") {
                SettingRow(
                    title = "Sort by",
                    summary = if (settings.sortOrder == SortOrder.FIRST_NAME) "First name" else "Last name",
                    onClick = { dialog = OpenDialog.SORT }
                )
                SwitchRow(
                    title = "Last name first",
                    summary = "Names shown as Martin, Joëlle.",
                    checked = settings.lastNameFirst,
                    onChange = viewModel::setLastNameFirst
                )
                val store: ContactStore = koinInject()
                val accounts = remember { store.accounts() }
                SettingRow(
                    title = "New contacts saved in",
                    summary = (accounts.firstOrNull { it.key == settings.defaultAccount } ?: accounts.firstOrNull { it.type != null } ?: accounts.firstOrNull())
                        ?.let(::accountLabel) ?: "This phone",
                    onClick = { dialog = OpenDialog.ACCOUNT }
                )
                SettingRow(
                    title = "Contacts to show",
                    summary = if (settings.shownAccounts.isEmpty()) "All accounts" else accounts.filter { it.key in settings.shownAccounts }.joinToString(", ") { accountLabel(it) },
                    onClick = { dialog = OpenDialog.SHOWN }
                )
                SettingRow(
                    title = "Trash",
                    summary = "Deleted contacts kept ${settings.trashDays} days",
                    onClick = { dialog = OpenDialog.TRASH }
                )
                SettingRow(
                    title = "Accounts",
                    summary = "Add an account to sync contacts with",
                    onClick = { open(context, Intent(android.provider.Settings.ACTION_ADD_ACCOUNT).putExtra(android.provider.Settings.EXTRA_AUTHORITIES, arrayOf(android.provider.ContactsContract.AUTHORITY))) }
                )
                SettingRow(
                    title = "Blocked numbers",
                    summary = "The list Android keeps for every app",
                    onClick = { open(context, context.getSystemService(TelecomManager::class.java).createManageBlockedNumbersIntent()) }
                )
            }

            Section("Appearance") {
                SettingRow(
                    title = "Theme",
                    summary = themeLabel(settings.themeMode),
                    onClick = { dialog = OpenDialog.THEME }
                )
                SwitchRow(
                    title = "Pure black",
                    summary = "Black background in dark mode.",
                    checked = settings.pureBlack,
                    onChange = viewModel::setPureBlack
                )
                SwitchRow(
                    title = "Hide in recent apps",
                    summary = "The app's preview stays blank in recent apps.",
                    checked = settings.hideInRecents,
                    onChange = viewModel::setHideInRecents
                )
                SwitchRow(
                    title = "Glass effects",
                    summary = "Buttons and panes in liquid glass.",
                    checked = settings.glass,
                    onChange = viewModel::setGlass
                )
                SettingRow(
                    title = "Text size",
                    summary = textScaleLabel(settings.textScale) + ", on top of Android's font size",
                    onClick = { dialog = OpenDialog.TEXT_SIZE }
                )
            }

            Section("About") {
                SettingRow(
                    title = "Updates",
                    summary = updatesLabel(settings.updates),
                    onClick = { dialog = OpenDialog.UPDATES }
                )
                SettingRow(
                    title = "Contacts ${BuildConfig.VERSION_NAME}",
                    summary = "Your contacts with no account, no tracking and no ads.",
                    onClick = null
                )
                SettingRow(
                    title = "Phone numbers",
                    summary = "libphonenumber by Google, Apache License 2.0",
                    onClick = { uriHandler.openUri("https://github.com/google/libphonenumber") }
                )
                SettingRow(
                    title = "QR codes",
                    summary = "ZXing, Apache License 2.0",
                    onClick = { uriHandler.openUri("https://github.com/zxing/zxing") }
                )
                SettingRow(
                    title = "Source code",
                    summary = "github.com/213YaZ786/Contacts",
                    onClick = { uriHandler.openUri("https://github.com/213YaZ786/Contacts") }
                )
            }

            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }

    when (dialog) {
        OpenDialog.THEME -> ChoiceDialog(
            title = "Theme",
            options = ThemeMode.entries.map { it to themeLabel(it) },
            selected = settings.themeMode,
            onSelect = viewModel::setTheme,
            onDismiss = { dialog = OpenDialog.NONE }
        )
        OpenDialog.TEXT_SIZE -> ChoiceDialog(
            title = "Text size",
            options = TEXT_SCALES.map { it to textScaleLabel(it) },
            selected = settings.textScale,
            onSelect = viewModel::setTextScale,
            onDismiss = { dialog = OpenDialog.NONE }
        )
        OpenDialog.UPDATES -> ChoiceDialog(
            title = "Updates",
            options = UpdateMode.entries.map { it to updatesLabel(it) },
            selected = settings.updates,
            onSelect = { mode ->
                viewModel.setUpdates(mode)
                // Installing needs Android's leave, asked when chosen.
                if (mode == UpdateMode.INSTALL && !Updates.canInstall(context)) Updates.allowInstalls(context)
            },
            onDismiss = { dialog = OpenDialog.NONE }
        )
        OpenDialog.SORT -> ChoiceDialog(
            title = "Sort by",
            options = listOf(SortOrder.FIRST_NAME to "First name", SortOrder.LAST_NAME to "Last name"),
            selected = settings.sortOrder,
            onSelect = viewModel::setSortOrder,
            onDismiss = { dialog = OpenDialog.NONE }
        )
        OpenDialog.ACCOUNT -> {
            val store: ContactStore = koinInject()
            val accounts = remember { store.accounts() }
            ChoiceDialog(
                title = "New contacts saved in",
                options = accounts.map { it.key to accountLabel(it) },
                selected = settings.defaultAccount ?: (accounts.firstOrNull { it.type != null } ?: accounts.firstOrNull())?.key,
                onSelect = { key -> key?.let(viewModel::setDefaultAccount) },
                onDismiss = { dialog = OpenDialog.NONE }
            )
        }
        OpenDialog.SHOWN -> {
            val store: ContactStore = koinInject()
            val accounts = remember { store.accounts() }
            ZoneAlertDialog(
                onDismissRequest = { dialog = OpenDialog.NONE },
                title = { Text("Contacts to show") },
                text = {
                    Column {
                        accounts.forEach { a ->
                            val on = settings.shownAccounts.isEmpty() || a.key in settings.shownAccounts
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                                    val current = if (settings.shownAccounts.isEmpty()) accounts.map { it.key }.toSet() else settings.shownAccounts
                                    val next = if (on) current - a.key else current + a.key
                                    // Every account ticked, or none, is all.
                                    viewModel.setShownAccounts(if (next.size == accounts.size || next.isEmpty()) emptySet() else next)
                                }.padding(vertical = 8.dp, horizontal = 4.dp)
                            ) {
                                androidx.compose.material3.Checkbox(checked = on, onCheckedChange = null)
                                Text(accountLabel(a), modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { dialog = OpenDialog.NONE }) { Text("Done") } }
            )
        }
        OpenDialog.TRASH -> ChoiceDialog(
            title = "Keep deleted contacts",
            options = listOf(7, 30, 90).map { it to "$it days" },
            selected = settings.trashDays,
            onSelect = viewModel::setTrashDays,
            onDismiss = { dialog = OpenDialog.NONE }
        )
        OpenDialog.NONE -> Unit
    }
}

/** A titled group of rows on one rounded zone. */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    // Centred and at title size: a heading names what the zone below holds.
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 10.dp)
    )
    ZoneSurface(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingRow(
    title: String,
    summary: String?,
    onClick: (() -> Unit)?,
    quiet: Boolean = false,
    trailing: (@Composable () -> Unit)? = null
) {
    val haptics = rememberHaptics()
    ListItem(
        headlineContent = { Text(title, color = MaterialTheme.colorScheme.onSurface) },
        supportingContent = summary?.let { { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        // Every row answers with a tick; a switch row answers with the
        // switch's own feel instead, so it is quiet here.
        modifier = if (onClick != null) {
            Modifier.clickable {
                if (!quiet) haptics.tick()
                onClick()
            }
        } else {
            Modifier
        }
    )
}

@Composable
private fun SwitchRow(title: String, summary: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val haptics = rememberHaptics()
    val change = { on: Boolean ->
        haptics.toggle(on)
        onChange(on)
    }
    SettingRow(
        title = title,
        summary = summary,
        quiet = true,
        onClick = if (enabled) ({ change(!checked) }) else null,
        trailing = { Switch(checked = checked, onCheckedChange = change, enabled = enabled) }
    )
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    ZoneAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onSelect(value)
                                onDismiss()
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}


/** The first of these Android screens the phone has: newer ones first, the general one last. */
private fun openFirst(context: Context, vararg actions: String) {
    for (action in actions) {
        if (runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}

/** An Android settings screen; some phones leave one out, then nothing happens. */
private fun open(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "Same as the system"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

private fun updatesLabel(mode: UpdateMode): String = when (mode) {
    UpdateMode.OFF -> "Off"
    UpdateMode.NOTIFY -> "Notify me"
    UpdateMode.INSTALL -> "Install automatically"
}

