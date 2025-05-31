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
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import uvnesh.myaod.MainActivity.Companion.executeCommand
import uvnesh.myaod.databinding.FloatingNotificationBinding


class NotificationService : NotificationListenerService() {

    private var windowManager: WindowManager? = null
    private var overlayView: FloatingNotificationBinding? = null

    private val animDuration = 300L
    private val headsUpDismiss = 5500L
    private var currentShowingNotificationId = -1
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
        MainActivity.activeNotifications.postValue(activeNotifications)
        if (resources.getBoolean(R.bool.enable_heads_up) && shouldShowHeadsUp(sbn)) {
            showFloatingPopup(applicationContext, sbn)
            currentShowingNotificationId = sbn?.id ?: -1
        }
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
        if (currentShowingNotificationId == sbn?.id) {
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

    @SuppressLint("ClickableViewAccessibility")
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
                    width - 20.px,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutFlag,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                )
                params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                params.y = 10.px
                if (MainActivity.myaod_active) {
                    params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    params.y =
                        (resources.getDimension(R.dimen.notification_small_margin_bottom) + resources.getDimension(
                            R.dimen.battery_margin_bottom
                        ) + 29.px).toInt()
                    overlayView?.main?.cardElevation = 0f
                }
                overlayView?.root?.alpha = 0f
                windowManager?.addView(overlayView?.root, params)
                setContent(sbn)
                overlayView?.root?.setOnTouchListener { v, event ->
                    removeNotification()
                    executeCommand("su -c cmd statusbar expand-notifications")
                    true
                }
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
            removeViewHandler.postDelayed(removeViewRunnable, animDuration + headsUpDismiss)
        }
    }

    private fun removeNotification() {
        removeViewHandler.removeCallbacks(removeViewRunnable)
        removeViewRunnable.run()
    }

}