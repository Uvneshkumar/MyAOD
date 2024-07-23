package uvnesh.myaod

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Resources.getSystem
import android.database.Cursor
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.forEach
import androidx.core.view.isVisible
import androidx.lifecycle.MutableLiveData
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var unlockSound: MediaPlayer

    private lateinit var textViewDate: TextView
    private lateinit var textViewSmallTime: TextView
    private lateinit var textViewLargeTimeHoursOne: TextView
    private lateinit var textViewLargeTimeHoursTwo: TextView
    private lateinit var textViewLargeTimeMinutesOne: TextView
    private lateinit var textViewLargeTimeMinutesTwo: TextView
    private lateinit var textViewInfo: TextView
    private lateinit var textViewBattery: TextView
    private lateinit var textViewWeather: TextView
    private lateinit var textViewAlarm: TextView
    private lateinit var textViewTouchBlock: TextView
    private lateinit var notificationSmall: LinearLayout

    private lateinit var handler: Handler
    private lateinit var timeRunnable: Runnable

    private val notificationPackages = mutableListOf<String>()

    private var currentBrightness = 0

    private lateinit var sharedPrefs: SharedPreferences

    private lateinit var sensorManager: SensorManager
    private var proximitySensor: Sensor? = null

    private fun finishApp() {
        textViewTouchBlock.isVisible = true
        enableTouch()
        setDeviceVolume(maxAndNeededVolume, this)
        unlockSound.start()
        Handler(Looper.getMainLooper()).postDelayed({
            setDeviceVolume(currentVolume, this)
            unlockSound.release()
        }, 500)
        Handler(Looper.getMainLooper()).postDelayed({
            executeCommand("su -c settings put system screen_brightness $currentBrightness")
            finishAndRemoveTask()
        }, 120)
    }

    private fun blockTouch() {
        executeCommand("su -c cp -pr /dev/input /data/adb/aodbackup")
        executeCommand("su -c rm $(getevent -pl 2>&1 | sed -n '/^add/{h}/ABS_MT_TOUCH/{x;s/[^/]*//p}')")
    }

    private fun enableTouch() {
        Handler(Looper.getMainLooper()).postDelayed({
            executeCommand("su -c cp -pr /data/adb/aodbackup/input /dev")
        }, 120)
    }

    override fun onPause() {
        super.onPause()
        if (resources.getBoolean(R.bool.should_lock_screen)) {
            if (!isFinishing) {
                finishAndRemoveTask()
                return
            }
        }
        sensorManager.unregisterListener(this)
        if (!isFinishing) {
            finishApp()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        if (!resources.getBoolean(R.bool.should_lock_screen)) {
            enableEdgeToEdge()
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        textViewTouchBlock = findViewById(R.id.touchBlock)
        if (resources.getBoolean(R.bool.should_lock_screen)) {
            textViewTouchBlock.isVisible = true
            Handler(Looper.getMainLooper()).postDelayed({
                executeCommand("su -c input keyevent 223")
            }, 100)
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        currentBrightness = getCurrentBrightness()
        unlockSound = MediaPlayer.create(this, R.raw.unlock)
        Handler(Looper.getMainLooper()).postDelayed({
            setDeviceVolume(currentVolume, this)
        }, 500)
        onBackPressedDispatcher.addCallback {}
        sharedPrefs = getSharedPreferences("MyPrefs", Context.MODE_PRIVATE)
        textViewDate = findViewById(R.id.date)
        textViewSmallTime = findViewById(R.id.smallTime)
        textViewLargeTimeHoursOne = findViewById(R.id.largeTimeHoursOne)
        textViewLargeTimeHoursTwo = findViewById(R.id.largeTimeHoursTwo)
        textViewLargeTimeMinutesOne = findViewById(R.id.largeTimeMinutesOne)
        textViewLargeTimeMinutesTwo = findViewById(R.id.largeTimeMinutesTwo)
        textViewInfo = findViewById(R.id.info)
        textViewBattery = findViewById(R.id.battery)
        textViewWeather = findViewById(R.id.weather)
        textViewAlarm = findViewById(R.id.alarm)
        textViewTouchBlock.setOnTouchListener { v, event ->
            true
        }
        notificationSmall = findViewById(R.id.notificationSmall)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        handler = Handler(Looper.getMainLooper())
        timeRunnable = object : Runnable {
            override fun run() {
                updateDateTime()
                handler.postDelayed(this, 1000) // 1 second delay
            }
        }
        if (resources.getBoolean(R.bool.should_unlock_on_tap)) {
            findViewById<View>(R.id.fpView).setOnClickListener {
                finishApp()
            }
        } else {
            findViewById<View>(R.id.fpView).setOnLongClickListener {
                finishApp()
                true
            }
        }
        listOf(
            findViewById<ViewGroup>(R.id.largeTimeHoursRoot),
            findViewById<ViewGroup>(R.id.largeTimeMinutesRoot)
        ).forEach {
            it.forEach {
                it.setOnLongClickListener {
                    toggleClock(false)
                    true
                }
            }
        }
        textViewSmallTime.setOnLongClickListener {
            toggleClock(true)
            true
        }
        handler.postDelayed(timeRunnable, 0)
        executeCommand("su -c settings put system screen_brightness ${resources.getInteger(R.integer.aod_brightness)}")
        activeNotifications.observe(this) {
            setNotificationInfo()
        }
        notificationSmall.setOnClickListener {
            executeCommand("su -c service call statusbar 1")
        }
        textViewBattery.post {
            toggleClock(sharedPrefs.getBoolean("isBig", true))
        }
    }

    override fun onResume() {
        super.onResume()
        proximitySensor?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    @SuppressLint("Range")
    fun getNextCalendarEvent(): String? {
        val now = Calendar.getInstance()
        val today = Calendar.getInstance()
        today.set(Calendar.HOUR_OF_DAY, 0)
        today.set(Calendar.MINUTE, 0)
        today.set(Calendar.SECOND, 0)
        today.set(Calendar.MILLISECOND, 0)
        val uri: Uri = CalendarContract.Events.CONTENT_URI
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART
        )
        val selection = "${CalendarContract.Events.DTSTART} >= ?"
        val selectionArgs = arrayOf(now.timeInMillis.toString())
        val cursor: Cursor? = contentResolver.query(
            uri, projection, selection, selectionArgs, CalendarContract.Events.DTSTART
        )
        var nextEvent: String? = null
        cursor?.use {
            if (it.moveToFirst()) {
                val title = it.getString(it.getColumnIndex(CalendarContract.Events.TITLE))
                val startMillis = it.getLong(it.getColumnIndex(CalendarContract.Events.DTSTART))
                // Format the event date/time
                val startDate = Calendar.getInstance()
                startDate.timeInMillis = startMillis
                // Check if the event starts today
                if (startDate.get(Calendar.YEAR) == today.get(Calendar.YEAR) && startDate.get(
                        Calendar.MONTH
                    ) == today.get(Calendar.MONTH) && startDate.get(Calendar.DAY_OF_MONTH) == today.get(
                        Calendar.DAY_OF_MONTH
                    )
                ) {
                    // Calculate time difference in minutes
                    val diffMillis = startMillis - now.timeInMillis
                    val diffMinutes = diffMillis / (1000 * 60)
                    // Format the time until event starts
                    if (diffMinutes <= 30) {
                        nextEvent = "$title in $diffMinutes minutes"
                    } else {
                        // Format the start and end times
                        val dateFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
                        val startTime = dateFormat.format(startDate.time)
                        nextEvent = "$title at $startTime"
                    }
                }
            }
        }
        cursor?.close()
        return nextEvent
    }

    private fun getCurrentBrightness(): Int {
        val command = "su -c settings get system screen_brightness"
        val result = executeCommand(command)
        // Parse the result to extract the brightness value
        return try {
            result.toInt()
        } catch (e: NumberFormatException) {
            e.printStackTrace()
            -1 // Handle parsing error
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateDateTime() {
        val dateFormat = SimpleDateFormat("EEE, dd MMM", Locale.getDefault())
        val timeFormat = SimpleDateFormat("h:mm", Locale.getDefault())
        val currentDate = dateFormat.format(Date())
        var currentTime = timeFormat.format(Date())
        if (currentTime.length == 4) {
            currentTime = "0$currentTime"
        }
        textViewDate.text = currentDate
        textViewSmallTime.text =
            if (currentTime.startsWith("0")) currentTime.substringAfter("0") else currentTime
        textViewLargeTimeHoursOne.text = currentTime.substring(0, 1)
        textViewLargeTimeHoursTwo.text = currentTime.substring(1, 2)
        textViewLargeTimeMinutesOne.text = currentTime.substring(3, 4)
        textViewLargeTimeMinutesTwo.text = currentTime.substring(4, 5)
        val bm = getSystemService(BATTERY_SERVICE) as? BatteryManager
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            applicationContext.registerReceiver(null, ifilter)
        }
        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging =
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        textViewBattery.text =
            (if (isCharging) "Charging  -  " else "") + bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                .toString() + "%"
        val nextEvent = getNextCalendarEvent()
        if (nextEvent != null) {
            // Do something with nextEvent
            textViewInfo.text = "Upcoming event: $nextEvent"
            textViewInfo.isVisible = true
        } else {
            textViewInfo.text = ""
            textViewInfo.isVisible = false
        }
        setAlarmInfo()
    }

    private fun setNotificationInfo() {
        notificationSmall.removeAllViews()
        notificationPackages.clear()
        // Loop through the notifications
        for (notification in activeNotifications.value.orEmpty()) {
            // Extract information from each notification
            val packageName = notification.packageName
            if (notificationPackages.contains(packageName) || notification.notification.visibility == -1) {
                notificationPackages.add(packageName)
                continue
            }
            notificationPackages.add(packageName)
            val id = notification.id
            val tag = notification.tag
            val postTime = notification.postTime
            // Get the notification's icon
            val iconDrawable = notification.notification.smallIcon.loadDrawable(applicationContext)
            // Log or process the notification information as needed
            notificationSmall.addView(ImageView(this).apply {
                post {
                    setPadding(0, 5.px, 5.px, 5.px)
                    layoutParams.height = 34.px
                    layoutParams.width = 34.px
                    requestLayout()
                    setImageDrawable(iconDrawable)
                }
            })
            // You can access more details depending on your needs
            // For example, notification.notification.extras gives you the Notification extras
            // Handle the iconBitmap as needed
            // Example: Display the icon in an ImageView
            // imageView.setImageBitmap(iconBitmap)
        }
    }

    private fun setAlarmInfo() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val nextAlarm = alarmManager.nextAlarmClock
        if (nextAlarm != null) {
            // There is an upcoming alarm
            val alarmTimeMillis = nextAlarm.triggerTime
            // Convert millis to your preferred format (e.g., Date, Calendar)
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = alarmTimeMillis
            // Example of formatting the alarm time
            val sdf = SimpleDateFormat("h:mm", Locale.getDefault())
            val alarmTimeString = sdf.format(calendar.time)
            // Get the current time
            val currentTime = Date()
            // Calculate the time difference in milliseconds
            val timeDifference = calendar.time.time - currentTime.time
            // Convert milliseconds to hours
            val hoursDifference = timeDifference / (1000 * 60 * 60)
            // Check if the time to be checked is within the next 12 hours
            val isWithin12Hours = hoursDifference < 12
            if (isWithin12Hours) {
                // Display or use the alarm time
                textViewAlarm.text = alarmTimeString
                textViewAlarm.isVisible = true
            } else {
                textViewAlarm.text = ""
                textViewAlarm.isVisible = false
            }
        } else {
            // There are no alarms scheduled
            textViewAlarm.text = ""
            textViewAlarm.isVisible = false
        }
    }

    private fun toggleClock(showBigClock: Boolean) {
        sharedPrefs.edit {
            putBoolean("isBig", showBigClock)
        }
        textViewSmallTime.isVisible = !showBigClock
        textViewLargeTimeHoursOne.isVisible = showBigClock
        textViewLargeTimeHoursTwo.isVisible = showBigClock
        textViewLargeTimeMinutesOne.isVisible = showBigClock
        textViewLargeTimeMinutesTwo.isVisible = showBigClock
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_PROXIMITY) {
                // "it.values[0]" gives you the proximity distance in centimeters
                if (it.values[0] <= 0f) {
                    // Proximity sensor is covered
                    // Add your logic here
                    blockTouch()
                    textViewTouchBlock.isVisible = true
                } else {
                    // Proximity sensor is not covered
                    // Add your logic here
                    textViewTouchBlock.isVisible = false
                    enableTouch()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {

        var maxAndNeededVolume: Int = 0
        var currentVolume = 0

        var activeNotifications: MutableLiveData<Array<StatusBarNotification>> =
            MutableLiveData(arrayOf())

        fun executeCommand(command: String): String {
            val process = Runtime.getRuntime().exec(command)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String? = ""
            while (line != null) {
                line = reader.readLine()
                if (line != null) {
                    output.append(line).append("\n")
                }
            }
            // Wait for the process to finish
            process.waitFor()
            // Close the reader
            reader.close()
            // Return the output as a string
            return output.toString().trim()
        }

        fun setDeviceVolume(volumeLevel: Int, applicationContext: Context) {
            val audioManager =
                applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager?
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, volumeLevel, 0)
        }
    }

}

val Int.dp: Int get() = (this / getSystem().displayMetrics.density).toInt()
val Int.px: Int get() = (this * getSystem().displayMetrics.density).toInt()