package com.example.nonogramsolverandroid

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Lightweight Accessibility Service that dispatches tap gestures to auto-fill
 * solved nonogram cells on the puzzle game's screen.
 *
 * The service itself does nothing until [autoFillSolution] is called by
 * [SolverService]. It exposes a static [instance] reference so the solver
 * can check availability and invoke it directly.
 *
 * The user must enable this service manually in:
 *   Settings → Accessibility → Nonogram Solver Android
 */
class AutoTapService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoTapService"

        /** Live reference to the running service instance, or null if disabled. */
        var instance: AutoTapService? = null
            private set

        /** Quick check for whether the service is connected and ready. */
        val isRunning: Boolean get() = instance != null
    }

    private var isFilling = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "AutoTapService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used — we only need gesture dispatch capability
    }

    override fun onInterrupt() {
        // Required override — nothing to clean up
    }

    override fun onDestroy() {
        instance = null
        isFilling = false
        super.onDestroy()
    }

    /**
     * Dispatches tap gestures for every filled cell in the solution grid.
     *
     * @param solution  2D grid where 1 = filled, 0 = empty
     * @param gridBounds Screen-coordinate bounds of the puzzle grid
     * @param gridSize  Number of rows/columns (e.g. 15 for a 15×15 puzzle)
     * @param delayMs   Milliseconds to wait between consecutive taps
     * @param onProgress Called after each tap with (completed, total) counts
     * @param onComplete Called when all taps are finished
     */
    fun autoFillSolution(
        solution: List<List<Int>>,
        gridBounds: Rect,
        gridSize: Int,
        delayMs: Long = 80,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null,
        onComplete: (() -> Unit)? = null
    ) {
        if (isFilling) {
            Log.w(TAG, "Auto-fill already in progress, ignoring")
            return
        }
        isFilling = true

        val cellWidth = gridBounds.width().toDouble() / gridSize
        val cellHeight = gridBounds.height().toDouble() / gridSize

        // Collect screen coordinates of every cell that needs to be tapped
        val tapPoints = mutableListOf<Pair<Float, Float>>()
        for (row in 0 until gridSize) {
            for (col in 0 until gridSize) {
                if (row < solution.size && col < solution[row].size && solution[row][col] == 1) {
                    val x = (gridBounds.left + col * cellWidth + cellWidth / 2).toFloat()
                    val y = (gridBounds.top + row * cellHeight + cellHeight / 2).toFloat()
                    tapPoints.add(x to y)
                }
            }
        }

        Log.d(TAG, "Auto-filling ${tapPoints.size} cells (grid ${gridSize}×$gridSize)")

        if (tapPoints.isEmpty()) {
            isFilling = false
            onComplete?.invoke()
            return
        }

        val handler = Handler(Looper.getMainLooper())

        fun tapNext(index: Int) {
            if (index >= tapPoints.size || !isFilling) {
                isFilling = false
                Log.d(TAG, "Auto-fill complete (${tapPoints.size} taps)")
                onComplete?.invoke()
                return
            }

            val (x, y) = tapPoints[index]
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()

            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onProgress?.invoke(index + 1, tapPoints.size)
                    handler.postDelayed({ tapNext(index + 1) }, delayMs)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "Gesture cancelled at tap ${index + 1}/${tapPoints.size}")
                    // Keep going — a single cancelled gesture shouldn't abort the fill
                    onProgress?.invoke(index + 1, tapPoints.size)
                    handler.postDelayed({ tapNext(index + 1) }, delayMs)
                }
            }, null)
        }

        tapNext(0)
    }

    /** Stops an in-progress auto-fill. */
    fun cancelFill() {
        isFilling = false
    }
}
