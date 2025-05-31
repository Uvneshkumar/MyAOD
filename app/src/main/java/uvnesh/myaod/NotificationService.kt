package uvnesh.myaod

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.res.Resources.getSystem
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import uvnesh.myaod.MainActivity.Companion.executeCommand
import uvnesh.myaod.databinding.FloatingNotificationBinding
import kotlin.math.abs

class NotificationService : NotificationListenerService() {

    private var mVibrationManager: VibratorManager? = null
    private var windowManager: WindowManager? = null
    private var overlayView: FloatingNotificationBinding? = null

    private val notificationIconAddDelay = 400L
    private val animDuration = 300L
    private val headsUpDismiss = 5500L
    private var currentShowingNotification: StatusBarNotification? = null
    private val width = getSystem().displayMetrics.widthPixels
    private var isAnimRunning = false
    private var dismissWithoutAnim = false
    private var allowedHeadsUpPackages = listOf(
        "com.google.android.gm",
        "com.atlassian.android.jira.core",
        "com.google.android.apps.photos",
        "com.google.android.apps.docs",
        "com.google.android.apps.docs.editors.docs",
        "com.google.android.apps.docs.editors.sheet"
    )

    private val removeViewHandler = Handler(Looper.getMainLooper())
    private val removeViewRunnable = Runnable {
        try {
            if (overlayView != null && !isAnimRunning) {
                isAnimRunning = true
                if (dismissWithoutAnim) {
                    dismissWithoutAnim = false
                    endBlock()
                    return@Runnable
                }
                val anim = ValueAnimator.ofInt(overlayView?.innerLayout?.measuredHeight ?: 0, 0)
                anim.addUpdateListener { valueAnimator ->
                    val dHeight = valueAnimator.animatedValue as Int
                    val layoutParams = overlayView?.innerLayout?.layoutParams
                    layoutParams?.height = dHeight
                    overlayView?.innerLayout?.setLayoutParams(layoutParams)
                }
                anim.duration = animDuration
                anim.start()
                val animator = ObjectAnimator.ofFloat(overlayView?.root, "alpha", 1f, 0f)
                animator.duration = animDuration
                animator.addListener(object : Animator.AnimatorListener {
                    override fun onAnimationCancel(animation: Animator) {}
                    override fun onAnimationRepeat(animation: Animator) {}
                    override fun onAnimationStart(animation: Animator) {}
                    override fun onAnimationEnd(animation: Animator) {
                        overlayView?.root?.post {
                            endBlock()
                        }
                    }
                })
                animator.start()
            }
        } catch (ignored: Exception) {
        }
    }

    private fun endBlock() {
        windowManager?.removeView(overlayView?.root)
        overlayView = null
        isAnimRunning = false
        dismissalBlocked = false
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        MainActivity.activeNotifications.postValue(activeNotifications)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        val notificationPackageName = sbn?.packageName.orEmpty()
        if ((sbn?.notification?.channelId.orEmpty()
                .contains(packageName) && notificationPackageName == "android")
        ) {
            return
        }
        if (resources.getBoolean(R.bool.enable_heads_up) && shouldShowHeadsUp(sbn)) {
            showFloatingPopup(applicationContext, sbn)
            currentShowingNotification = sbn
        }
        Handler(Looper.getMainLooper()).postDelayed({
            MainActivity.activeNotifications.postValue(activeNotifications)
        }, notificationIconAddDelay)
    }

    private fun shouldShowHeadsUp(sbn: StatusBarNotification?): Boolean {
        val notificationPackageName = sbn?.packageName.orEmpty()
        @Suppress("DEPRECATION") val isHighPriority =
            (sbn?.notification?.priority ?: Notification.PRIORITY_DEFAULT) >=
                    Notification.PRIORITY_HIGH
        return when {
            isHighPriority -> {
                true
            }

            notificationPackageName in allowedHeadsUpPackages -> {
                true
            }

            else -> false
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        MainActivity.activeNotifications.postValue(activeNotifications)
        if (currentShowingNotification?.id == sbn?.id) {
            removeNotification()
        }
    }

    private fun setContent(sbn: StatusBarNotification?) {
        val title = sbn?.notification?.extras?.getString(Notification.EXTRA_TITLE)
        val text =
            sbn?.notification?.extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty()
                .joinToString("\n").trim().ifEmpty {
                    (sbn?.notification?.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
                        ?: "").toString()
                        .trim()
                        .ifEmpty {
                            sbn?.notification?.extras?.getString(Notification.EXTRA_TEXT)
                        }
                }
        overlayView?.notificationTitle?.text = title
        overlayView?.notificationText?.text = text
        overlayView?.notificationSubText?.text =
            sbn?.notification?.extras?.getString(Notification.EXTRA_SUB_TEXT)
        val iconDrawable = MainActivity.appListItems.find { it.first == sbn?.packageName }?.second
        overlayView?.icon?.setImageDrawable(iconDrawable)
    }

    private val headsUpSnapBackDuration = 100L
    private val swipeStartThreshold = 6
    private var dX: Float = 0F
    private var dY: Float = 0F
    private var isXScroll = true
    private var isScrollSet = false
    private var tapStartTime: Long = 0
    private var dismissConfirmed = false
    private var dismissalBlocked = false

    private fun openQs() {
        removeNotification()
        executeCommand("su -c cmd statusbar expand-notifications")
    }

    private fun swipeAndDismiss() {
        overlayView?.root?.animate()?.apply {
            if ((overlayView?.root?.x
                    ?: 0f) < 0
            ) x(-width.toFloat()) else x(width.toFloat())
            duration = headsUpSnapBackDuration
            start()
        }
        cancelNotification(currentShowingNotification?.key)
    }

    private fun vibrate() {
        if (mVibrationManager == null) {
            mVibrationManager =
                applicationContext.getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        }
        mVibrationManager?.defaultVibrator?.vibrate(
            VibrationEffect.createPredefined(
                VibrationEffect.EFFECT_CLICK
            )
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setUpTouch() {
        overlayView?.root?.setOnTouchListener { p0, motionEvent ->
            when (motionEvent?.action) {
                MotionEvent.ACTION_DOWN -> {
                    tapStartTime = System.currentTimeMillis()
                    dismissalBlocked = true
                    removeViewHandler.removeCallbacks(removeViewRunnable)
                    dX = (overlayView?.root?.x ?: 0f) - motionEvent.rawX
                    dY = (overlayView?.root?.y ?: 0f) - motionEvent.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val xScroll = motionEvent.rawX + dX
                    val yScroll = motionEvent.rawY + dY
                    if (abs(xScroll) < swipeStartThreshold && abs(yScroll) < swipeStartThreshold && !isScrollSet) {
                        return@setOnTouchListener true
                    }
                    if (!isScrollSet) {
                        isXScroll = abs(xScroll) > abs(yScroll)
                        isScrollSet = true
                    }
                    overlayView?.root?.animate()?.apply {
                        if (isXScroll) x(xScroll) else y(yScroll)
                        duration = 0
                        start()
                    }
                    if (isXScroll) {
                        if (abs(overlayView?.root?.x ?: 0f)
                            > ((width - (2 * resources.getDimension(R.dimen.notif_margin))) * 0.5)
                        ) {
                            if (!dismissConfirmed) {
                                dismissConfirmed = true
                                vibrate()
                            }
                        } else {
                            if (dismissConfirmed) {
                                dismissConfirmed = false
                                vibrate()
                            }
                        }
                    } else {
                        if ((overlayView?.root?.y ?: 0f) >= swipeStartThreshold) {
                            openQs()
                        } else {
                            removeNotification()
                        }
                        isScrollSet = false
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    dismissalBlocked = false
                    scheduleNotificationRemoval()
                    isScrollSet = false
                    if (overlayView?.root?.x == 0f && overlayView?.root?.y == 0f && System.currentTimeMillis() - tapStartTime < ViewConfiguration.getLongPressTimeout()) {
                        openQs()
                        return@setOnTouchListener true
                    }
                    if (dismissConfirmed) {
                        swipeAndDismiss()
                        dismissConfirmed = false
                    } else {
                        if (System.currentTimeMillis() - tapStartTime < ViewConfiguration.getLongPressTimeout()
                            && abs(overlayView?.root?.x ?: 0f) >= swipeStartThreshold && isXScroll
                        ) {
                            swipeAndDismiss()
                        } else {
                            overlayView?.root?.animate()?.apply {
                                x(0f)
                                y(0f)
                                duration = headsUpSnapBackDuration
                                start()
                            }
                        }
                    }
                    true
                }

                else -> {
                    false
                }
            }
        }
    }

    private fun showFloatingPopup(context: Context, sbn: StatusBarNotification?) {
        Handler(Looper.getMainLooper()).post {
            if (windowManager == null) {
                windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            }
            removeViewHandler.removeCallbacks(removeViewRunnable)
            if (overlayView == null) {
                val inflater = LayoutInflater.from(context)
                overlayView = FloatingNotificationBinding.inflate(inflater)
                val layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                val params = WindowManager.LayoutParams(
                    width,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutFlag,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                )
                params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                if (MainActivity.myaod_active) {
                    params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    params.y =
                        (resources.getDimension(R.dimen.notification_small_margin_bottom) + resources.getDimension(
                            R.dimen.battery_margin_bottom
                        ) + 10.px).toInt()
                    overlayView?.card?.cardElevation = 0f
                }
                overlayView?.root?.alpha = 0f
                windowManager?.addView(overlayView?.root, params)
                setContent(sbn)
                setUpTouch()
                overlayView?.root?.post {
                    val animator = ObjectAnimator.ofFloat(overlayView?.root, "alpha", 0f, 1f)
                    animator.duration = animDuration
                    animator.start()
                    val anim = ValueAnimator.ofInt(0, overlayView?.innerLayout?.measuredHeight ?: 0)
                    anim.addUpdateListener { valueAnimator ->
                        val dHeight = valueAnimator.animatedValue as Int
                        val layoutParams = overlayView?.innerLayout?.layoutParams
                        layoutParams?.height = dHeight
                        overlayView?.innerLayout?.setLayoutParams(layoutParams)
                    }
                    anim.duration = animDuration
                    anim.start()
                }
            } else {
                val layoutParams = overlayView?.innerLayout?.layoutParams
                layoutParams?.height = ViewGroup.LayoutParams.WRAP_CONTENT
                overlayView?.innerLayout?.setLayoutParams(layoutParams)
                setContent(sbn)
            }
            scheduleNotificationRemoval()
        }
    }

    private fun scheduleNotificationRemoval() {
        if (!dismissalBlocked) {
            removeViewHandler.postDelayed(removeViewRunnable, animDuration + headsUpDismiss)
        }
    }

    private fun removeNotification() {
        removeViewHandler.removeCallbacks(removeViewRunnable)
        removeViewRunnable.run()
    }

}