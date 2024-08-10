package uvnesh.myaod

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import uvnesh.myaod.MainActivity.Companion.currentVolume
import uvnesh.myaod.MainActivity.Companion.maxAndNeededVolume
import uvnesh.myaod.MainActivity.Companion.setDeviceVolume

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (resources.getBoolean(R.bool.should_lock_screen)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        } else {
            currentVolume = getCurrentDeviceVolume()
            maxAndNeededVolume =
                (maxAndNeededVolume * resources.getInteger(R.integer.volume_percentage) / 100.0).toInt()
            setDeviceVolume(maxAndNeededVolume, this)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    private fun getCurrentDeviceVolume(): Int {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager?
        maxAndNeededVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
        return audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
    }
}