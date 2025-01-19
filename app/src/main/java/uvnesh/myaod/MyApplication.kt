package uvnesh.myaod

import android.app.Application
import android.content.Intent

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val serviceIntent = Intent(this, MyForegroundService::class.java)
        startService(serviceIntent)
    }
}