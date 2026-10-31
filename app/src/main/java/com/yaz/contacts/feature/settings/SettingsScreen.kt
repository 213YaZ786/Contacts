package com.yaz.contacts.feature.settings

import android.content.Context
import android.content.Intent
import android.telecom.TelecomManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.ShapeDefaults
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.yaz.contacts.BuildConfig
import com.yaz.contacts.core.contacts.SortOrder
import com.yaz.contacts.core.update.UpdateMode
import com.yaz.contacts.core.update.Updates
import com.yaz.contacts.data.contacts.ContactStore
import com.yaz.contacts.data.settings.ThemeMode
import com.yaz.contacts.feature.contact.accountLabel
import com.yaz.contacts.navigation.LocalReadableInset
import com.yaz.contacts.navigation.WidthClass
import com.yaz.contacts.ui.component.BoldButton
import com.yaz.contacts.ui.component.ChoiceDialog
import com.yaz.contacts.ui.component.FloatingAction
import com.yaz.contacts.ui.component.FloatingFrame
import com.yaz.contacts.ui.component.FloatingTop
import com.yaz.contacts.ui.component.GlassCheck
import com.yaz.contacts.ui.component.LargeTitle
import com.yaz.contacts.ui.component.SettingsActionTile
import com.yaz.contacts.ui.component.SettingsFoldedSections
import com.yaz.contacts.ui.component.SettingsHeaderSpacer
import com.yaz.contacts.ui.component.SettingsListDetail
import com.yaz.contacts.ui.component.SettingsSection
import com.yaz.contacts.ui.component.SettingsSectionTitle
import com.yaz.contacts.ui.component.SettingsSwitchTile
import com.yaz.contacts.ui.component.SettingsTile
import com.yaz.contacts.ui.component.SettingsTileDialogs
import com.yaz.contacts.ui.component.SettingsTileGrid
import com.yaz.contacts.ui.component.ZoneAlertDialog
import com.yaz.contacts.ui.icon.AppIcons
import com.yaz.contacts.ui.theme.TEXT_SCALES
import com.yaz.contacts.ui.theme.textScaleLabel
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

private enum class OpenDialog { NONE, THEME, TEXT_SIZE, UPDATES, SORT, ACCOUNT, TRASH, SHOWN, LOCK }

/** The groups of settings: folded one under another on a phone, a list and the open one's page on a wide window. */
private val CONTACTS = SettingsSection("contacts", "Contacts", AppIcons.Contacts)
private val PRIVACY = SettingsSection("privacy", "Privacy", AppIcons.Lock)
private val APPEARANCE = SettingsSection("appearance", "Appearance", AppIcons.Palette)
private val ABOUT = SettingsSection("about", "About", AppIcons.Info)
private val SECTIONS = listOf(CONTACTS, PRIVACY, APPEARANCE, ABOUT)

/**
 * Settings as tiles, rebuilt on Modules' SettingsTiles the way SMS's SettingsScreen is (SettingsScreen.kt in
 * /home/yghouar/Claude Code/APK/YAZ/SMS, feature/settings; Quick Settings' tile, AOSP SystemUI QSTileViewImpl):
 * a large title that folds as the page scrolls, every setting a tile with its value, a tap toggles or opens,
 * a long press explains, the sections side by side on a wide window and folded on a phone.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = koinViewModel()) {
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var dialog by rememberSaveable { mutableStateOf(OpenDialog.NONE) }
    // The tile whose long press is open (its id), "" when none.
    var tileId by rememberSaveable { mutableStateOf("") }
    var sectionId by rememberSaveable { mutableStateOf(CONTACTS.id) }
    val section = SECTIONS.first { it.id == sectionId }
    // On a phone every section starts folded; the one open stays open across a turn of the screen.
    var openId by rememberSaveable { mutableStateOf("") }
    val openSection = SECTIONS.firstOrNull { it.id == openId }
    // The page's scroll, held here so a drag on the header drives it (FloatingFrame's `scroll`): the folded
    // list on a phone, the chosen section's page on a wide window (it starts at the top again for each section).
    val compactScroll = rememberScrollState()
    val detailScroll = rememberSaveable(sectionId, saver = androidx.compose.foundation.ScrollState.Saver) {
        androidx.compose.foundation.ScrollState(0)
    }

    val store: ContactStore = koinInject()
    val accounts = remember { store.accounts() }
    val everyone by store.contacts.collectAsState()
    val canLock = remember { com.yaz.contacts.core.security.AppLock.possible(context) }
    // The people the list shows: the same two filters as the list (ListScreen).
    val count = everyone?.let { all ->
        all.filter { c -> settings.shownAccounts.isEmpty() || c.accounts.any { it in settings.shownAccounts } }
            .count { c -> !settings.onlyWithNumbers || c.phones.isNotEmpty() }
    }
    val savedIn = (accounts.firstOrNull { it.key == settings.defaultAccount } ?: accounts.firstOrNull { it.type != null } ?: accounts.firstOrNull())
        ?.let(::accountLabel) ?: "This phone"
    val shown = if (settings.shownAccounts.isEmpty()) "All accounts" else accounts.filter { it.key in settings.shownAccounts }.joinToString(", ") { accountLabel(it) }
    val sortLabel = if (settings.sortOrder == SortOrder.FIRST_NAME) "First name" else "Last name"
    val lockAfter = when (settings.lockAfterSeconds) {
        0 -> "As soon as you leave"
        60 -> "1 minute after you leave"
        else -> "${settings.lockAfterSeconds / 60} minutes after you leave"
    }

    val tiles: Map<SettingsSection, List<SettingsSwitchTile>> = mapOf(
        CONTACTS to listOf(
            SettingsSwitchTile(
                id = "lastFirst", name = "Last name first", title = "Last name first", icon = AppIcons.Person,
                summary = "Names shown as Martin, Joëlle.",
                checked = settings.lastNameFirst, onChange = viewModel::setLastNameFirst
            ),
            SettingsSwitchTile(
                id = "onlyNumbers", name = "With a number", title = "Only contacts with a number", icon = AppIcons.Call,
                summary = "People with only an email or an address stay out of the list.",
                checked = settings.onlyWithNumbers, onChange = viewModel::setOnlyWithNumbers
            )
        ),
        PRIVACY to listOf(
            SettingsSwitchTile(
                id = "lock", name = "Lock Contacts", title = "Lock Contacts", icon = AppIcons.Lock,
                summary = if (canLock) "Opens with your fingerprint, face or the phone's PIN, from the app, the widget and other apps."
                else "Set a screen lock on the phone first.",
                checked = settings.lock && canLock, enabled = canLock, onChange = viewModel::setLock
            ),
            SettingsSwitchTile(
                id = "recents", name = "Hide in recents", title = "Hide in recent apps", icon = AppIcons.Hidden,
                summary = "The app's preview stays blank in recent apps.",
                checked = settings.hideInRecents, onChange = viewModel::setHideInRecents
            )
        ),
        APPEARANCE to listOf(
            SettingsSwitchTile(
                id = "black", name = "Pure black", title = "Pure black", icon = AppIcons.Night,
                summary = "Black background in dark mode.",
                checked = settings.pureBlack, onChange = viewModel::setPureBlack
            ),
            SettingsSwitchTile(
                id = "glass", name = "Glass effects", title = "Glass effects", icon = AppIcons.AutoAwesome,
                summary = "Buttons and panes in liquid glass.",
                checked = settings.glass, onChange = viewModel::setGlass
            )
        )
    )

    // What opens a choice, a page or a link: tiles of the same grid, the value as the second line.
    val actions: Map<SettingsSection, List<SettingsActionTile>> = mapOf(
        CONTACTS to listOf(
            SettingsActionTile(
                id = "sort", name = "Sort by", title = "Sort by", icon = AppIcons.Sort,
                value = sortLabel, summary = sortLabel,
                onClick = { dialog = OpenDialog.SORT }
            ),
            SettingsActionTile(
                id = "savedIn", name = "Saved in", title = "New contacts saved in", icon = AppIcons.Account,
                value = savedIn, summary = "New contacts are saved in $savedIn",
                onClick = { dialog = OpenDialog.ACCOUNT }
            ),
            SettingsActionTile(
                id = "shown", name = "Contacts to show", title = "Contacts to show", icon = AppIcons.Filter,
                value = shown, summary = shown,
                onClick = { dialog = OpenDialog.SHOWN }
            ),
            SettingsActionTile(
                id = "trash", name = "Trash", title = "Trash", icon = AppIcons.DeleteOutline,
                value = "${settings.trashDays} days", summary = "Deleted contacts kept ${settings.trashDays} days",
                onClick = { dialog = OpenDialog.TRASH }
            ),
            SettingsActionTile(
                id = "accounts", name = "Accounts", title = "Accounts", icon = AppIcons.PersonAdd,
                value = "Android's settings", summary = "Add an account to sync contacts with",
                onClick = { open(context, Intent(android.provider.Settings.ACTION_ADD_ACCOUNT).putExtra(android.provider.Settings.EXTRA_AUTHORITIES, arrayOf(android.provider.ContactsContract.AUTHORITY))) }
            ),
            SettingsActionTile(
                id = "blocked", name = "Blocked numbers", title = "Blocked numbers", icon = AppIcons.Block,
                value = "Android's list", summary = "The list Android keeps for every app",
                onClick = { open(context, context.getSystemService(TelecomManager::class.java).createManageBlockedNumbersIntent()) }
            )
        ),
        PRIVACY to listOfNotNull(
            if (settings.lock) SettingsActionTile(
                id = "lockAfter", name = "Lock again", title = "Lock again", icon = AppIcons.History,
                value = lockAfter, summary = lockAfter,
                onClick = { dialog = OpenDialog.LOCK }
            ) else null
        ),
        APPEARANCE to listOf(
            SettingsActionTile(
                id = "theme", name = "Theme", title = "Theme", icon = AppIcons.Brightness,
                value = themeLabel(settings.themeMode), summary = themeLabel(settings.themeMode),
                onClick = { dialog = OpenDialog.THEME }
            ),
            SettingsActionTile(
                id = "textSize", name = "Text size", title = "Text size", icon = AppIcons.FormatSize,
                value = textScaleLabel(settings.textScale),
                summary = textScaleLabel(settings.textScale) + ", on top of Android's font size",
                onClick = { dialog = OpenDialog.TEXT_SIZE }
            )
        ),
        ABOUT to listOf(
            // Dialer's Guide tile (https://github.com/213YaZ786/Dialer/blob/main/app/src/main/java/com/yaz/dialer/feature/settings/SettingsScreen.kt).
            SettingsActionTile(
                id = "guide", name = "Guide", title = "Guide", icon = AppIcons.Play,
                value = "Show again", summary = "See the welcome pages again",
                onClick = {
                    viewModel.replayGuide()
                    onBack()
                }
            ),
            SettingsActionTile(
                id = "updates", name = "Updates", title = "Updates", icon = AppIcons.Update,
                value = updatesLabel(settings.updates), summary = updatesLabel(settings.updates),
                onClick = { dialog = OpenDialog.UPDATES }
            ),
            SettingsActionTile(
                id = "version", name = "Contacts", title = "Contacts ${BuildConfig.VERSION_NAME}", icon = AppIcons.Info,
                value = BuildConfig.VERSION_NAME, summary = "Your contacts with no account, no tracking and no ads."
            ),
            SettingsActionTile(
                id = "numbers", name = "Phone numbers", title = "Phone numbers", icon = AppIcons.ContactPhone,
                value = "libphonenumber", summary = "libphonenumber by Google, Apache License 2.0",
                onClick = { uriHandler.openUri("https://github.com/google/libphonenumber") }
            ),
            SettingsActionTile(
                id = "qr", name = "QR codes", title = "QR codes", icon = AppIcons.QrCode,
                value = "ZXing", summary = "ZXing, Apache License 2.0",
                onClick = { uriHandler.openUri("https://github.com/zxing/zxing") }
            ),
            SettingsActionTile(
                id = "source", name = "Source code", title = "Source code", icon = AppIcons.Code,
                value = "github.com/213YaZ786/Contacts", summary = "github.com/213YaZ786/Contacts",
                onClick = { uriHandler.openUri("https://github.com/213YaZ786/Contacts") }
            )
        )
    )

    // One section's page: its name, then its tiles: the switches first, then what opens something.
    val page: @Composable ColumnScope.(SettingsSection) -> Unit = { which ->
        SettingsSectionTitle(which.title)
        val list: List<SettingsTile> = tiles[which].orEmpty() + actions[which].orEmpty()
        if (list.isNotEmpty()) SettingsTileGrid(list) { tileId = it.id }
    }

    // The window decides: a list and a page side by side from Medium up, as a phone held upright
    // keeps folded sections. The panes take the whole width, so the readable inset is dropped.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = WidthClass.of(maxWidth) != WidthClass.COMPACT
        CompositionLocalProvider(LocalReadableInset provides if (wide) 0.dp else LocalReadableInset.current) {
            FloatingFrame(
                bottom = 0.dp,
                scroll = if (wide) detailScroll else compactScroll,
                top = { FloatingTop("Settings", leading = { FloatingAction(AppIcons.ArrowBack, "Back", onBack) }) },
                largeTitle = LargeTitle(
                    "Settings",
                    status = count?.let { if (it == 1) "1 contact" else "$it contacts" }
                )
            ) { padding ->
                if (wide) {
                    SettingsListDetail(
                        padding = padding,
                        sections = SECTIONS,
                        section = section,
                        onSection = { sectionId = it.id },
                        detailScroll = detailScroll,
                        detail = { page(section) }
                    )
                } else {
                    // Scrolls at full width, rows pushed in by the readable inset, so the
                    // margins of a tablet scroll like the rest. See ReadableScroll.
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(compactScroll)
                            .padding(horizontal = LocalReadableInset.current)
                    ) {
                        SettingsHeaderSpacer(padding)
                        Spacer(Modifier.height(24.dp))
                        SettingsFoldedSections(SECTIONS, open = openSection, onOpen = { openId = it?.id.orEmpty() }) { which ->
                            val list: List<SettingsTile> = tiles[which].orEmpty() + actions[which].orEmpty()
                            if (list.isNotEmpty()) SettingsTileGrid(list) { tileId = it.id }
                        }
                        Spacer(Modifier.height(24.dp))
                        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                    }
                }
            }
        }
    }

    (tiles.values.flatten() + actions.values.flatten()).firstOrNull { it.id == tileId }?.let { tile ->
        SettingsTileDialogs(tile, onDismiss = { tileId = "" })
    }

    when (dialog) {
        OpenDialog.THEME -> ChoiceDialog(
            title = "Theme",
            options = ThemeMode.entries.map { it to themeLabel(it) },
            selected = settings.themeMode,
            onPick = viewModel::setTheme,
            onDismiss = { dialog = OpenDialog.NONE }
        )
        OpenDialog.TEXT_SIZE -> ChoiceDialog(
            title = "Text size",
            options = TEXT_SCALES.map { it to textScaleLabel(it) },
            selected = settings.textScale,
            onPick = viewModel::setTextScale,
            onDismiss = { dialog = OpenDialog.NONE }
        )
        OpenDialog.UPDATES -> ChoiceDialog(
            title = "Updates",
            options = UpdateMode.entries.map { it to updatesLabel(it) },
            selected = settings.updates,
            onPick = { mode ->
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
            onPick = viewModel::setSortOrder,
            onDismiss = { dialog = OpenDialog.NONE }
        )
        OpenDialog.ACCOUNT -> {
            val dialogStore: ContactStore = koinInject()
            val dialogAccounts = remember { dialogStore.accounts() }
            ChoiceDialog(
                title = "New contacts saved in",
                options = dialogAccounts.map { it.key to accountLabel(it) },
                selected = settings.defaultAccount ?: (dialogAccounts.firstOrNull { it.type != null } ?: dialogAccounts.firstOrNull())?.key,
                onPick = viewModel::setDefaultAccount,
                onDismiss = { dialog = OpenDialog.NONE }
            )
        }
        OpenDialog.LOCK -> ChoiceDialog(
            title = "Lock again",
            options = listOf(0 to "As soon as you leave", 60 to "1 minute after you leave", 300 to "5 minutes after you leave"),
            selected = settings.lockAfterSeconds,
            onPick = viewModel::setLockAfter,
            onDismiss = { dialog = OpenDialog.NONE }
        )
        OpenDialog.SHOWN -> {
            val dialogStore: ContactStore = koinInject()
            val dialogAccounts = remember { dialogStore.accounts() }
            ZoneAlertDialog(
                onDismissRequest = { dialog = OpenDialog.NONE },
                title = { Text("Contacts to show") },
                text = {
                    Column {
                        dialogAccounts.forEach { a ->
                            val on = settings.shownAccounts.isEmpty() || a.key in settings.shownAccounts
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                // A row inside a dialog: Material 3's large corner, 16 dp (ShapeDefaults,
                                // https://developer.android.com/reference/kotlin/androidx/compose/material3/ShapeDefaults).
                                modifier = Modifier.fillMaxWidth().clip(ShapeDefaults.Large).clickable {
                                    val current = if (settings.shownAccounts.isEmpty()) dialogAccounts.map { it.key }.toSet() else settings.shownAccounts
                                    val next = if (on) current - a.key else current + a.key
                                    // Every account ticked, or none, is all.
                                    viewModel.setShownAccounts(if (next.size == dialogAccounts.size || next.isEmpty()) emptySet() else next)
                                }.padding(vertical = 8.dp, horizontal = 4.dp)
                            ) {
                                GlassCheck(checked = on, onCheckedChange = null)
                                Text(accountLabel(a), modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                },
                confirmButton = { BoldButton(filled = true, onClick = { dialog = OpenDialog.NONE }) { Text("Done") } }
            )
        }
        OpenDialog.TRASH -> ChoiceDialog(
            title = "Keep deleted contacts",
            options = listOf(7, 30, 90).map { it to "$it days" },
            selected = settings.trashDays,
            onPick = viewModel::setTrashDays,
            onDismiss = { dialog = OpenDialog.NONE }
        )
        OpenDialog.NONE -> Unit
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
