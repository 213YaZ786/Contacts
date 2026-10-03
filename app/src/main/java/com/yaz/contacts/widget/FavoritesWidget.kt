package com.yaz.contacts.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.yaz.contacts.HandOffActivity
import com.yaz.contacts.R

/**
 * The favourites on the home screen: their faces, a tap opens their page.
 * Drawn when placed and when the favourites change in the app, never on a
 * timer of its own.
 */
class FavoritesWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> manager.updateAppWidget(id, views(context, id)) }
        manager.notifyAppWidgetViewDataChanged(ids, R.id.faces)
    }

    companion object {
        /** Asks the placed widgets to draw the favourites again; nothing when none is placed. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(context, FavoritesWidget::class.java)) }.getOrNull() ?: return
            if (ids.isEmpty()) return
            manager.notifyAppWidgetViewDataChanged(ids, R.id.faces)
        }

        private fun views(context: Context, id: Int) = RemoteViews(context.packageName, R.layout.widget_favorites).apply {
            val adapter = Intent(context, FavoritesWidgetService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .setData(Uri.parse("widget://favorites/$id"))
            setRemoteAdapter(R.id.faces, adapter)
            setEmptyView(R.id.faces, R.id.empty)
            // One explicit intent to this app's page, each face fills in only its contact.
            val open = Intent(context, HandOffActivity::class.java).setAction(Intent.ACTION_VIEW)
            setPendingIntentTemplate(
                R.id.faces,
                PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            )
        }
    }
}
