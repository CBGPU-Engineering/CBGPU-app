package com.cbgpu.cbgpu_app.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri

abstract class NativeWidgetProvider : AppWidgetProvider() {
    final override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        onUpdate(
            context,
            manager,
            ids,
            context.getSharedPreferences("HomeWidgetPreferences", Context.MODE_PRIVATE),
        )
    }

    abstract fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        widgetData: SharedPreferences,
    )
}

object NativeWidgetLaunchIntent {
    fun getActivity(context: Context, activity: Class<*>, uri: Uri): PendingIntent =
        PendingIntent.getActivity(
            context,
            uri.toString().hashCode(),
            Intent(context, activity)
                .setAction(Intent.ACTION_VIEW)
                .setData(uri)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
