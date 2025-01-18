package uvnesh.myaod

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.constraintlayout.widget.ConstraintLayout
import uvnesh.myaod.MainActivity.Companion.executeCommand
import kotlin.math.abs

class SwipeDetectableView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private lateinit var gestureDetector: GestureDetector

    init {
        setupGestureDetection()
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
                            onSwipeDown()
                        } else {
                            // Swipe up
                            onSwipeUp()
                        }
                        return true
                    }
                    return false
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

    private fun onSwipeDown() {
        // Handle swipe down action
        executeCommand("su -c service call statusbar 1")
    }

    companion object {
        private const val SWIPE_THRESHOLD_VELOCITY = 100
        private const val MIN_SWIPE_DISTANCE = 100
    }
}
