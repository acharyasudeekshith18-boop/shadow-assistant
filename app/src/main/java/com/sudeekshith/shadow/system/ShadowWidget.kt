package com.sudeekshith.shadow.system

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.sudeekshith.shadow.MainActivity
import com.sudeekshith.shadow.R

/** Home-screen widget: one tap opens Shadow already listening. */
class ShadowWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val i = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_LISTEN, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(context, 1, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_shadow)
            views.setOnClickPendingIntent(R.id.widget_root, pi)
            manager.updateAppWidget(id, views)
        }
    }
}
