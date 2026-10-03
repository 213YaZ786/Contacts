package com.yaz.contacts.widget

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.provider.ContactsContract
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.yaz.contacts.R
import com.yaz.contacts.core.contacts.LookCodec
import kotlin.math.max

/** The faces of the widget, read from Android's contacts when the widget asks. */
class FavoritesWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Faces(applicationContext)
}

private class Faces(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private data class Face(val id: Long, val lookup: String, val name: String, val thumb: String?, val colour: Int, val letters: String)

    private var faces: List<Face> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            faces = emptyList()
            return
        }
        val looks = HashMap<Long, Pair<Int, String>>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Data.DATA1),
                "${ContactsContract.Data.MIMETYPE} = ?", arrayOf(LookCodec.MIMETYPE), null
            )?.use { c -> while (c.moveToNext()) LookCodec.decode(c.getString(1)).let { looks[c.getLong(0)] = it.color to it.letters } }
        }
        faces = runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI),
                "${ContactsContract.Contacts.STARRED} = 1", null, "${ContactsContract.Contacts.SORT_KEY_PRIMARY} ASC"
            )?.use { c ->
                buildList {
                    while (c.moveToNext() && size < 24) {
                        val id = c.getLong(0)
                        val look = looks[id]
                        add(Face(id, c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getString(3), look?.first ?: 0, look?.second.orEmpty()))
                    }
                }
            }
        }.getOrNull().orEmpty()
    }

    override fun getCount() = faces.size

    override fun getViewAt(position: Int): RemoteViews {
        val face = faces.getOrNull(position) ?: return RemoteViews(context.packageName, R.layout.widget_face)
        return RemoteViews(context.packageName, R.layout.widget_face).apply {
            setTextViewText(R.id.name, face.name.substringBefore(' ').ifBlank { face.name })
            setImageViewBitmap(R.id.picture, picture(face))
            setContentDescription(R.id.face, face.name)
            // Only this contact's page, filled into the widget's own explicit intent.
            setOnClickFillInIntent(R.id.face, Intent().setData(ContactsContract.Contacts.getLookupUri(face.id, face.lookup)))
        }
    }

    /** Their small photo in a circle, else their monogram on their colour. */
    private fun picture(face: Face): Bitmap {
        val side = 156
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val photo = face.thumb?.let { uri ->
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                    // A provider's own small picture, read with a bounded size.
                    BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
                }
            }.getOrNull()?.takeIf { it.width in 1..1024 && it.height in 1..1024 }
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (photo != null) {
            val scale = max(side.toFloat() / photo.width, side.toFloat() / photo.height)
            val matrix = android.graphics.Matrix().apply {
                setScale(scale, scale)
                postTranslate((side - photo.width * scale) / 2f, (side - photo.height * scale) / 2f)
            }
            paint.shader = BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
            canvas.drawCircle(side / 2f, side / 2f, side / 2f, paint)
            return out
        }
        paint.color = if (face.colour != 0) face.colour else context.getColor(android.R.color.system_accent1_400)
        canvas.drawCircle(side / 2f, side / 2f, side / 2f, paint)
        val letters = face.letters.ifBlank { face.name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() } }.ifBlank { "?" }
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = if (letters.length > 1) side * 0.36f else side * 0.44f
        }
        canvas.drawText(letters, side / 2f, side / 2f - (ink.descent() + ink.ascent()) / 2f, ink)
        return out
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount() = 1
    override fun getItemId(position: Int) = faces.getOrNull(position)?.id ?: position.toLong()
    override fun hasStableIds() = true
    override fun onDestroy() {
        faces = emptyList()
    }
}
