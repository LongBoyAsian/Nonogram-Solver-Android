package com.example.nonogramsolverandroid

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class SolverService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var windowManager: WindowManager
    private var fabView: View? = null
    private var overlayView: ResultOverlayView? = null
    private var clueEditorOverlay: ClueEditorOverlay? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var puzzleProcessor: PuzzleProcessor
    private lateinit var jsSolver: JsSolver

    // Stored after a successful solve so the Fill button can access them
    private var lastSolution: List<List<Int>>? = null
    private var lastClues: PuzzleClues? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        puzzleProcessor = PuzzleProcessor()
        jsSolver = JsSolver(this)
        createNotificationChannel()
        
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Start with a neutral type or none if possible, but mediaProjection requires it.
            // Actually, for SDK 34+, you MUST start with the type you intend to use if you want to use it later.
            // But you can't use mediaProjection type UNTIL you have the projection.
            // This is a catch-22 for "start immediately to avoid crash".
            // Let's try starting without a specific type first, then upgrading.
            startForeground(NOTIFICATION_ID, notification)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultData != null && mediaProjection == null) {
            val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, resultData)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    virtualDisplay?.release()
                    virtualDisplay = null
                    mediaProjection = null
                }
            }, null)

            // Start foreground here for SDK 34+
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, notification)
            }

            setupVirtualDisplay()
            showFloatingButton()
        }

        return START_STICKY
    }

    private fun setupVirtualDisplay() {
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
    }

    private var clearButton: Button? = null
    private var fillButton: Button? = null

    private fun showFloatingButton() {
        if (fabView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 50 // Moved higher per user request
        }

        val frameLayout = FrameLayout(this)
        val buttonRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }

        val solveBtn = Button(this).apply {
            text = "Solve"
            setOnClickListener {
                val editor = clueEditorOverlay
                when {
                    // Editor is minimized — restore it (preserves user's clue edits)
                    editor?.isMinimized == true -> editor.restore()
                    // Editor is fully visible — do nothing
                    editor?.isShowing == true -> { /* already open */ }
                    // Editor is null or fully dismissed — fresh scan
                    else -> captureAndSolve()
                }
            }
        }
        buttonRow.addView(solveBtn)

        val fillBtn = Button(this).apply {
            text = "Fill"
            visibility = View.GONE
            setOnClickListener { startAutoFill() }
        }
        buttonRow.addView(fillBtn)
        fillButton = fillBtn

        val clrBtn = Button(this).apply {
            text = "Clear"
            visibility = View.GONE
            setOnClickListener {
                clearOverlay()
            }
        }
        buttonRow.addView(clrBtn)
        clearButton = clrBtn

        frameLayout.addView(buttonRow)
        fabView = frameLayout
        windowManager.addView(fabView, params)
    }

    private fun captureAndSolve() {
        serviceScope.launch {
            // Clear previous puzzle state so old solutions/clues don't bleed into new puzzle
            clueEditorOverlay?.destroy()
            clueEditorOverlay = null
            clearOverlay()

            // Hide floating buttons so they don't interfere with grid detection
            fabView?.visibility = View.INVISIBLE
            delay(300) // Allow time for the screen to redraw without the button

            val bitmap = captureScreenshot()

            // Show toast AFTER capture so it doesn't appear in the screenshot
            Toast.makeText(this@SolverService, "Scanning puzzle...", Toast.LENGTH_SHORT).show()
            
            // Restore floating buttons
            fabView?.visibility = View.VISIBLE

            if (bitmap == null) {
                Toast.makeText(this@SolverService, "Failed to capture screen", Toast.LENGTH_SHORT).show()
                return@launch
            }

            // Show bitmap dimensions for diagnostics
            Toast.makeText(this@SolverService, "Captured: ${bitmap.width}x${bitmap.height}", Toast.LENGTH_SHORT).show()

            val clues = puzzleProcessor.processImage(bitmap)
            if (clues == null) {
                Toast.makeText(this@SolverService, "Could not detect puzzle grid", Toast.LENGTH_SHORT).show()
                return@launch
            }

            // Convert clue lists to space-separated strings for the editor
            val colClueStrings = clues.colClues.map { it.joinToString(" ") }
            val rowClueStrings = clues.rowClues.map { it.joinToString(" ") }

            // Show the clue editor as a floating overlay so the puzzle stays visible
            val editor = ClueEditorOverlay(this@SolverService, windowManager)
            clueEditorOverlay = editor

            editor.onSolve = { rowClues, colClues ->
                val correctedClues = PuzzleClues(
                    size = clues.size,
                    rowClues = rowClues,
                    colClues = colClues,
                    gridBounds = clues.gridBounds,
                    debugNumberRects = emptyList(),
                    sourceBitmapWidth = clues.sourceBitmapWidth,
                    sourceBitmapHeight = clues.sourceBitmapHeight
                )
                serviceScope.launch {
                    Toast.makeText(this@SolverService, "Solving...", Toast.LENGTH_SHORT).show()
                    val solution = jsSolver.solve(correctedClues)
                    if (solution != null) {
                        lastSolution = solution
                        lastClues = correctedClues
                        showOverlay(solution, correctedClues)
                        Toast.makeText(this@SolverService, "Solved!", Toast.LENGTH_SHORT).show()
                    } else {
                        lastSolution = null
                        lastClues = null
                        Toast.makeText(this@SolverService, "Solver failed (Invalid clues?)", Toast.LENGTH_LONG).show()
                        showOverlay(null, correctedClues)
                    }
                }
            }

            editor.show(clues.size, colClueStrings, rowClueStrings)
        }
    }

    private fun showOverlay(solution: List<List<Int>>?, clues: PuzzleClues) {
        if (overlayView == null) {
            overlayView = ResultOverlayView(this)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                // Ensure the overlay covers the entire physical screen
                // so its coordinate space matches the screenshot bitmap
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }
            windowManager.addView(overlayView, params)
        }
        overlayView?.setResults(clues, solution ?: emptyList())
        // Show Clear and Fill buttons when a solution exists
        clearButton?.visibility = View.VISIBLE
        fillButton?.visibility = if (solution != null && solution.isNotEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * Removes the solution overlay from the screen and hides the Clear button.
     */
    private fun clearOverlay() {
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) { }
        }
        overlayView = null
        clearButton?.visibility = View.GONE
        fillButton?.visibility = View.GONE
        lastSolution = null
        lastClues = null
    }

    /**
     * Starts the auto-fill process: hides overlays and dispatches tap gestures
     * for each filled cell via the Accessibility Service.
     */
    private fun startAutoFill() {
        val solution = lastSolution
        val clues = lastClues

        if (solution == null || clues == null) {
            Toast.makeText(this, "No solution to fill", Toast.LENGTH_SHORT).show()
            return
        }

        // Check if the Accessibility Service is enabled
        if (!AutoTapService.isRunning) {
            Toast.makeText(
                this,
                "Enable \"Nonogram Solver\" in Accessibility Settings first",
                Toast.LENGTH_LONG
            ).show()
            // Open Accessibility Settings for the user
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (_: Exception) { }
            return
        }

        // Hide the solution overlay and editor so taps go to the puzzle game
        clearOverlayForFill()
        clueEditorOverlay?.minimize()

        Toast.makeText(this, "Make sure Fill mode is selected, starting in 2s...", Toast.LENGTH_SHORT).show()

        // Short delay to let the user see the puzzle and ensure fill mode
        serviceScope.launch {
            delay(2000)

            AutoTapService.instance?.autoFillSolution(
                solution = solution,
                gridBounds = clues.gridBounds,
                gridSize = clues.size,
                delayMs = 80,
                onComplete = {
                    serviceScope.launch {
                        Toast.makeText(this@SolverService, "Auto-fill complete!", Toast.LENGTH_SHORT).show()
                        // Fully dismiss the clue editor and clear state,
                        // matching the behavior of manual fill completion.
                        // The Solve button in the FAB stays visible for a new scan.
                        clueEditorOverlay?.destroy()
                        clueEditorOverlay = null
                        lastSolution = null
                        lastClues = null
                    }
                }
            )
        }
    }

    /**
     * Removes the solution overlay without clearing the stored solution,
     * so auto-fill can still reference it.
     */
    private fun clearOverlayForFill() {
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) { }
        }
        overlayView = null
        clearButton?.visibility = View.GONE
        fillButton?.visibility = View.GONE
    }

    private suspend fun captureScreenshot(): Bitmap? = withContext(Dispatchers.IO) {
        val reader = imageReader ?: return@withContext null

        // Drain any stale frames from the buffer. The ImageReader holds
        // maxImages=2 and drops newer frames when full, so the "latest"
        // image may actually be from BEFORE the user navigated to the
        // puzzle or before overlays were hidden.
        repeat(3) {
            try {
                reader.acquireLatestImage()?.close()
            } catch (_: Exception) { }
        }

        // Wait for a fresh frame to be rendered by the virtual display
        delay(200)

        val image = reader.acquireLatestImage() ?: return@withContext null
        
        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val paddedBitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            paddedBitmap.copyPixelsFromBuffer(buffer)
            
            // Crop to actual screen dimensions, discarding row padding
            if (rowPadding > 0) {
                val cropped = Bitmap.createBitmap(paddedBitmap, 0, 0, image.width, image.height)
                paddedBitmap.recycle()
                cropped
            } else {
                paddedBitmap
            }
        } finally {
            image.close()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Solver Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nonogram Solver")
            .setContentText("Screen capture is active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        virtualDisplay?.release()
        mediaProjection?.stop()
        clueEditorOverlay?.destroy()
        fabView?.let { windowManager.removeView(it) }
        overlayView?.let { windowManager.removeView(it) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "SolverServiceChannel"
        const val NOTIFICATION_ID = 1
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        const val ACTION_STOP = "ACTION_STOP"

        /** Holds grid bounds and bitmap dimensions from the last scan,
         *  so ClueEditorActivity can reconstruct a PuzzleClues object. */
        data class ScanContext(
            val gridBounds: android.graphics.Rect,
            val sourceBitmapWidth: Int,
            val sourceBitmapHeight: Int
        )

        var pendingScanContext: ScanContext? = null
        var onCluesCorrected: ((PuzzleClues) -> Unit)? = null
    }
}
