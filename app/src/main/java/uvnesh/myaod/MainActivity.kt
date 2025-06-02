package uvnesh.myaod

import android.animation.Animator
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources.getSystem
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.ViewCompat.setSystemGestureExclusionRects
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.client.util.DateTime
import com.google.api.services.calendar.CalendarScopes
import com.google.api.services.calendar.model.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uvnesh.myaod.LockWidget.isFromWidget
import uvnesh.myaod.databinding.ActivityMainBinding
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding

    private val shiftHandler = Handler(Looper.getMainLooper())
    private lateinit var handler: Handler
    private lateinit var timeRunnable: Runnable

    private val notificationPackages = mutableListOf<String>()

    private lateinit var sharedPrefs: SharedPreferences
    val hmmaFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    private lateinit var sensorManager: SensorManager
    private var proximitySensor: Sensor? = null

    private lateinit var googleSignInClient: GoogleSignInClient
    private val resultCodeGoogle = 9001
    private val scope = listOf(CalendarScopes.CALENDAR_READONLY)

    private var isSamsung = false

    private var enable_refresh_rate_switching = false
    private var low_refresh_rate = 6
    private var high_refresh_rate = 0

    private val inactivityTimeout: Long = 60_000 // 60 seconds
    private val refreshRateHandler = Handler(Looper.getMainLooper())
    private var isLow = false

    private fun lowRefreshRate() {
        if (!isLow) {
            executeCommand("su -c service call SurfaceFlinger 1035 i32 $low_refresh_rate")
        }
        isLow = true
    }

    private fun highRefreshRate() {
        if (isLow) {
            executeCommand("su -c service call SurfaceFlinger 1035 i32 $high_refresh_rate")
        }
        isLow = false
        resetInactivityTimer()
    }

    private val inactivityRunnable = Runnable {
        lowRefreshRate()
    }

    private fun finishApp() {
        enableTouch()
        if (!isHome) {
            playSound(false)
        }
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
        if (enable_refresh_rate_switching) {
            highRefreshRate()
            refreshRateHandler.removeCallbacks(inactivityRunnable)
        }
        myaod_active = false
        executeCommand("su -c rm -f /sdcard/myaod_active", true)
        handler.removeCallbacks(timeRunnable)
        sensorManager.unregisterListener(this)
        finishApp()
    }

    private val isAppsLoaded = MutableLiveData(false)

    private suspend fun loadAppIcons() {
        withContext(Dispatchers.IO) {
            val appList: List<ApplicationInfo> =
                packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            for (appInfo in appList) {
                val appName: String = appInfo.packageName
                val appIcon: Drawable = packageManager.getApplicationIcon(appInfo)
                appListItems.add(Pair(appName, appIcon))
            }
            isAppsLoaded.postValue(true)
        }
    }

    private fun signIn() {
        val signInIntent = googleSignInClient.signInIntent
        startActivityForResult(signInIntent, resultCodeGoogle)
    }

    @Deprecated("Deprecated")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == resultCodeGoogle) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)
                account?.let {
                    googleSignInAccount = account
                    CoroutineScope(Dispatchers.IO).launch {
                        getCalendarEvents(it)
                    }
                }
            } catch (e: ApiException) {
                Log.e("SignIn", "signInResult:failed code=" + e.statusCode)
            }
        }
    }

    private fun getEndOfDay(): DateTime {
        val calendar = Calendar.getInstance()
        calendar.time = Date()
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 0)
        return DateTime(calendar.time)
    }

    @SuppressLint("SetTextI18n")
    private suspend fun getCalendarEvents(account: GoogleSignInAccount) {
        val credential = GoogleAccountCredential.usingOAuth2(
            this, scope
        )
        credential.selectedAccount = account.account
        try {
            val transport = AndroidHttp.newCompatibleTransport()
            val jsonFactory = JacksonFactory.getDefaultInstance()
            val service = com.google.api.services.calendar.Calendar.Builder(
                transport, jsonFactory, credential
            ).setApplicationName("MyAOD").build()
            var now: DateTime
            var resultsToFetch = 1
            var shouldFetch = true
            var event: Event? = null
            var currentResultCount = 0
            while (shouldFetch) {
                now = DateTime(System.currentTimeMillis())
                val events = service.events().list("primary").setTimeMax(getEndOfDay())
                    .setMaxResults(resultsToFetch).setTimeMin(now).setOrderBy("startTime")
                    .setSingleEvents(true).execute().items
                event = events.firstOrNull {
                    val startTime = it?.start?.dateTime?.value ?: it?.start?.date?.value ?: 0
                    it.attendees.find { attendee -> attendee.email == account.email }?.responseStatus.orEmpty() != "declined" && startTime > now.value
                }
                if (event == null && currentResultCount != events.size) {
                    currentResultCount = events.size
                    resultsToFetch++
                } else {
                    shouldFetch = false
                }
            }
            withContext(Dispatchers.Main) {
                if (event == null) {
                    Log.d("Calendar", "No upcoming events found")
                    binding.infoRoot.isVisible = false
                    binding.info.text = "No upcoming events today"
                    currentInfo = binding.info.text.toString()
                    currentInfoTime = Long.MAX_VALUE
                    // No Events Today. Fetch Again after next day
                    val checkOnNextDayRunnable = object : Runnable {
                        override fun run() {
                            if (hmmaFormat.format(Date()) == "12:00 am") {
                                CoroutineScope(Dispatchers.IO).launch {
                                    getCalendarEvents(account)
                                }
                            } else {
                                // Check if midnight every second and refresh calendar
                                Handler(Looper.getMainLooper()).postDelayed(this, 1000)
                            }
                        }
                    }
                    Handler(Looper.getMainLooper()).post(checkOnNextDayRunnable)
                } else {
                    val start = event.start?.dateTime?.value ?: event.start?.date?.value ?: 0
                    val startDate = Calendar.getInstance()
                    startDate.timeInMillis = start
                    val updateTimeRunnable = object : Runnable {
                        override fun run() {
                            now = DateTime(System.currentTimeMillis())
                            val diffMillis = start - now.value
                            var diffMinutes: Int = (diffMillis / (1000 * 60)).toInt()
                            diffMinutes++
                            if (diffMillis <= 0) {
                                CoroutineScope(Dispatchers.IO).launch {
                                    getCalendarEvents(account)
                                }
                                return
                            }
                            val nextEvent = if (diffMinutes <= 30) {
                                "${event.summary} in $diffMinutes min" + if (diffMinutes > 1) "s" else ""
                            } else {
                                val startTime = hmmaFormat.format(startDate.time)
                                "${event.summary} at $startTime"
                            }
                            if (binding.infoRoot.isGone || binding.info.text == getString(R.string.info)) {
                                binding.infoRoot.isVisible = true
                                binding.info.text = nextEvent
                                currentInfo = binding.info.text.toString()
                                currentInfoTime = startDate.timeInMillis
                                binding.infoRoot.animateAlpha(400)
                            } else if (binding.info.text != nextEvent) {
                                binding.info.text = nextEvent
                                currentInfo = binding.info.text.toString()
                                currentInfoTime = startDate.timeInMillis
                            }
                            Handler(Looper.getMainLooper()).postDelayed(this, 1000)
                        }
                    }
                    Handler(Looper.getMainLooper()).post(updateTimeRunnable)
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private var isHome = false

    private var notificationManager: NotificationManager? = null
    private var lockRingtone: Ringtone? = null
    private var unlockRingtone: Ringtone? = null

    private fun createNotificationChannel() {
        if (notificationManager == null) {
            notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        }
        val lockChannel = NotificationChannel(
            LOCK_CHANNEL, "Lock Channel", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = name.toString()
        }
        val unlockChannel = NotificationChannel(
            UNLOCK_CHANNEL, "Unlock Channel", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = name.toString()
        }
        notificationManager?.createNotificationChannel(lockChannel)
        notificationManager?.createNotificationChannel(unlockChannel)
    }

    private fun playSound(isLock: Boolean = true) {
        if (notificationManager == null) {
            notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        }
        if (isLock) {
            if (resources.getBoolean(R.bool.should_use_lock_volume) && isRingerModeNormal()) {
                if (lockRingtone == null) {
                    lockRingtone = RingtoneManager.getRingtone(
                        applicationContext,
                        notificationManager?.getNotificationChannel(LOCK_CHANNEL)?.sound
                    )
                }
                if (lockRingtone?.getTitle(this).orEmpty().trim().lowercase() != "empty_audio") {
                    lockRingtone?.play()
                }
            }
        } else {
            if (resources.getBoolean(R.bool.should_use_unlock_volume) && isRingerModeNormal()) {
                if (unlockRingtone == null) {
                    unlockRingtone = RingtoneManager.getRingtone(
                        applicationContext,
                        notificationManager?.getNotificationChannel(UNLOCK_CHANNEL)?.sound
                    )
                }
                if (unlockRingtone?.getTitle(this).orEmpty().trim().lowercase() != "empty_audio") {
                    unlockRingtone?.play()
                }
            }
        }
    }

    private fun goHome() {
        playSound(false)
        isHome = true
        executeCommand("su -c input keyevent 3", true)
        binding.rootAnim.alpha = 1f
        binding.rootAnim.isVisible = true
        binding.rootAnim.post {
            binding.main.translationY = topMargin()
        }
    }

    private fun isRingerModeNormal(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val ringerMode = audioManager.ringerMode
        return ringerMode == AudioManager.RINGER_MODE_NORMAL
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        enableEdgeToEdge()
        setShowWhenLocked(false)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        @SuppressLint("InternalInsetResource", "DiscouragedApi") val resourceId: Int =
            resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            statusBarHeight = resources.getDimensionPixelSize(resourceId)
        }
        createNotificationChannel()
        val height = getSystem().displayMetrics.heightPixels
        val width = getSystem().displayMetrics.widthPixels
        val exclusionRects = listOf(Rect(0, 0, width, height))
        setSystemGestureExclusionRects(binding.main, exclusionRects)
        findViewById<View>(android.R.id.content).setBackgroundColor(getColor(android.R.color.black))
        lifecycleScope.launch {
            loadAppIcons()
        }
        isSamsung =
            Build.BRAND == "samsung" && executeCommand("su -c getprop ro.build.version.oneui").trim()
                .isNotEmpty()
        enable_refresh_rate_switching = resources.getBoolean(R.bool.enable_refresh_rate_switching)
        low_refresh_rate = resources.getInteger(R.integer.low_refresh_rate)
        high_refresh_rate = resources.getInteger(R.integer.high_refresh_rate)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onBackPressedDispatcher.addCallback {}
        sharedPrefs = getSharedPreferences("MyPrefs", Context.MODE_PRIVATE)
        val scaleFactor = resources.getFloat(R.dimen.ui_scale)
        binding.innerLayout.scaleX = scaleFactor
        binding.innerLayout.scaleY = scaleFactor
        binding.touchBlock.setOnTouchListener { v, event ->
            true
        }
        currentInfo?.let {
            if (binding.info.text == getString(R.string.info) && (Date().time < currentInfoTime)) {
                binding.info.text = it
            }
        }
        if (googleSignInAccount == null) {
            val gso =
                GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail()
                    .requestScopes(Scope(CalendarScopes.CALENDAR_READONLY)).build()
            googleSignInClient = GoogleSignIn.getClient(this@MainActivity, gso)
            signIn()
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                googleSignInAccount?.let {
                    getCalendarEvents(it)
                }
            }
        }
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        if (resources.getBoolean(R.bool.should_use_proximity)) {
            proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        }
        handler = Handler(Looper.getMainLooper())
        timeRunnable = object : Runnable {
            override fun run() {
                updateDateTime()
                handler.postDelayed(this, 1000) // 1 second delay
            }
        }
        binding.swipeDetectableView.setOnClickCallback {
            goHome()
        }
        if (resources.getBoolean(R.bool.should_allow_clock_switching)) {
            binding.swipeDetectableView.setOnLongPressCallback {
                toggleClock(!sharedPrefs.getBoolean("isBig", false))
            }
        }
        isAppsLoaded.observe(this, object : Observer<Boolean> {
            override fun onChanged(value: Boolean) {
                if (value) {
                    isAppsLoaded.removeObserver(this)
                    activeNotifications.observe(this@MainActivity) {
                        setNotificationInfo()
//                        notificationSmall.animateAlpha(200)
                    }
                }
            }
        })
        binding.battery.post {
            toggleClock(sharedPrefs.getBoolean("isBig", false))
        }
        startPeriodicShifting()
    }

    private fun startPeriodicShifting() {
        val shiftInterval = 600000L // 10 Minutes
        shiftHandler.post(object : Runnable {
            override fun run() {
                shiftContent()
                shiftHandler.postDelayed(this, shiftInterval)
            }
        })
    }

    private data class ShiftValues(val x: Float, val y: Float)

    private val shiftMap = mapOf(
        0f to ShiftValues(-5f, -20f),
        -25f to ShiftValues(5f, 20f),
        25f to ShiftValues(-5f, 20f),
        15f to ShiftValues(5f, -20f),
        -15f to ShiftValues(-5f, -20f)
    )

    private fun getShiftValues(currentTotal: Float): ShiftValues {
        return shiftMap[currentTotal] ?: ShiftValues(0f, 0f)
    }

    private fun shiftContent() {
        binding.rootAnim.isVisible = true
        binding.rootAnim.post {
            binding.rootAnim.isVisible = false
        }
        val totalTranslation = binding.innerLayout.translationX + binding.innerLayout.translationY
        val (shiftX, shiftY) = getShiftValues(totalTranslation)
        binding.innerLayout.animate()
            .translationX(shiftX)
            .translationY(shiftY)
            .setDuration(500L)
            .start()
    }

    private fun topMargin(): Float {
        return if (intent.getBooleanExtra(
                isFromWidget, false
            )
        ) 80.px.toFloat() else -40.px.toFloat()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (enable_refresh_rate_switching) {
            if (ev?.action == MotionEvent.ACTION_DOWN) {
                highRefreshRate()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun resetInactivityTimer() {
        refreshRateHandler.removeCallbacks(inactivityRunnable)
        refreshRateHandler.postDelayed(inactivityRunnable, inactivityTimeout)
    }

    override fun onResume() {
        super.onResume()
        if (enable_refresh_rate_switching) {
            highRefreshRate()
        }
        handler.removeCallbacks(timeRunnable)
        handler.post(timeRunnable)
        binding.rootAnim.alpha = 1f
        playSound()
        binding.smallTime.post {
            if (isHome) {
                isHome = false
                val animDuration = 600L
                binding.rootAnim.animateAlpha((animDuration * 1.1).toLong(), true)
                binding.main.apply {
                    val animator =
                        ObjectAnimator.ofFloat(this@apply, "translationY", topMargin(), 0f)
                    animator.duration = animDuration
                    animator.addListener(object : Animator.AnimatorListener {
                        override fun onAnimationStart(animation: Animator) {}
                        override fun onAnimationEnd(animation: Animator) {
                            endBlock()
                        }

                        override fun onAnimationCancel(animation: Animator) {}
                        override fun onAnimationRepeat(animation: Animator) {}
                    })
                    animator.start()
                }
            } else {
                endBlock()
            }
        }
        myaod_active = true
        executeCommand("su -c touch /sdcard/myaod_active", true)
    }

    private fun endBlock() {
        proximitySensor?.also { sensor ->
            sensorManager.registerListener(
                this@MainActivity, sensor, SensorManager.SENSOR_DELAY_NORMAL
            )
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateDateTime() {
        val dateFormat = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
        val timeFormat = SimpleDateFormat("h:mm", Locale.getDefault())
        val currentDate = dateFormat.format(Date())
        var currentTime = timeFormat.format(Date())
        if (currentTime.length == 4) {
            currentTime = "0$currentTime"
        }
        binding.date.text = currentDate
        binding.largeTimeHoursOne.text = currentTime.substring(0, 1)
        binding.largeTimeHoursTwo.text = currentTime.substring(1, 2)
        binding.largeTimeMinutesOne.text = currentTime.substring(3, 4)
        binding.largeTimeMinutesTwo.text = currentTime.substring(4, 5)
        binding.smallTime.text =
            if (currentTime.startsWith("0")) currentTime.substringAfter("0") else currentTime
        val bm = getSystemService(BATTERY_SERVICE) as? BatteryManager
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            applicationContext.registerReceiver(null, ifilter)
        }
        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging =
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        var chargingText =
            (if (isCharging) "Charging  -  " else "") + bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                .toString() + "%"
        if (chargingText.contains("-  100")) {
            chargingText = "Charged"
        }
        binding.battery.text = chargingText
        setAlarmInfo()
    }

    private fun setNotificationInfo() {
        if (enable_refresh_rate_switching) {
            highRefreshRate()
        }
        binding.notificationSmall.removeAllViews()
        notificationPackages.clear()
        binding.mediaItem.text = ""
        if (!binding.touchBlock.isVisible) {
            val fullScreenOrSamsungAlarmNotification = activeNotifications.value.orEmpty()
                .firstOrNull { it.notification.fullScreenIntent != null || (it.packageName == "com.sec.android.app.clockpackage" && it.notification.channelId == "notification_channel_firing_alarm_and_timer") }
            if (fullScreenOrSamsungAlarmNotification != null) {
                if (enable_refresh_rate_switching) {
                    highRefreshRate()
                }
                if (isSamsung) {
                    executeCommand("su -c cmd statusbar expand-notifications", true)
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    executeCommand(
                        "su -c input tap 400 ${
                            if (isSamsung) {
                                if (fullScreenOrSamsungAlarmNotification.notification.fullScreenIntent == null) 400 else 340
                            } else 200
                        }", true
                    )
                }, 1000)
                return
            }
        }
        // Loop through the notifications
        for (notification in activeNotifications.value.orEmpty()) {
            // Extract information from each notification
            val packageName = notification.packageName
            if (notificationPackages.contains(packageName) || notification.notification.visibility == -1 || (packageName == "com.android.systemui" && notification.notification.channelId == "CHR")) {
                notificationPackages.add(packageName)
                continue
            }
            notificationPackages.add(packageName)
            val id = notification.id
            val tag = notification.tag
            val postTime = notification.postTime
            // Get the notification's icon
            val iconDrawable = appListItems.find { it.first == notification.packageName }?.second
            // Log or process the notification information as needed
            binding.notificationSmall.addView(ImageView(this).apply {
                post {
                    setPadding(0, 5.px, 5.px, 5.px)
                    layoutParams.height = 50.px
                    layoutParams.width = 50.px
                    requestLayout()
                    setImageDrawable(iconDrawable)
                    if (notification.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
                        binding.mediaItem.text =
                            notification.notification.extras.getString(Notification.EXTRA_TITLE)
                    }
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
                binding.alarm.text = alarmTimeString
                binding.alarm.isVisible = true
            } else {
                binding.alarm.text = ""
                binding.alarm.isVisible = false
            }
        } else {
            // There are no alarms scheduled
            binding.alarm.text = ""
            binding.alarm.isVisible = false
        }
    }

    private fun toggleClock(showBigClock: Boolean) {
        sharedPrefs.edit {
            putBoolean("isBig", showBigClock)
        }
        binding.smallTime.isVisible = !showBigClock
        binding.largeTimeHoursOne.isVisible = showBigClock
        binding.largeTimeHoursTwo.isVisible = showBigClock
        binding.largeTimeMinutesOne.isVisible = showBigClock
        binding.largeTimeMinutesTwo.isVisible = showBigClock
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_PROXIMITY) {
                // "it.values[0]" gives you the proximity distance in centimeters
                if (it.values[0] <= 0f) {
                    // Proximity sensor is covered
                    // Add your logic here
                    binding.touchBlock.isVisible = true
                    blockTouch()
                } else {
                    // Proximity sensor is not covered
                    // Add your logic here
                    enableTouch()
                    binding.touchBlock.isVisible = false
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {

        private const val LOCK_CHANNEL = "LOCK_CHANNEL"
        private const val UNLOCK_CHANNEL = "UNLOCK_CHANNEL"

        var statusBarHeight = 0
        val appListItems: MutableSet<Pair<String, Drawable>> = mutableSetOf()
        var myaod_active = false

        var currentInfo: String? = null
        var currentInfoTime: Long = 0

        var googleSignInAccount: GoogleSignInAccount? = null

        var activeNotifications: MutableLiveData<Array<StatusBarNotification>> =
            MutableLiveData(arrayOf())

        // isAsync should always be false to avoid unknown recursive loop
        fun executeCommand(command: String, isAsync: Boolean = false): String {
            if (isAsync) {
                CoroutineScope(Dispatchers.IO).launch {
                    executeCommand(command)
                }
                return ""
            }
            try {
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
            } catch (ignored: Exception) {
                return ShizukuShell(arrayListOf(), command.substringAfter("su -c ")).exec()
            }
        }
    }

}

val Int.dp: Int get() = (this / getSystem().displayMetrics.density).toInt()
val Int.px: Int get() = (this * getSystem().displayMetrics.density).toInt()

fun View.animateAlpha(
    duration: Long = 100, isReverse: Boolean = false, postCompletion: () -> Unit = {}
) {
    alpha = if (isReverse) 1f else 0f
    Handler(Looper.getMainLooper()).post {
        animate().alpha(if (isReverse) 0f else 1f).setListener(object : Animator.AnimatorListener {
            override fun onAnimationStart(animation: Animator) {}
            override fun onAnimationCancel(animation: Animator) {}
            override fun onAnimationRepeat(animation: Animator) {}
            override fun onAnimationEnd(animation: Animator) {
                if (isReverse) {
                    this@animateAlpha.isVisible = false
                }
                postCompletion()
            }
        }).duration = duration
    }
}