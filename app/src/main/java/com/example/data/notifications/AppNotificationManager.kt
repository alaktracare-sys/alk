package com.example.data.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.repository.MusicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

class AppNotificationManager(private val context: Context) {
    private val CHANNEL_ID = "alaktra_admin_notifications"
    private val CHANNEL_NAME = "Alaktra Announcements & Updates"
    private val prefs = context.getSharedPreferences("alaktra_notifications_prefs", Context.MODE_PRIVATE)

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Broadcast updates and news from Alaktra"
                enableVibration(true)
                enableLights(true)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun getDeviceId(): String {
        var deviceId = prefs.getString("device_installation_id", null)
        if (deviceId.isNullOrBlank()) {
            deviceId = UUID.randomUUID().toString()
            prefs.edit().putString("device_installation_id", deviceId).apply()
        }
        return deviceId
    }

    fun registerDeviceTokenWithServer(userId: Int? = null) {
        val deviceId = getDeviceId()
        val deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = MusicRepository.getInstance(context)
                repository.registerDeviceToken(
                    userId = userId,
                    token = deviceId,
                    deviceInfo = deviceInfo
                )
            } catch (e: Exception) {
                // Ignore silent network fail, will retry next start
            }
        }
    }

    fun checkForNewNotifications(userId: Int? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = MusicRepository.getInstance(context)
                val notifications = repository.getNotificationHistory(userId)
                if (notifications.isEmpty()) return@launch

                val shownIds = prefs.getStringSet("shown_notification_ids", emptySet()) ?: emptySet()
                val newShownIds = shownIds.toMutableSet()

                for (item in notifications) {
                    val idStr = item.id.toString()
                    if (!shownIds.contains(idStr)) {
                        showSystemNotification(item.id, item.title, item.message)
                        newShownIds.add(idStr)
                    }
                }
                prefs.edit().putStringSet("shown_notification_ids", newShownIds).apply()
            } catch (_: Exception) {
            }
        }
    }

    fun showSystemNotification(id: Int, title: String, message: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(id, builder.build())
    }
}
