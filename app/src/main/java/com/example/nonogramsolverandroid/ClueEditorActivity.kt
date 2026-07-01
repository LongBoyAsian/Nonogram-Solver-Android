package com.example.nonogramsolverandroid

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Displays a clue editor pre-filled with OCR-detected numbers.
 *
 * Each grid row and column gets a single EditText showing space-separated
 * clue numbers (e.g. "2 1 3"). Fields that OCR missed are shown empty
 * with a light red tint so the user knows they need to fill them in.
 *
 * When the user taps "Solve", the corrected clues are sent back to
 * [SolverService] via a static callback, and this activity finishes.
 */
class ClueEditorActivity : AppCompatActivity() {

    private val colClueEdits = mutableListOf<EditText>()
    private val rowClueEdits = mutableListOf<EditText>()
    private var gridSize = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clue_editor)

        gridSize = intent.getIntExtra(EXTRA_GRID_SIZE, 0)
        if (gridSize == 0) {
            Toast.makeText(this, "Invalid grid size", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val colClueStrings = intent.getStringArrayListExtra(EXTRA_COL_CLUES) ?: arrayListOf()
        val rowClueStrings = intent.getStringArrayListExtra(EXTRA_ROW_CLUES) ?: arrayListOf()

        findViewById<TextView>(R.id.tvGridSize).text = "Grid Size: ${gridSize}×${gridSize}"

        val colContainer = findViewById<LinearLayout>(R.id.llColClues)
        val rowContainer = findViewById<LinearLayout>(R.id.llRowClues)

        buildClueEditors("Col", gridSize, colClueStrings, colContainer, colClueEdits)
        buildClueEditors("Row", gridSize, rowClueStrings, rowContainer, rowClueEdits)

        findViewById<Button>(R.id.btnSolve).setOnClickListener {
            submitClues()
        }
    }

    /**
     * Programmatically builds [count] labeled EditText rows inside [container].
     * Each row has a fixed-width label ("Col 1:", "Row 3:", etc.) and an
     * EditText pre-filled with the OCR-detected clue string.
     */
    private fun buildClueEditors(
        prefix: String,
        count: Int,
        prefillStrings: List<String>,
        container: LinearLayout,
        editList: MutableList<EditText>
    ) {
        for (i in 0 until count) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dpToPx(4)
                }
            }

            val label = TextView(this).apply {
                text = "$prefix ${i + 1}:"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(70),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val prefill = prefillStrings.getOrNull(i) ?: ""
            val edit = EditText(this).apply {
                setText(prefill)
                // TYPE_CLASS_TEXT so the keyboard shows the space bar
                // (the user types space-separated numbers like "2 1 3")
                inputType = InputType.TYPE_CLASS_TEXT
                hint = "e.g. 2 1 3"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            // Highlight empty fields so the user knows OCR missed them
            if (prefill.isEmpty()) {
                edit.setBackgroundColor(0x20FF0000) // Light red tint
            }

            row.addView(label)
            row.addView(edit)
            container.addView(row)
            editList.add(edit)
        }
    }

    /**
     * Parses all EditText fields, validates, and sends corrected clues
     * back to [SolverService] via the static callback.
     */
    private fun submitClues() {
        val colClues = colClueEdits.map { parseClueString(it.text.toString()) }
        val rowClues = rowClueEdits.map { parseClueString(it.text.toString()) }

        val emptyRows = rowClues.count { it.isEmpty() }
        val emptyCols = colClues.count { it.isEmpty() }

        val scanContext = SolverService.pendingScanContext
        if (scanContext == null) {
            Toast.makeText(this, "Scan context lost — please re-scan", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val correctedClues = PuzzleClues(
            size = gridSize,
            // Empty fields → [0] (meaning "all cells in this row/col are empty")
            rowClues = rowClues.map { it.ifEmpty { listOf(0) } },
            colClues = colClues.map { it.ifEmpty { listOf(0) } },
            gridBounds = scanContext.gridBounds,
            debugNumberRects = emptyList(),
            sourceBitmapWidth = scanContext.sourceBitmapWidth,
            sourceBitmapHeight = scanContext.sourceBitmapHeight
        )

        if (emptyRows > 0 || emptyCols > 0) {
            AlertDialog.Builder(this)
                .setTitle("Empty Clue Fields")
                .setMessage(
                    "$emptyRows row(s) and $emptyCols column(s) have no clues.\n" +
                    "The solver may not find a solution."
                )
                .setPositiveButton("Solve Anyway") { _, _ -> solveAndFinish(correctedClues) }
                .setNegativeButton("Go Back", null)
                .show()
        } else {
            solveAndFinish(correctedClues)
        }
    }

    private fun solveAndFinish(clues: PuzzleClues) {
        SolverService.onCluesCorrected?.invoke(clues)
        finish()
    }

    /**
     * Parses a space-separated string like "2 1 3" into [2, 1, 3].
     * Ignores non-numeric tokens and zeros (no valid nonogram clue is 0).
     */
    private fun parseClueString(text: String): List<Int> {
        return text.trim()
            .split(Regex("\\s+"))
            .mapNotNull { it.toIntOrNull() }
            .filter { it > 0 }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density + 0.5f).toInt()
    }

    companion object {
        const val EXTRA_GRID_SIZE = "extra_grid_size"
        const val EXTRA_COL_CLUES = "extra_col_clues"
        const val EXTRA_ROW_CLUES = "extra_row_clues"
    }
}
