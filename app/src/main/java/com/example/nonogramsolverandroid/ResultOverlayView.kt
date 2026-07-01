package com.example.nonogramsolverandroid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

class ResultOverlayView(context: Context) : View(context) {

    private var solution: List<List<Int>>? = null
    private var gridBounds: Rect? = null
    private var detectedNumbers: List<Rect>? = null
    private var sourceWidth: Int = 1
    private var sourceHeight: Int = 1

    private val fillPaint = Paint().apply {
        color = Color.argb(150, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val xPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }
    private val debugPaint = Paint().apply {
        color = Color.GREEN
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    fun setResults(clues: PuzzleClues, solution: List<List<Int>>) {
        this.solution = solution
        this.gridBounds = clues.gridBounds
        this.detectedNumbers = clues.debugNumberRects
        this.sourceWidth = clues.sourceBitmapWidth
        this.sourceHeight = clues.sourceBitmapHeight
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val sol = solution ?: return
        val bounds = gridBounds ?: return
        
        // Scale factors to map bitmap pixel coordinates to overlay view coordinates.
        // sourceWidth/Height should match the actual screen resolution since we fixed
        // the screenshot cropping to remove row padding.
        val scaleX = width.toFloat() / sourceWidth
        val scaleY = height.toFloat() / sourceHeight

        // 1. DRAW DEBUG RECTANGLES around detected clue numbers
        canvas.save()
        
        detectedNumbers?.forEach { rect ->
            val scaledRect = Rect(
                (rect.left * scaleX).toInt(),
                (rect.top * scaleY).toInt(),
                (rect.right * scaleX).toInt(),
                (rect.bottom * scaleY).toInt()
            )
            canvas.drawRect(scaledRect, debugPaint)
        }
        canvas.restore()

        // 2. DRAW SOLUTION GRID
        canvas.save()
        
        // Scale grid bounds from bitmap coordinates to overlay coordinates
        val scaledLeft = bounds.left * scaleX
        val scaledTop = bounds.top * scaleY
        val scaledRight = bounds.right * scaleX
        val scaledBottom = bounds.bottom * scaleY

        // Draw debug grid outline
        canvas.drawRect(scaledLeft, scaledTop, scaledRight, scaledBottom, debugPaint)
        
        if (sol.isNotEmpty() && sol[0].isNotEmpty()) {
            val rows = sol.size
            val cols = sol[0].size
            val cellWidth = (scaledRight - scaledLeft) / cols
            val cellHeight = (scaledBottom - scaledTop) / rows

            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val left = scaledLeft + c * cellWidth
                    val top = scaledTop + r * cellHeight
                    val right = left + cellWidth
                    val bottom = top + cellHeight

                    when (sol[r][c]) {
                        1 -> canvas.drawRect(left + 2, top + 2, right - 2, bottom - 2, fillPaint)
                        -1 -> {
                            canvas.drawLine(left + 5, top + 5, right - 5, bottom - 5, xPaint)
                            canvas.drawLine(right - 5, top + 5, left + 5, bottom - 5, xPaint)
                        }
                    }
                }
            }
        }
        canvas.restore()
    }
}
