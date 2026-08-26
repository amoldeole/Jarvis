package com.jarvis.phoneguardian.widget

import android.appwidget.AppWidgetManager
import android.app.PendingIntent
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.jarvis.phoneguardian.R
import com.jarvis.phoneguardian.MainActivity
import com.jarvis.phoneguardian.ui.GuardianViewModel

class StorageWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        Thread {
            try {
                val total = android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path).totalBytes
                val free = android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path).availableBytes
                val used = total - free
                val text = "${GuardianViewModel.formatBytes(used)} / ${GuardianViewModel.formatBytes(total)} used\nFree: ${GuardianViewModel.formatBytes(free)}"
                ids.forEach { id ->
                    val launch = PendingIntent.getActivity(
                        context, 100, Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    manager.updateAppWidget(id, RemoteViews(context.packageName, R.layout.widget_storage).apply {
                        setTextViewText(R.id.widget_value, text)
                        setOnClickPendingIntent(R.id.widget_title, launch)
                        setOnClickPendingIntent(R.id.widget_value, launch)
                    })
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
