package uvnesh.myaod

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import uvnesh.myaod.MainActivity.Companion.channelId

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private fun createNotificationChannel() {
        val channelName = "Keep Running"
        val channelDescription = "Can be turned off"
        val importance = NotificationManager.IMPORTANCE_HIGH
        val channel = NotificationChannel(channelId, channelName, importance).apply {
            description = channelDescription
        }
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        val serviceIntent = Intent(this, MyForegroundService::class.java)
        startForegroundService(serviceIntent)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}