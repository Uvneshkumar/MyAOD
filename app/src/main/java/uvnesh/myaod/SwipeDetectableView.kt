package uvnesh.myaod

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import uvnesh.myaod.MainActivity.Companion.executeCommand
import kotlin.math.abs

class SwipeDetectableView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private lateinit var gestureDetector: GestureDetector
    private var onLongPress = {}
    private var onClick = {}

    init {
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
                    // Calculate the distance moved
                    val deltaY = e2.y - (e1?.y ?: 0f)
                    val deltaX = e2.x - (e1?.x ?: 0f)
                    // Check if it's a vertical swipe and meets the minimum velocity and distance
                    if (abs(deltaY) > abs(deltaX) && abs(deltaY) > SWIPE_THRESHOLD_VELOCITY && abs(
                            deltaY
                        ) > MIN_SWIPE_DISTANCE
                    ) {
                        if (deltaY > 0) {
                            // Swipe down
                            onSwipeDown(e2.x)
                        } else {
                            // Swipe up
                            onSwipeUp()
                        }
                        return true
                    } else if (abs(deltaX) > abs(deltaY) && abs(deltaX) > SWIPE_THRESHOLD_VELOCITY && abs(
                            deltaX
                        ) > MIN_SWIPE_DISTANCE
                    ) {
                        if (deltaX > 0) {
                            // Swipe right
                            onSwipeRight()
                        } else {
                            // Swipe left
                            onSwipeLeft()
                        }
                        return true
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
        if (x >= 0.80 * totalWidth) {
            executeCommand("su -c cmd statusbar expand-settings")
        } else {
            executeCommand("su -c cmd statusbar expand-notifications")
        }
    }

    private fun onSwipeLeft() {
        // starts media even if not playing already
        executeCommand("su -c  cmd media_session dispatch previous")
    }

    private fun onSwipeRight() {
        // starts media even if not playing already
        executeCommand("su -c  cmd media_session dispatch next")
    }

    companion object {
        private const val SWIPE_THRESHOLD_VELOCITY = 100
        private const val MIN_SWIPE_DISTANCE = 100
    }
}
