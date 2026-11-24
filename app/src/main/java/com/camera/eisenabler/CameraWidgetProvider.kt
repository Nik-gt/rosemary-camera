package com.camera.eisenabler

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class CameraWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        const val ACTION_WIDGET_CLICK = "com.camera.eisenabler.WIDGET_CLICK"

        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            // Проверяем состояние записи
            val prefs = context.getSharedPreferences("camera_widget", Context.MODE_PRIVATE)
            val isRecording = prefs.getBoolean("is_recording", false)

            // Меняем иконку в зависимости от состояния
            val iconRes = if (isRecording) android.R.drawable.ic_media_pause else android.R.drawable.ic_menu_camera
            views.setImageViewResource(R.id.widgetIcon, iconRes)

            // Создаем PendingIntent для клика
			val intent = Intent(context, MainActivity::class.java).apply {
				putExtra("EXTRA_QUICK_RECORD", true)
				addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
			}
			val pendingIntent = PendingIntent.getActivity(
				context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
			)
			views.setOnClickPendingIntent(R.id.widgetIcon, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        if (intent.action == ACTION_WIDGET_CLICK) {
            val prefs = context.getSharedPreferences("camera_widget", Context.MODE_PRIVATE)
            val isRecording = prefs.getBoolean("is_recording", false)

            if (isRecording) {
                // Останавливаем запись
                prefs.edit().putBoolean("is_recording", false).apply()
                //MainActivity.stopBackgroundMode()
                
                val serviceIntent = Intent(context, BackgroundRecordService::class.java)
                context.stopService(serviceIntent)
            } else {
                // Запускаем запись
                prefs.edit().putBoolean("is_recording", true).apply()
                
                val serviceIntent = Intent(context, BackgroundRecordService::class.java)
                context.startForegroundService(serviceIntent)
                
                //MainActivity.startBackgroundMode(context)
            }

            // Обновляем виджет
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val views = RemoteViews(context.packageName, R.layout.widget_layout)
            val iconRes = if (!isRecording) android.R.drawable.ic_media_pause else android.R.drawable.ic_menu_camera
            views.setImageViewResource(R.id.widgetIcon, iconRes)
            
            val intent = Intent(context, CameraWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_CLICK
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widgetIcon, pendingIntent)
            
            val ids = appWidgetManager.getAppWidgetIds(android.content.ComponentName(context, CameraWidgetProvider::class.java))
            appWidgetManager.updateAppWidget(ids, views)
        }
    }
}