package uvnesh.myaod

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import uvnesh.myaod.MainActivity.Companion.channelId

class MyForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildOngoingNotification().build()
        startForeground(1, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun buildOngoingNotification(): NotificationCompat.Builder {
        return NotificationCompat.Builder(this, channelId).setContentTitle("App is Running")
            .setContentText("Your app is running in the background.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_MAX).setOngoing(true)
    }
}
