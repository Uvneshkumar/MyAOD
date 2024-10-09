package uvnesh.myaod

import android.animation.Animator
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources.getSystem
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.util.Log
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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var lockSound: MediaPlayer
    private lateinit var unlockSound: MediaPlayer

    private lateinit var textViewDate: TextView
    private lateinit var textViewSmallTime: TextView
    private lateinit var textViewLargeTimeHoursOne: TextView
    private lateinit var textViewLargeTimeHoursTwo: TextView
    private lateinit var textViewLargeTimeMinutesOne: TextView
    private lateinit var textViewLargeTimeMinutesTwo: TextView
    private lateinit var textViewInfo: TextView
    private lateinit var textViewBattery: TextView
    private lateinit var infoRoot: LinearLayout
    private lateinit var textViewAlarm: TextView
    private lateinit var textViewTouchBlock: TextView
    private lateinit var rootAnim: View
    private lateinit var notificationSmall: LinearLayout
    private lateinit var brightnessRestoreRoot: View

    private lateinit var handler: Handler
    private lateinit var timeRunnable: Runnable

    private lateinit var lightHandler: Handler
    private lateinit var lightTimeRunnable: Runnable

    private val notificationPackages = mutableListOf<String>()

    private lateinit var sharedPrefs: SharedPreferences
    val hmmaFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    private lateinit var sensorManager: SensorManager
    private var proximitySensor: Sensor? = null
    private var lightSensor: Sensor? = null

    private var isFullScreenNotificationTriggered = false
    private var shouldTriggerLogin = false
    private var isLoginTriggered = false

    private lateinit var googleSignInClient: GoogleSignInClient
    private val resultCodeGoogle = 9001
    private val scope = listOf(CalendarScopes.CALENDAR_READONLY)

    private fun finishApp(shouldMinimise: Boolean = false) {
        if (!shouldMinimise) {
            textViewTouchBlock.animateAlpha(240)
            textViewTouchBlock.isVisible = true
        }
        enableTouch()
        if (!isHome) {
            playSound(false)
        }
        Handler(Looper.getMainLooper()).postDelayed({
            executeCommand("su -c settings put system screen_brightness $currentBrightness")
            currentBrightness = -1
            if (!shouldMinimise) {
                finishAndRemoveTask()
            }
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
        if (shouldTriggerLogin) {
            isLoginTriggered = true
        }
        if (isFullScreenNotificationTriggered || isLoginTriggered) {
            return
        }
        if (resources.getBoolean(R.bool.should_lock_screen)) {
            if (!isFinishing) {
                finishAndRemoveTask()
                return
            }
        }
        handler.removeCallbacks(timeRunnable)
        lightHandler.removeCallbacks(lightTimeRunnable)
        sensorManager.unregisterListener(this)
//        if (!isFinishing) {
//            finishApp()
        finishApp(resources.getBoolean(R.bool.should_minimise))
//        }
    }

    private val appListItems: MutableSet<Pair<String, Drawable>> = mutableSetOf()
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
        shouldTriggerLogin = true
        startActivityForResult(signInIntent, resultCodeGoogle)
    }

    @Deprecated("Deprecated")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        shouldTriggerLogin = false
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
                    infoRoot.isVisible = true
                    textViewInfo.text = "No upcoming events today"
                    currentInfo = textViewInfo.text.toString()
                    currentInfoTime = Long.MAX_VALUE
                    infoRoot.animateAlpha(400)
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
                            if (infoRoot.isGone || textViewInfo.text == getString(R.string.info)) {
                                infoRoot.isVisible = true
                                textViewInfo.text = nextEvent
                                currentInfo = textViewInfo.text.toString()
                                currentInfoTime = startDate.timeInMillis
                                infoRoot.animateAlpha(400)
                            } else if (textViewInfo.text != nextEvent) {
                                textViewInfo.text = nextEvent
                                currentInfo = textViewInfo.text.toString()
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

    private fun playSound(isLock: Boolean = true) {
        if (resources.getBoolean(R.bool.should_use_volume) && isRingerModeNormal()) {
            setDeviceVolume(maxAndNeededVolume, this)
            if (isLock) {
                lockSound.start()
            } else {
                unlockSound.start()
            }
        }
    }

    private fun goHome() {
        playSound(false)
        isHome = true
        executeCommand("su -c settings put system screen_brightness $currentBrightness", true)
        executeCommand("su -c input keyevent 3", true)
        rootAnim.alpha = 1f
        rootAnim.isVisible = true
        rootAnim.post {
            findViewById<View>(R.id.main).translationY = topMargin
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
        if (!resources.getBoolean(R.bool.should_lock_screen)) {
            enableEdgeToEdge()
        }
        super.onCreate(savedInstanceState)
        lockSound = MediaPlayer.create(this, R.raw.lock)
        setContentView(R.layout.activity_main)
        findViewById<View>(android.R.id.content).setBackgroundColor(getColor(android.R.color.black))
        lifecycleScope.launch {
            loadAppIcons()
        }
        textViewTouchBlock = findViewById(R.id.touchBlock)
        rootAnim = findViewById(R.id.rootAnim)
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
        onBackPressedDispatcher.addCallback {}
        lockSound.setOnCompletionListener {
            setDeviceVolume(currentVolume, this)
        }
        unlockSound.setOnCompletionListener {
            setDeviceVolume(currentVolume, this)
        }
        sharedPrefs = getSharedPreferences("MyPrefs", Context.MODE_PRIVATE)
        textViewDate = findViewById(R.id.date)
        textViewSmallTime = findViewById(R.id.smallTime)
        textViewLargeTimeHoursOne = findViewById(R.id.largeTimeHoursOne)
        textViewLargeTimeHoursTwo = findViewById(R.id.largeTimeHoursTwo)
        textViewLargeTimeMinutesOne = findViewById(R.id.largeTimeMinutesOne)
        textViewLargeTimeMinutesTwo = findViewById(R.id.largeTimeMinutesTwo)
        textViewInfo = findViewById(R.id.info)
        textViewBattery = findViewById(R.id.battery)
        infoRoot = findViewById(R.id.info_root)
        textViewAlarm = findViewById(R.id.alarm)
        textViewTouchBlock.setOnTouchListener { v, event ->
            true
        }
        notificationSmall = findViewById(R.id.notificationSmall)
        brightnessRestoreRoot = findViewById(R.id.brightnessRestoreRoot)
        currentInfo?.let {
            if (textViewInfo.text == getString(R.string.info) && (Date().time < currentInfoTime)) {
                textViewInfo.text = it
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
        if (resources.getBoolean(R.bool.should_use_light)) {
            lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        }
        handler = Handler(Looper.getMainLooper())
        timeRunnable = object : Runnable {
            override fun run() {
                updateDateTime()
                handler.postDelayed(this, 1000) // 1 second delay
            }
        }
        lightHandler = Handler(Looper.getMainLooper())
        lightTimeRunnable = Runnable {
            lightSensor?.also { sensor ->
                sensorManager.registerListener(
                    this@MainActivity, sensor, SensorManager.SENSOR_DELAY_NORMAL
                )
            }
        }
        if (resources.getBoolean(R.bool.should_unlock_on_tap)) {
            findViewById<View>(R.id.fpView).setOnClickListener {
//                finishApp()
                goHome()
            }
        } else {
            findViewById<View>(R.id.fpView).setOnLongClickListener {
//                finishApp()
                goHome()
                true
            }
        }
        if (resources.getBoolean(R.bool.should_allow_clock_switching)) {
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
        }
        executeCommand("su -c settings put system screen_brightness ${resources.getInteger(R.integer.aod_brightness)}")
        isAppsLoaded.observe(this, object : Observer<Boolean> {
            override fun onChanged(value: Boolean) {
                if (value) {
                    isAppsLoaded.removeObserver(this)
                    activeNotifications.observe(this@MainActivity) {
                        setNotificationInfo()
                        notificationSmall.animateAlpha(200)
                    }
                }
            }
        })
        toggleTorch.observe(this) {
            if (resources.getBoolean(R.bool.should_use_torch)) {
                val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return@observe
                try {
                    cameraManager.setTorchMode(cameraId, it)
                } catch (e: CameraAccessException) {
                    e.printStackTrace()
                }
            }
        }
        brightnessRestoreRoot.setOnClickListener {
            shouldShowRestoreBrightness.postValue(false)
            executeCommand("su -c settings put system screen_brightness ${resources.getInteger(R.integer.aod_brightness)}")
            enableLight()
        }
        shouldShowRestoreBrightness.observe(this) {
            brightnessRestoreRoot.isVisible = it
        }
        notificationSmall.setOnClickListener {
            executeCommand("su -c service call statusbar 1")
        }
        textViewBattery.post {
            toggleClock(sharedPrefs.getBoolean("isBig", false))
        }
    }

    private val topMargin = -100.px.toFloat()

    private fun enableLight() {
        lightHandler.removeCallbacks(lightTimeRunnable)
        lightHandler.post(lightTimeRunnable)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(timeRunnable)
        handler.post(timeRunnable)
        rootAnim.alpha = 1f
        if (!isFullScreenNotificationTriggered && !isLoginTriggered) {
            currentVolume = getCurrentDeviceVolume(this)
            if (currentBrightness == -1) currentBrightness = getCurrentBrightness()
            maxAndNeededVolume =
                (maxAndNeededVolume * resources.getInteger(R.integer.volume_percentage) / 100.0).toInt()
            playSound()
        }
        executeCommand("su -c settings put system screen_brightness ${resources.getInteger(R.integer.aod_brightness)}")
        textViewSmallTime.post {
            if (isHome) {
                isHome = false
                val animDuration = 500L
                rootAnim.animateAlpha(animDuration, true)
                findViewById<View>(R.id.main).apply {
                    val animator = ObjectAnimator.ofFloat(this@apply, "translationY", topMargin, 0f)
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
    }

    private fun endBlock() {
        if (isFullScreenNotificationTriggered) {
//            toggleTorch.postValue(false)
        } else if (isLoginTriggered) {
            isLoginTriggered = false
        } else {
            proximitySensor?.also { sensor ->
                sensorManager.registerListener(
                    this@MainActivity, sensor, SensorManager.SENSOR_DELAY_NORMAL
                )
            }
        }
        isFullScreenNotificationTriggered = false
        enableLight()
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
        textViewLargeTimeHoursOne.text = currentTime.substring(0, 1)
        textViewLargeTimeHoursTwo.text = currentTime.substring(1, 2)
        textViewLargeTimeMinutesOne.text = currentTime.substring(3, 4)
        textViewLargeTimeMinutesTwo.text = currentTime.substring(4, 5)
        textViewSmallTime.text =
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
        textViewBattery.text = chargingText
        setAlarmInfo()
    }

    private fun setNotificationInfo() {
        notificationSmall.removeAllViews()
        notificationPackages.clear()
        // Loop through the notifications
        for (notification in activeNotifications.value.orEmpty()) {
            if (notification.notification?.fullScreenIntent != null && notification.notification.channelId == "Firing" && notification.packageName == "com.google.android.deskclock" && notification.notification.actions?.size == 2) {
                // Max Brightness to make Alarm more "disturbing"
                isFullScreenNotificationTriggered = true
                executeCommand("su -c settings put system screen_brightness 255")
                Handler(Looper.getMainLooper()).postDelayed({
//                    toggleTorch.postValue(true)
                    executeCommand("su -c input tap 400 200")
                }, 1000)
                continue
            }
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
            val iconDrawable = appListItems.find { it.first == notification.packageName }?.second
            // Log or process the notification information as needed
            notificationSmall.addView(ImageView(this).apply {
                post {
                    setPadding(0, 5.px, 5.px, 5.px)
                    layoutParams.height = 36.px
                    layoutParams.width = 36.px
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
                    enableLight()
                }
            } else if (!isFullScreenNotificationTriggered && it.sensor.type == Sensor.TYPE_LIGHT) {
                val localCurrentBrightness = getCurrentBrightness()
                if (it.values[0] <= 5 && localCurrentBrightness != resources.getInteger(R.integer.aod_brightness_low) && localCurrentBrightness != currentBrightness && shouldShowRestoreBrightness.value != true) {
                    executeCommand(
                        "su -c settings put system screen_brightness ${
                            resources.getInteger(
                                R.integer.aod_brightness_low
                            )
                        }"
                    )
                } else if (it.values[0] > 5 && localCurrentBrightness != resources.getInteger(R.integer.aod_brightness) && localCurrentBrightness != currentBrightness && shouldShowRestoreBrightness.value != true) {
                    executeCommand(
                        "su -c settings put system screen_brightness ${
                            resources.getInteger(
                                R.integer.aod_brightness
                            )
                        }"
                    )
                }
                sensorManager.unregisterListener(this, lightSensor)
                lightHandler.postDelayed(lightTimeRunnable, 5000) // 5 Second Delay
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        lockSound.release()
        unlockSound.release()
        super.onDestroy()
//        Don't kill as it delays Notifications when app is launched again
//        executeCommand("su -c killall $packageName")
    }

    companion object {

        var maxAndNeededVolume: Int = 0
        var currentVolume = 0
        val toggleTorch = MutableLiveData(false)
        val shouldShowRestoreBrightness = MutableLiveData(false)
        var currentBrightness = -1

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
            if (command.contains("service call statusbar 1")) {
                executeCommand("su -c settings put system screen_brightness $currentBrightness")
                shouldShowRestoreBrightness.postValue(true)
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

        fun setDeviceVolume(volumeLevel: Int, applicationContext: Context) {
            val audioManager =
                applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager?
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, volumeLevel, 0)
        }

        fun getCurrentDeviceVolume(applicationContext: Context): Int {
            val audioManager =
                applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager?
            maxAndNeededVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
            return audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
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