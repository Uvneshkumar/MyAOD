package uvnesh.myaod

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class MyForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "FOREGROUND_SERVICE"
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID, "My App Service Channel", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Channel for foreground service"
        }
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification =
            NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("My AOD is Running")
                .setSmallIcon(android.R.drawable.sym_def_app_icon).setOngoing(true).build()
        startForeground(1, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
