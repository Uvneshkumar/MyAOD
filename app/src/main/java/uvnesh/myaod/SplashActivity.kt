package uvnesh.myaod

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import uvnesh.myaod.MainActivity.Companion.currentVolume
import uvnesh.myaod.MainActivity.Companion.maxAndNeededVolume
import uvnesh.myaod.MainActivity.Companion.setDeviceVolume

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var lockSound: MediaPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        currentVolume = getCurrentDeviceVolume()
        maxAndNeededVolume = (maxAndNeededVolume * (70.0 / 100.0)).toInt()
        lockSound = MediaPlayer.create(this, R.raw.lock)
        setDeviceVolume(maxAndNeededVolume, this)
        lockSound.start()
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        lockSound.release()
        super.onDestroy()
    }

    private fun getCurrentDeviceVolume(): Int {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager?
        maxAndNeededVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
        return audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
    }
}