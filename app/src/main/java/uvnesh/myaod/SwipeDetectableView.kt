package uvnesh.myaod

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity.VIBRATOR_MANAGER_SERVICE
import uvnesh.myaod.MainActivity.Companion.executeCommand
import kotlin.math.abs

class SwipeDetectableView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private lateinit var gestureDetector: GestureDetector
    private var mVibrationManager: VibratorManager? = null
    private var onLongPress = {}
    private var onClick = {}

    init {
        mVibrationManager = context.getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        setupGestureDetection()
    }

    fun setOnLongPressCallback(longPressCallback: () -> Unit) {
        onLongPress = longPressCallback
    }

    fun setOnClickCallback(onClickCallback: () -> Unit) {
        onClick = onClickCallback
    }

    private fun setupGestureDetection() {
        gestureDetector =
            GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean {
                    return true
                }

                override fun onFling(
                    e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
                ): Boolean {
                    val deltaY = e2.y - (e1?.y ?: 0f)
                    val deltaX = e2.x - (e1?.x ?: 0f)
                    if (abs(deltaY) > abs(deltaX) && abs(deltaY) > SWIPE_THRESHOLD_VELOCITY && abs(
                            deltaY
                        ) > MIN_SWIPE_DISTANCE
                    ) {
                        if (deltaY > 0) {
                            onSwipeDown(e1?.x ?: 0f)
                        } else {
                            onSwipeUp()
                        }
                        return true
                    } else if (abs(deltaX) > abs(deltaY) && abs(deltaX) > SWIPE_THRESHOLD_VELOCITY && abs(
                            deltaX
                        ) > MIN_SWIPE_DISTANCE
                    ) {
                        if (MainActivity.activeNotifications.value.orEmpty()
                                .any { it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) }
                        ) {
                            mVibrationManager?.defaultVibrator?.vibrate(
                                VibrationEffect.createPredefined(
                                    VibrationEffect.EFFECT_CLICK
                                )
                            )
                            if (deltaX > 0) {
                                onSwipeRight()
                            } else {
                                onSwipeLeft()
                            }
                            return true
                        }
                    }
                    return false
                }

                override fun onLongPress(e: MotionEvent) {
                    super.onLongPress(e)
                    onLongPress()
                }

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    onClick()
                    return super.onSingleTapUp(e)
                }
            })
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    private fun onSwipeUp() {
        // Handle swipe up action
    }

    private fun onSwipeDown(x: Float) {
        val totalWidth = measuredWidth
        if (x >= 0.65 * totalWidth) {
            executeCommand("su -c cmd statusbar expand-settings")
        } else {
            executeCommand("su -c cmd statusbar expand-notifications")
        }
    }

    private fun onSwipeLeft() {
        executeCommand("su -c  cmd media_session dispatch previous")
    }

    private fun onSwipeRight() {
        executeCommand("su -c  cmd media_session dispatch next")
    }

    companion object {
        private const val SWIPE_THRESHOLD_VELOCITY = 100
        private const val MIN_SWIPE_DISTANCE = 100
    }
}
