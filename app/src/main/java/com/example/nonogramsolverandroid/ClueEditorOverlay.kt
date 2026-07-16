package com.example.nonogramsolverandroid

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.annotation.SuppressLint
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*

/**
 * Floating overlay panel for editing clue numbers.
 *
 * Displays at the bottom ~55% of the screen so the user can see the puzzle
 * clues above (column clues at the top of the grid remain visible).
 * Each row/column gets a single EditText with space-separated numbers.
 * Empty fields (OCR missed them) are highlighted red.
 *
 * The overlay is focusable so the keyboard works on EditTexts,
 * and intercepts the BACK key to dismiss itself.
 *
 * Supports minimize/restore: tapping the minimize button collapses the panel
 * to a small tab at the bottom of the screen. Tapping the tab restores it.
 */
class ClueEditorOverlay(
    private val context: Context,
    private val windowManager: WindowManager
) {
    private var rootView: View? = null
    private var minimizedTabView: View? = null
    private val colEdits = mutableListOf<EditText>()
    private val rowEdits = mutableListOf<EditText>()
    private val clueRowLayouts = mutableListOf<LinearLayout>()
    private var scrollView: ScrollView? = null
    private var bottomSpacer: Space? = null
    /** The EditText that last held focus — used to restore keyboard after peek/minimize. */
    private var lastFocusedEdit: EditText? = null
    /** Whether an EditText currently has focus. */
    private var hasEditFocus = false
    /** Snapshot of whether the keyboard was up at the moment of peek/minimize. */
    private var wasKeyboardUp = false
    /** Combined ordered list of all EditTexts (columns first, then rows). */
    private val allEdits: List<EditText> get() = colEdits + rowEdits

    /** Called with parsed (rowClues, colClues) when the user taps Solve. */
    var onSolve: ((rowClues: List<List<Int>>, colClues: List<List<Int>>) -> Unit)? = null

    /** Called when the user cancels/dismisses the editor. */
    var onDismiss: (() -> Unit)? = null

    fun show(gridSize: Int, colClueStrings: List<String>, rowClueStrings: List<String>) {
        destroy() // remove any existing overlay

        colEdits.clear()
        rowEdits.clear()
        clueRowLayouts.clear()

        // Root view — intercepts BACK key
        val root = BackAwareLayout(context).apply {
            onBackPressed = { minimize() }
        }

        // Rounded top corners + shadow
        root.background = GradientDrawable().apply {
            setColor(Color.parseColor("#F5F5F5"))
            cornerRadii = floatArrayOf(dp(16f), dp(16f), dp(16f), dp(16f), 0f, 0f, 0f, 0f)
        }
        root.elevation = dp(8f)

        // Main vertical layout
        val mainLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // ---- Header bar ----
        mainLayout.addView(createHeader(gridSize))

        // ---- Scrollable clue editors ----
        val sv = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f // take remaining space
            )
            setPadding(dp(12).toInt(), 0, dp(12).toInt(), 0)
        }
        scrollView = sv

        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Column clues section
        contentLayout.addView(createSectionLabel("Column Clues (top → bottom)"))
        for (i in 0 until gridSize) {
            val prefill = colClueStrings.getOrNull(i) ?: ""
            contentLayout.addView(createClueRow("Col ${i + 1}", prefill, colEdits))
        }

        contentLayout.addView(createDivider())

        // Row clues section
        contentLayout.addView(createSectionLabel("Row Clues (left → right)"))
        for (i in 0 until gridSize) {
            val prefill = rowClueStrings.getOrNull(i) ?: ""
            contentLayout.addView(createClueRow("Row ${i + 1}", prefill, rowEdits))
        }

        // Bottom padding — tall enough so the last clue rows can be scrolled
        // above the on-screen numpad (~250dp covers most keyboard heights).
        val spacer = Space(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(250).toInt()
            )
            visibility = View.VISIBLE
        }
        bottomSpacer = spacer
        contentLayout.addView(spacer)

        sv.addView(contentLayout)
        mainLayout.addView(sv)

        // ---- Button bar ----
        mainLayout.addView(createButtonBar())

        root.addView(mainLayout)
        rootView = root

        // Window params: bottom of screen, 55% height, FOCUSABLE for keyboard input
        val displayMetrics = context.resources.displayMetrics
        val panelHeight = (displayMetrics.heightPixels * 0.55).toInt()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            panelHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }

        windowManager.addView(root, params)

        // Wire up Enter-key navigation, green highlighting, and focus tracking
        setupEnterKeyNavigation()
    }

    /**
     * Fully removes the overlay and minimized tab from the window manager.
     * All view state (EditText contents) is lost.
     */
    fun destroy() {
        rootView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) { }
        }
        rootView = null
        removeMinimizedTab()
        colEdits.clear()
        rowEdits.clear()
        clueRowLayouts.clear()
        scrollView = null
        bottomSpacer = null
        lastFocusedEdit = null
        hasEditFocus = false
        wasKeyboardUp = false
    }

    /**
     * Fully hides/dismisses the editor (close button behavior).
     * Removes the minimized tab too.
     */
    fun hide() {
        rootView?.visibility = View.GONE
        removeMinimizedTab()
        onDismiss?.invoke()
    }

    /**
     * Minimizes the editor to a small tab at the bottom of the screen.
     * The main panel is hidden but NOT destroyed — EditText values are preserved.
     */
    fun minimize() {
        wasKeyboardUp = isKeyboardActive()
        hideKeyboard()
        rootView?.visibility = View.GONE
        showMinimizedTab()
    }

    /**
     * Restores the editor from minimized state.
     * EditText values are intact since the views were never destroyed.
     */
    fun restore() {
        removeMinimizedTab()
        rootView?.visibility = View.VISIBLE
        if (wasKeyboardUp) {
            restoreKeyboard()
        }
        wasKeyboardUp = false
    }

    fun unhide() {
        rootView?.visibility = View.VISIBLE
    }

    val isShowing: Boolean get() = rootView?.visibility == View.VISIBLE
    val isHidden: Boolean get() = rootView == null || (rootView?.visibility == View.GONE && minimizedTabView == null)
    val isMinimized: Boolean get() = rootView?.visibility == View.GONE && minimizedTabView != null

    // -----------------------------------------------------------------------
    // Minimized tab
    // -----------------------------------------------------------------------

    private fun showMinimizedTab() {
        if (minimizedTabView != null) return

        val tab = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(20).toInt(), dp(10).toInt(), dp(20).toInt(), dp(10).toInt())

            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1565C0"))
                cornerRadii = floatArrayOf(dp(12f), dp(12f), dp(12f), dp(12f), 0f, 0f, 0f, 0f)
            }
            elevation = dp(8f)

            // Arrow + label
            addView(TextView(context).apply {
                text = "▲  Edit Clues"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })

            setOnClickListener { restore() }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }

        minimizedTabView = tab
        windowManager.addView(tab, params)
    }

    private fun removeMinimizedTab() {
        minimizedTabView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) { }
        }
        minimizedTabView = null
    }

    // -----------------------------------------------------------------------
    // View builders
    // -----------------------------------------------------------------------

    private fun createHeader(gridSize: Int): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1565C0"))
            setPadding(dp(16).toInt(), dp(10).toInt(), dp(12).toInt(), dp(10).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            // Drag-handle visual indicator
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40).toInt(), dp(4).toInt()).apply {
                    gravity = Gravity.CENTER
                    marginEnd = dp(8).toInt()
                }
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#80FFFFFF"))
                    cornerRadius = dp(2f)
                }
            })

            // Title
            addView(TextView(context).apply {
                text = "Edit Clues — ${gridSize}×${gridSize}"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            // Peek button — hold to temporarily hide the editor and see the puzzle
            addView(createPeekButton())

            // Minimize button
            addView(TextView(context).apply {
                text = "─"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                gravity = Gravity.CENTER
                setPadding(dp(8).toInt(), 0, dp(8).toInt(), 0)
                setOnClickListener { minimize() }
            })

            // Close button
            addView(TextView(context).apply {
                text = "✕"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                gravity = Gravity.CENTER
                setPadding(dp(8).toInt(), 0, dp(4).toInt(), 0)
                setOnClickListener { hide() }
            })
        }
    }

    /**
     * Creates a "Peek" button for the header bar.
     * While the user presses and holds this button, the entire editor panel
     * becomes fully transparent so they can see the puzzle clues underneath.
     * Releasing the button instantly restores the editor.
     *
     * Uses window alpha (0f / 1f) instead of View.INVISIBLE so the window
     * remains in the touch-target tree and ACTION_UP is still delivered.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createPeekButton(): TextView {
        return TextView(context).apply {
            text = "\uD83D\uDC41" // 👁
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            setPadding(dp(8).toInt(), 0, dp(8).toInt(), 0)

            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        wasKeyboardUp = isKeyboardActive()
                        hideKeyboard()
                        setOverlayAlpha(0f)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        setOverlayAlpha(1f)
                        if (wasKeyboardUp) {
                            restoreKeyboard()
                        }
                        wasKeyboardUp = false
                        true
                    }
                    else -> false
                }
            }
        }
    }

    /**
     * Sets the overlay window's alpha without removing it from the
     * window manager, so touch events continue to be delivered.
     */
    private fun setOverlayAlpha(alpha: Float) {
        val root = rootView ?: return
        try {
            val lp = root.layoutParams as WindowManager.LayoutParams
            lp.alpha = alpha
            windowManager.updateViewLayout(root, lp)
        } catch (_: Exception) { }
    }

    private fun createSectionLabel(text: String): TextView {
        return TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1565C0"))
            setPadding(0, dp(10).toInt(), 0, dp(4).toInt())
        }
    }

    private fun createClueRow(
        label: String,
        prefill: String,
        editList: MutableList<EditText>
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(3).toInt() }
            // Rounded background so green highlight looks clean
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(4f)
            }
            setPadding(dp(2).toInt(), dp(2).toInt(), dp(2).toInt(), dp(2).toInt())

            // Label
            addView(TextView(context).apply {
                text = "$label:"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#333333"))
                layoutParams = LinearLayout.LayoutParams(dp(60).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
            })

            // EditText
            val edit = EditText(context).apply {
                setText(prefill)
                inputType = InputType.TYPE_CLASS_PHONE
                imeOptions = EditorInfo.IME_ACTION_NEXT
                hint = "e.g. 2 1 3"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(Color.parseColor("#333333"))
                setHintTextColor(Color.parseColor("#999999"))
                setPadding(dp(8).toInt(), dp(4).toInt(), dp(8).toInt(), dp(4).toInt())
                minHeight = dp(34).toInt()
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

                background = GradientDrawable().apply {
                    setColor(if (prefill.isEmpty()) Color.parseColor("#20FF0000") else Color.WHITE)
                    setStroke(1, Color.parseColor("#CCCCCC"))
                    cornerRadius = dp(4f)
                }
            }
            addView(edit)
            editList.add(edit)
        }
        clueRowLayouts.add(row)
        return row
    }

    private fun createDivider(): View {
        return View(context).apply {
            setBackgroundColor(Color.parseColor("#CCCCCC"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1).toInt()
            ).apply {
                topMargin = dp(8).toInt()
                bottomMargin = dp(4).toInt()
            }
        }
    }

    private fun createButtonBar(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16).toInt(), dp(8).toInt(), dp(16).toInt(), dp(12).toInt())
            setBackgroundColor(Color.parseColor("#EEEEEE"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            addView(Button(context).apply {
                text = "Discard"
                setOnClickListener { destroy(); onDismiss?.invoke() }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(8).toInt()
                }
            })

            addView(Button(context).apply {
                text = "Solve"
                setOnClickListener { handleSolve() }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8).toInt()
                }
            })
        }
    }

    // -----------------------------------------------------------------------
    // Solve logic
    // -----------------------------------------------------------------------

    private fun handleSolve() {
        val colClues = colEdits.map { parseClueString(it.text.toString()) }
        val rowClues = rowEdits.map { parseClueString(it.text.toString()) }

        val emptyRows = rowClues.count { it.isEmpty() }
        val emptyCols = colClues.count { it.isEmpty() }

        if (emptyRows > 0 || emptyCols > 0) {
            Toast.makeText(
                context,
                "$emptyRows row(s) and $emptyCols col(s) empty — solver may fail",
                Toast.LENGTH_LONG
            ).show()
        }

        val finalRowClues = rowClues.map { it.ifEmpty { listOf(0) } }
        val finalColClues = colClues.map { it.ifEmpty { listOf(0) } }

        minimize()
        onSolve?.invoke(finalRowClues, finalColClues)
    }

    private fun parseClueString(text: String): List<Int> {
        return text.trim()
            .split(Regex("\\s+"))
            .mapNotNull { it.toIntOrNull() }
            .filter { it > 0 }
    }

    // -----------------------------------------------------------------------
    // Enter-key navigation + green highlighting
    // -----------------------------------------------------------------------

    /**
     * Wires up every EditText so that pressing Enter/Return:
     *  1. Highlights the current row's background green (confirmed).
     *  2. Moves focus to the next EditText in the list.
     *  3. Places the cursor at the END of the next field's text.
     */
    private fun setupEnterKeyNavigation() {
        val all = allEdits
        for (i in all.indices) {
            val edit = all[i]

            // Track focus for keyboard restore after peek/minimize
            edit.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    lastFocusedEdit = edit
                    hasEditFocus = true
                } else {
                    // Post to check if *another* EditText grabbed focus;
                    // if not, no EditText has focus anymore.
                    edit.post {
                        val currentFocus = rootView?.findFocus()
                        if (currentFocus !is EditText) {
                            hasEditFocus = false
                        }
                    }
                }
            }

            // Use OnKeyListener for hardware / phone-pad Return key
            edit.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP) {
                    advanceFromIndex(i)
                    true
                } else {
                    false
                }
            }

            // Also handle IME action (soft keyboard "Next" / "Done")
            edit.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_NEXT ||
                    actionId == EditorInfo.IME_ACTION_DONE
                ) {
                    advanceFromIndex(i)
                    true
                } else {
                    false
                }
            }
        }
    }

    /**
     * Marks the row at [index] green and moves focus to the next EditText.
     */
    private fun advanceFromIndex(index: Int) {
        // Highlight current row green
        if (index in clueRowLayouts.indices) {
            (clueRowLayouts[index].background as? GradientDrawable)
                ?.setColor(Color.parseColor("#C8E6C9"))
        }

        // Move to the next EditText, if any
        val all = allEdits
        val nextIndex = index + 1
        if (nextIndex in all.indices) {
            val nextEdit = all[nextIndex]
            nextEdit.requestFocus()
            nextEdit.setSelection(nextEdit.text.length) // cursor at end

            // Scroll so 1-2 previous clue rows are visible above the focused one.
            // We find the row 2 positions back and scroll so it sits at the top
            // of the ScrollView, which places the focused row a couple lines down.
            scrollView?.let { sv ->
                nextEdit.post {
                    val anchorIndex = (nextIndex - 2).coerceAtLeast(0)
                    val anchorView = clueRowLayouts.getOrNull(anchorIndex) ?: return@post

                    val anchorLocation = IntArray(2)
                    val svLocation = IntArray(2)
                    anchorView.getLocationInWindow(anchorLocation)
                    sv.getLocationInWindow(svLocation)

                    val anchorTopInSv = anchorLocation[1] - svLocation[1] + sv.scrollY
                    val targetScroll = (anchorTopInSv - dp(4).toInt()).coerceAtLeast(0)
                    sv.smoothScrollTo(0, targetScroll)
                }
            }
        } else {
            // Last EditText — dismiss the keyboard and clear focus so the
            // user can immediately tap the Solve button.
            hideKeyboard()
            allEdits.lastOrNull()?.clearFocus()
        }
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private fun dp(value: Int): Float = value * context.resources.displayMetrics.density
    private fun dp(value: Float): Float = value * context.resources.displayMetrics.density

    /** Hides the soft keyboard if it is currently showing. */
    private fun hideKeyboard() {
        val focusedView = rootView?.findFocus() ?: return
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(focusedView.windowToken, 0)
    }

    /**
     * Returns true if the InputMethodManager reports an active input
     * connection, which is a reliable signal that the soft keyboard is showing.
     */
    private fun isKeyboardActive(): Boolean {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        return imm?.isActive(rootView?.findFocus()) == true
    }

    /**
     * Re-shows the soft keyboard for the last-focused EditText.
     * Called when restoring from peek or minimize only if the keyboard
     * was confirmed to be active at the moment of the peek/minimize action.
     */
    private fun restoreKeyboard() {
        val editToRestore = lastFocusedEdit ?: return
        rootView?.postDelayed({
            editToRestore.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(editToRestore, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    /**
     * FrameLayout that intercepts BACK key presses so the user can
     * dismiss the editor overlay without closing the game.
     */
    private class BackAwareLayout(context: Context) : FrameLayout(context) {
        var onBackPressed: (() -> Unit)? = null

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                onBackPressed?.invoke()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }
}
