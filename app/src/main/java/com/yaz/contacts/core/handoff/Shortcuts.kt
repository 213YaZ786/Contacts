package com.yaz.contacts.core.handoff

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.provider.ContactsContract
import com.yaz.contacts.HandOffActivity
import com.yaz.contacts.MainActivity
import com.yaz.contacts.core.contacts.Details
import com.yaz.contacts.core.security.SafeImages
import kotlin.math.max

/**
 * A person on the home screen, and the app's own shortcuts on its icon
 * (a long press): a new contact, scanning someone's QR code.
 */
object Shortcuts {

    const val ACTION_NEW = "com.yaz.contacts.action.NEW"
    const val ACTION_SCAN = "com.yaz.contacts.action.SCAN"

    /** Asks the launcher to place [d] on the home screen; false when it cannot. */
    suspend fun pin(context: Context, d: Details, accent: Int): Boolean {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
        if (!manager.isRequestPinShortcutSupported) return false
        val photo = runCatching {
            ContactsContract.Contacts.openContactPhotoInputStream(context.contentResolver, ContactsContract.Contacts.getLookupUri(d.id, d.lookup), true)?.use { it.readBytes() }
        }.getOrNull()?.let { SafeImages.decode(context, it, 432) }
        val icon = Icon.createWithAdaptiveBitmap(face(photo, d, if (d.look.color != 0) d.look.color else accent))
        val open = Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.getLookupUri(d.id, d.lookup))
            .setComponent(ComponentName(context, HandOffActivity::class.java))
        val info = ShortcutInfo.Builder(context, "contact-" + d.lookup.hashCode())
            .setShortLabel(d.name.given.ifBlank { d.display }.take(20).ifBlank { "Contact" })
            .setLongLabel(d.display.take(40).ifBlank { "Contact" })
            .setIcon(icon)
            .setIntent(open)
            .build()
        return runCatching { manager.requestPinShortcut(info, null) }.getOrDefault(false)
    }

    /** The app's icon shortcuts, set when the app opens (once is enough, they stay). */
    fun publish(context: Context) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        if (manager.dynamicShortcuts.size >= 2) return
        fun shortcut(id: String, label: String, action: String, glyph: Int) = ShortcutInfo.Builder(context, id)
            .setShortLabel(label)
            .setIcon(Icon.createWithResource(context, glyph))
            .setIntent(Intent(action).setComponent(ComponentName(context, MainActivity::class.java)))
            .build()
        runCatching {
            manager.dynamicShortcuts = listOf(
                shortcut("new", "New contact", ACTION_NEW, com.yaz.contacts.R.drawable.ic_shortcut_add),
                shortcut("scan", "Scan a QR code", ACTION_SCAN, com.yaz.contacts.R.drawable.ic_shortcut_scan)
            )
        }
    }

    /** The person's face for the home screen: their photo, else their monogram on their colour. */
    private fun face(photo: Bitmap?, d: Details, colour: Int): Bitmap {
        val side = 432
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        if (photo != null) {
            val scale = max(side.toFloat() / photo.width, side.toFloat() / photo.height)
            val w = photo.width * scale
            val h = photo.height * scale
            canvas.drawBitmap(photo, Rect(0, 0, photo.width, photo.height), RectF((side - w) / 2, (side - h) / 2, (side + w) / 2, (side + h) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
            return out
        }
        canvas.drawColor(colour)
        val text = d.look.emoji.ifBlank {
            d.look.letters.ifBlank { d.display.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() } }
        }.ifBlank { "?" }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = if (text.length > 1) side * 0.26f else side * 0.32f
        }
        val y = side / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, side / 2f, y, paint)
        return out
    }
}
