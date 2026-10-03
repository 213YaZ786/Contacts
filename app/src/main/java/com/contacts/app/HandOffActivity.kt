package com.contacts.app

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.contacts.app.core.contacts.Details
import com.contacts.app.core.contacts.Labelled
import com.contacts.app.core.handoff.Drafts
import com.contacts.app.core.handoff.HandOff
import com.contacts.app.core.handoff.HandOffs
import com.contacts.app.data.contacts.ContactStore
import com.contacts.app.data.settings.SettingsStore
import com.contacts.app.navigation.ContactsApp
import com.contacts.app.navigation.Routes
import com.contacts.app.ui.component.EmptyZone
import com.contacts.app.ui.component.LoadingMark
import com.contacts.app.ui.icon.AppIcons
import com.contacts.app.ui.theme.AppSurface
import org.koin.android.ext.android.inject

/**
 * What another app asks of the contacts app: a contact to show, to make,
 * to change or to pick, a card to read. It opens over that app and goes
 * back to it when done; nothing is saved or sent without the user.
 */
class HandOffActivity : ComponentActivity() {

    private val settings: SettingsStore by inject()
    private val store: ContactStore by inject()
    private val drafts: Drafts by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val request = HandOffs.of(intent)
        if (request == null) {
            finish()
            return
        }
        // Nothing of another app can float over Save, Delete or a pick.
        window.setHideOverlayWindows(true)
        hideWhenAsked(this, settings)
        setContent {
            AppSurface {
                val start by produceState<String?>(null) { value = startOf(request) }
                when (val route = start) {
                    null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingMark(size = 72.dp) }
                    GONE -> EmptyZone(
                        title = "This contact is gone",
                        message = "It was deleted, or is not on this phone.",
                        icon = AppIcons.Person,
                        actionLabel = "Close",
                        onAction = ::finish,
                        modifier = Modifier.fillMaxSize()
                    )
                    else -> ContactsApp(route, finish = ::done)
                }
            }
        }
    }

    /** Where the request starts, once its contact is found. */
    private suspend fun startOf(request: HandOff): String = when (request) {
        is HandOff.Show -> store.resolve(request.uri)?.let { Routes.contact(it) } ?: GONE
        is HandOff.Edit -> store.resolve(request.uri)?.let { Routes.edit(it, drafts.put(request.prefill)) } ?: GONE
        is HandOff.Insert -> Routes.edit(null, drafts.put(request.prefill))
        is HandOff.InsertOrEdit -> Routes.choose(drafts.put(request.prefill))
        is HandOff.Pick -> Routes.pick(request.kind.name)
        is HandOff.Import -> Routes.import(request.uri)
        is HandOff.ShowOrCreate -> store.findBy(request.scheme, request.value)?.let { Routes.contact(it) } ?: run {
            val name = request.name?.let { HandOffs.split(it) }
            val prefill = if (request.scheme == "tel") Details(phones = listOf(Labelled(kind = android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE, value = request.value)))
            else Details(emails = listOf(Labelled(kind = android.provider.ContactsContract.CommonDataKinds.Email.TYPE_HOME, value = request.value)))
            Routes.choose(drafts.put(if (name != null) prefill.copy(name = name) else prefill))
        }
    }

    /** Back to the app that asked: with the row picked or the contact saved, or nothing. */
    private fun done(result: Intent?) {
        if (result?.data != null) {
            val uri: Uri = result.data!!
            result.clipData = ClipData.newRawUri("contact", uri)
            setResult(Activity.RESULT_OK, result)
        } else setResult(Activity.RESULT_CANCELED)
        finish()
    }

    private companion object {
        const val GONE = "gone"
    }
}
