package com.example.nonogramsolverandroid

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

data class PuzzleClues(
    val size: Int,
    val rowClues: List<List<Int>>,
    val colClues: List<List<Int>>,
    val gridBounds: Rect,
    val debugNumberRects: List<Rect>,
    val sourceBitmapWidth: Int,
    val sourceBitmapHeight: Int,
    val hiddenRowCount: Int = 0,
    val hiddenColCount: Int = 0
)

class PuzzleProcessor {

    companion object {
        private const val TAG = "PuzzleProcessor"
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun processImage(bitmap: Bitmap): PuzzleClues? {
        // ==========================================
        // STEP 1: Detect grid (MUST succeed — only hard failure)
        // ==========================================
        Log.d(TAG, "Processing image: ${bitmap.width}x${bitmap.height}")
        val gridResult = detectGridByBlackLines(bitmap)
        if (gridResult == null) {
            Log.w(TAG, "Could not detect grid by black lines")
            return null
        }

        Log.d(TAG, "Grid detected: bounds=${gridResult.bounds}, size=${gridResult.size}, bitmap=${bitmap.width}x${bitmap.height}")

        val gridBounds = gridResult.bounds
        val gridSize = gridResult.size
        val cellWidth = gridBounds.width() / gridSize
        val cellHeight = gridBounds.height() / gridSize

        // ==========================================
        // STEP 2: OCR — best effort (empty is OK for editor)
        // ==========================================
        val image = InputImage.fromBitmap(bitmap, 0)
        val visionText = try {
            recognizer.process(image).await()
        } catch (e: Exception) {
            Log.e(TAG, "ML Kit text recognition failed", e)
            null
        }

        // ==========================================
        // STEP 3: Extract raw text elements with line-level fallback
        // ==========================================
        data class RawElement(val text: String, val rect: Rect)
        val rawElements = buildList {
            visionText?.textBlocks?.forEach { block ->
                block.lines.forEach { line ->
                    val lineBox = line.boundingBox ?: Rect()

                    // Primary: individual ML Kit elements
                    val elementRects = mutableListOf<Rect>()
                    line.elements.forEach { element ->
                        val cleanedText = cleanOcrText(element.text.trim())
                        val elemRect = element.boundingBox ?: Rect()
                        add(RawElement(cleanedText, elemRect))
                        elementRects.add(elemRect)
                    }

                    // Fallback: if line text has more words than elements, ML Kit missed some
                    val lineWords = line.text.trim()
                        .split(Regex("\\s+"))
                        .filter { it.isNotEmpty() }
                    if (lineWords.size > line.elements.size) {
                        Log.d(TAG, "Line fallback: '${line.text}' → ${lineWords.size} words vs ${line.elements.size} elements")
                        val subW = if (lineWords.isNotEmpty()) lineBox.width() / lineWords.size else 0
                        lineWords.forEachIndexed { i, word ->
                            val subLeft = lineBox.left + i * subW
                            val subRect = Rect(subLeft, lineBox.top, subLeft + subW, lineBox.bottom)
                            // Only add fallback element if it doesn't overlap with
                            // an already-detected element (prevents duplicates)
                            val overlapsExisting = elementRects.any { existing ->
                                Rect.intersects(existing, subRect)
                            }
                            if (!overlapsExisting) {
                                add(RawElement(cleanOcrText(word), subRect))
                            }
                        }
                    }
                }
            }
        }

        Log.d(TAG, "Raw text elements: ${rawElements.size}")

        // ==========================================
        // STEP 4: Parse numbers, splitting merged digits
        // ==========================================
        val numbers = rawElements.flatMap { raw ->
            splitMergedDigits(raw.text, raw.rect, gridSize)
        }.filter { it.value > 0 }

        Log.d(TAG, "Parsed ${numbers.size} numbers (zeros excluded)")

        // ==========================================
        // STEP 5: Filter into clue regions (mutually exclusive)
        // ==========================================
        // Small tolerance for OCR bounding boxes that slightly overshoot grid edges
        val topMargin  = (cellHeight * 0.5).toInt()
        val sideMargin = (cellWidth  * 0.5).toInt()
        // Low cutoff to avoid clipping tall clue stacks on large grids
        val minTopY    = (bitmap.height * 0.05).toInt()

        // Column clues: ABOVE the grid, horizontally within the grid span.
        // centerX must be within gridBounds.left..right to exclude row clue
        // numbers that sit to the LEFT of the grid in the corner area.
        val topClueNumbers = numbers.filter {
            it.rect.bottom <= gridBounds.top + topMargin &&
            it.rect.top    >= minTopY &&
            it.rect.centerX() >= gridBounds.left &&
            it.rect.centerX() <= gridBounds.right
        }

        // Row clues: LEFT of the grid, vertically within the grid span.
        // centerY must be within gridBounds.top..bottom to exclude column clue
        // numbers that sit ABOVE the grid in the corner area.
        val leftClueNumbers = numbers.filter {
            it.rect.right  <= gridBounds.left + sideMargin &&
            it.rect.centerY() >= gridBounds.top &&
            it.rect.centerY() <= gridBounds.bottom
        }

        Log.d(TAG, "Filtered: ${topClueNumbers.size} top clues, ${leftClueNumbers.size} left clues")

        // ==========================================
        // STEP 6: Cluster and assign to specific grid positions
        // ==========================================
        // Precompute cell centers for nearest-center assignment
        // (more robust than proportional mapping, especially at grid edges)
        val colCenters = List(gridSize) { i ->
            gridBounds.left + (i * gridBounds.width().toDouble() / gridSize + gridBounds.width().toDouble() / (2 * gridSize)).toInt()
        }
        val rowCenters = List(gridSize) { i ->
            gridBounds.top + (i * gridBounds.height().toDouble() / gridSize + gridBounds.height().toDouble() / (2 * gridSize)).toInt()
        }

        // Column clues: cluster by X, map each group to its nearest grid column
        // Use 0.75x cell width as gap threshold — prevents splitting on small-cell grids
        val colGroups = clusterByGap(topClueNumbers, (cellWidth * 0.75).toInt()) { it.rect.centerX() }
        val colClueMap = mutableMapOf<Int, MutableList<NumWithRect>>()
        for (group in colGroups) {
            val avgX = group.map { it.rect.centerX() }.average().toInt()
            // Nearest-cell-center: find the column whose center is closest
            val colIdx = colCenters.indices.minByOrNull { kotlin.math.abs(colCenters[it] - avgX) } ?: 0
            // Merge if multiple clusters map to the same column
            colClueMap.getOrPut(colIdx) { mutableListOf() }.addAll(group)
        }
        val fullColClues = List(gridSize) { idx ->
            colClueMap[idx]?.sortedBy { it.rect.top }?.map { it.value } ?: emptyList()
        }

        // Row clues: cluster by Y, map each group to its nearest grid row
        val rowGroups = clusterByGap(leftClueNumbers, (cellHeight * 0.75).toInt()) { it.rect.centerY() }
        val rowClueMap = mutableMapOf<Int, MutableList<NumWithRect>>()
        for (group in rowGroups) {
            val avgY = group.map { it.rect.centerY() }.average().toInt()
            val rowIdx = rowCenters.indices.minByOrNull { kotlin.math.abs(rowCenters[it] - avgY) } ?: 0
            // Merge if multiple clusters map to the same row
            rowClueMap.getOrPut(rowIdx) { mutableListOf() }.addAll(group)
        }
        val fullRowClues = List(gridSize) { idx ->
            rowClueMap[idx]?.sortedBy { it.rect.left }?.map { it.value } ?: emptyList()
        }

        Log.d(TAG, "Assigned: ${colClueMap.size}/$gridSize cols, ${rowClueMap.size}/$gridSize rows")

        val hiddenRows = fullRowClues.count { it.isEmpty() }
        val hiddenCols = fullColClues.count { it.isEmpty() }
        if (hiddenRows > 0 || hiddenCols > 0) {
            Log.d(TAG, "Undetected clues: $hiddenRows rows, $hiddenCols cols")
        }

        // Collect debug rects
        val allDetectedRects = (topClueNumbers + leftClueNumbers).map { it.rect }

        return PuzzleClues(
            size = gridSize,
            rowClues = fullRowClues,
            colClues = fullColClues,
            gridBounds = gridBounds,
            debugNumberRects = allDetectedRects,
            sourceBitmapWidth = bitmap.width,
            sourceBitmapHeight = bitmap.height,
            hiddenRowCount = hiddenRows,
            hiddenColCount = hiddenCols
        )
    }

    // ===== Grid Detection by Black Line Scanning =====

    private data class GridDetectionResult(val bounds: Rect, val size: Int)

    /**
     * Detects the puzzle grid by scanning for thick black lines in the image.
     *
     * The game's grid has:
     * - Thick black outer border
     * - Thick black internal dividers every 5 cells
     * - Light gray dividers between individual cells (ignored — too light)
     *
     * Algorithm:
     * 1. For each pixel column, find the longest contiguous run of "black" pixels
     * 2. Columns with a long enough run are vertical grid lines
     * 3. Same for horizontal (rows of pixels)
     * 4. Grid bounds = outermost detected lines
     * 5. Grid size = (number of black line sections) × 5
     *    - 2 lines = 1 section = 5×5
     *    - 3 lines = 2 sections = 10×10
     *    - 4 lines = 3 sections = 15×15
     */
    private fun detectGridByBlackLines(bitmap: Bitmap): GridDetectionResult? {
        val imgW = bitmap.width
        val imgH = bitmap.height

        // Read all pixels into an array for fast access (avoids slow per-pixel JNI calls)
        val pixels = IntArray(imgW * imgH)
        bitmap.getPixels(pixels, 0, imgW, 0, 0, imgW, imgH)

        // A grid line must have a contiguous dark run at least this long to be detected.
        // This filters out short dark elements like text characters, icons, etc.
        val minLineLength = (minOf(imgW, imgH) * 0.08).toInt()

        // Scan region: exclude status bar (top 5%) and bottom ads/nav (bottom 15%)
        val scanYStart = (imgH * 0.05).toInt()
        val scanYEnd = (imgH * 0.85).toInt()
        val scanXStart = (imgW * 0.01).toInt()
        val scanXEnd = (imgW * 0.99).toInt()

        // ---- PASS 1: Strict threshold (luminance < 80) ----
        val vertLines = scanForLines(
            pixels, imgW, scanXStart, scanXEnd, scanYStart, scanYEnd,
            vertical = true, minLineLength = minLineLength, luminanceThreshold = 80
        )
        val horizLines = scanForLines(
            pixels, imgW, scanXStart, scanXEnd, scanYStart, scanYEnd,
            vertical = false, minLineLength = minLineLength, luminanceThreshold = 80
        )

        Log.d(TAG, "Pass 1 (strict): ${vertLines.size} vert, ${horizLines.size} horiz")

        if (vertLines.size < 2 || horizLines.size < 2) {
            Log.w(TAG, "Not enough black lines for grid detection")
            return null
        }

        // Try to find a valid grid from the strict pass
        val result1 = findBestSquareGrid(vertLines, horizLines)

        // Always proceed to Pass 2 — internal dividers may be dark gray
        // and only visible with a relaxed threshold, yielding a larger grid.

        // ---- PASS 2: Relaxed threshold (luminance < 130) ----
        // The internal 5-cell dividers may be rendered as dark gray instead of
        // pure black on some devices. Re-scan within the detected outer grid
        // bounds using a more forgiving threshold.
        val outerLeft   = if (result1 != null) result1.bounds.left else vertLines.first()
        val outerRight  = if (result1 != null) result1.bounds.right else vertLines.last()
        val outerTop    = if (result1 != null) result1.bounds.top else horizLines.first()
        val outerBottom = if (result1 != null) result1.bounds.bottom else horizLines.last()

        // Scan within the grid bounds (with small margin)
        val margin = minLineLength / 4
        val innerScanXStart = maxOf(scanXStart, outerLeft - margin)
        val innerScanXEnd   = minOf(scanXEnd, outerRight + margin)
        val innerScanYStart = maxOf(scanYStart, outerTop - margin)
        val innerScanYEnd   = minOf(scanYEnd, outerBottom + margin)

        val vertLines2 = scanForLines(
            pixels, imgW, innerScanXStart, innerScanXEnd, innerScanYStart, innerScanYEnd,
            vertical = true, minLineLength = minLineLength, luminanceThreshold = 130
        )
        val horizLines2 = scanForLines(
            pixels, imgW, innerScanXStart, innerScanXEnd, innerScanYStart, innerScanYEnd,
            vertical = false, minLineLength = minLineLength, luminanceThreshold = 130
        )

        Log.d(TAG, "Pass 2 (relaxed): ${vertLines2.size} vert, ${horizLines2.size} horiz")

        if (vertLines2.size >= 2 && horizLines2.size >= 2) {
            val result2 = findBestSquareGrid(vertLines2, horizLines2)
            if (result2 != null && result2.size > (result1?.size ?: 0)) {
                Log.d(TAG, "Grid selected (pass 2): ${result2.size}x${result2.size}")
                return result2
            }
        }

        // Fall back to pass-1 result (may be 5×5)
        if (result1 != null) {
            Log.d(TAG, "Grid selected (fallback): ${result1.size}x${result1.size}")
        }
        return result1
    }

    /**
     * Scans for dark lines (vertical or horizontal) in the given region.
     * Returns grouped line positions.
     */
    private fun scanForLines(
        pixels: IntArray, imgWidth: Int,
        scanXStart: Int, scanXEnd: Int,
        scanYStart: Int, scanYEnd: Int,
        vertical: Boolean,
        minLineLength: Int,
        luminanceThreshold: Int
    ): List<Int> {
        val candidates = mutableListOf<Int>()
        if (vertical) {
            for (x in scanXStart until scanXEnd) {
                val maxRun = longestContiguousDarkRun(
                    pixels, imgWidth, x, vertical = true,
                    rangeStart = scanYStart, rangeEnd = scanYEnd,
                    luminanceThreshold = luminanceThreshold
                )
                if (maxRun >= minLineLength) candidates.add(x)
            }
        } else {
            for (y in scanYStart until scanYEnd) {
                val maxRun = longestContiguousDarkRun(
                    pixels, imgWidth, y, vertical = false,
                    rangeStart = scanXStart, rangeEnd = scanXEnd,
                    luminanceThreshold = luminanceThreshold
                )
                if (maxRun >= minLineLength) candidates.add(y)
            }
        }
        return groupConsecutivePositions(candidates)
    }

    /**
     * Finds the best square grid from the given line positions using the
     * square-constraint algorithm.
     *
     * Tries all combinations of (left, right) vertical borders and
     * (top, bottom) horizontal borders, counts internal lines between them,
     * and picks the largest valid size (5..25) with reasonable squareness.
     *
     * Key improvement: larger grids are ALWAYS preferred over smaller ones
     * as long as squareness is within tolerance, because real nonograms
     * are almost never 5×5 when a 10×10 or 15×15 interpretation exists.
     * Internal line spacing is also validated for uniformity.
     */
    private fun findBestSquareGrid(
        vertLines: List<Int>, horizLines: List<Int>
    ): GridDetectionResult? {
        if (vertLines.size < 2 || horizLines.size < 2) return null

        Log.d(TAG, "  findBestSquareGrid: vertLines=$vertLines")
        Log.d(TAG, "  findBestSquareGrid: horizLines=$horizLines")

        data class Candidate(
            val size: Int,
            val squareness: Double,
            val left: Int, val top: Int,
            val right: Int, val bottom: Int
        )

        val candidates = mutableListOf<Candidate>()

        // Try all combinations of (leftBorder, rightBorder) x (topBorder, bottomBorder)
        // This avoids the assumption that the LAST line is always the border,
        // which can fail if there are spurious dark lines beyond the grid.
        for (leftIdx in vertLines.indices) {
            for (rightIdx in leftIdx + 1 until vertLines.size) {
                val candidateLeft = vertLines[leftIdx]
                val candidateRight = vertLines[rightIdx]
                val width = candidateRight - candidateLeft
                if (width <= 0) continue

                // Count internal vertical lines between left and right borders
                val internalVertLines = mutableListOf<Int>()
                for (k in leftIdx + 1 until rightIdx) {
                    internalVertLines.add(vertLines[k])
                }
                val internalVert = internalVertLines.size

                for (topIdx in horizLines.indices) {
                    for (bottomIdx in topIdx + 1 until horizLines.size) {
                        val candidateTop = horizLines[topIdx]
                        val candidateBottom = horizLines[bottomIdx]
                        val height = candidateBottom - candidateTop
                        if (height <= 0) continue

                        // Count internal horizontal lines between top and bottom borders
                        val internalHorizLines = mutableListOf<Int>()
                        for (k in topIdx + 1 until bottomIdx) {
                            internalHorizLines.add(horizLines[k])
                        }
                        val internalHoriz = internalHorizLines.size

                        // Internal line counts must match (square grid)
                        if (internalVert != internalHoriz) continue

                        val size = (internalVert + 1) * 5
                        if (size !in 5..25) continue

                        val ratio = width.toDouble() / height.toDouble()
                        val squareness = kotlin.math.abs(ratio - 1.0)

                        // Reject if aspect ratio is too far from square
                        if (squareness > 0.25) continue

                        // Validate internal line spacing is roughly uniform
                        // (real grid dividers are evenly spaced every 5 cells)
                        if (internalVert > 0 && !isSpacingUniform(
                                candidateLeft, candidateRight, internalVertLines, 0.30
                            )) continue
                        if (internalHoriz > 0 && !isSpacingUniform(
                                candidateTop, candidateBottom, internalHorizLines, 0.30
                            )) continue

                        candidates.add(Candidate(
                            size, squareness,
                            candidateLeft, candidateTop,
                            candidateRight, candidateBottom
                        ))
                    }
                }
            }
        }

        if (candidates.isEmpty()) return null

        // Log all candidates for debugging
        for (c in candidates) {
            Log.d(TAG, "  candidate: ${c.size}x${c.size}, squareness=${"%.3f".format(c.squareness)}, bounds=[${c.left},${c.top},${c.right},${c.bottom}]")
        }

        // Selection: STRONGLY prefer larger grids.
        // Among same-size candidates, prefer better squareness.
        val best = candidates.sortedWith(
            compareByDescending<Candidate> { it.size }
                .thenBy { it.squareness }
        ).first()

        Log.d(TAG, "  → selected: ${best.size}x${best.size}, squareness=${"%.3f".format(best.squareness)}, bounds=[${best.left},${best.top},${best.right},${best.bottom}]")

        return GridDetectionResult(
            bounds = Rect(best.left + 1, best.top + 1, best.right - 1, best.bottom - 1),
            size = best.size
        )
    }

    /**
     * Checks that internal divider lines are roughly uniformly spaced.
     * For a real nonogram grid, the thick dividers should be evenly spaced
     * (every 5 cells). This filters out false positives where random dark
     * UI elements create a symmetric line count but irregular spacing.
     *
     * @param borderStart Position of the starting border (left or top)
     * @param borderEnd Position of the ending border (right or bottom)
     * @param internalLines Positions of internal divider lines
     * @param tolerance Maximum allowed deviation from ideal spacing (fraction, e.g. 0.30 = 30%)
     */
    private fun isSpacingUniform(
        borderStart: Int, borderEnd: Int,
        internalLines: List<Int>, tolerance: Double
    ): Boolean {
        val totalSpan = borderEnd - borderStart
        val numSections = internalLines.size + 1
        val idealSpacing = totalSpan.toDouble() / numSections

        // Check each section
        val allPositions = listOf(borderStart) + internalLines + listOf(borderEnd)
        for (i in 1 until allPositions.size) {
            val gap = allPositions[i] - allPositions[i - 1]
            val deviation = kotlin.math.abs(gap - idealSpacing) / idealSpacing
            if (deviation > tolerance) {
                return false
            }
        }
        return true
    }

    /**
     * Finds the longest contiguous run of "black" pixels along a scan line.
     * Tolerates up to 2 non-dark pixels within a run (handles anti-aliasing artifacts).
     *
     * @param pos Column index (if vertical) or row index (if horizontal)
     * @param vertical If true, scans down column [pos]. If false, scans across row [pos].
     * @param rangeStart Start of scan range (inclusive)
     * @param rangeEnd End of scan range (exclusive)
     */
    private fun longestContiguousDarkRun(
        pixels: IntArray, width: Int, pos: Int, vertical: Boolean,
        rangeStart: Int, rangeEnd: Int,
        luminanceThreshold: Int = 80
    ): Int {
        var maxRun = 0
        var currentRun = 0
        var gapCount = 0
        val maxGapTolerance = 2 // Allow up to 2 non-dark pixels within a line

        for (i in rangeStart until rangeEnd) {
            val pixelIndex = if (vertical) i * width + pos else pos * width + i
            if (isDarkPixel(pixels[pixelIndex], luminanceThreshold)) {
                currentRun += gapCount + 1 // Re-include any tolerated gap pixels
                gapCount = 0
                if (currentRun > maxRun) maxRun = currentRun
            } else {
                gapCount++
                if (gapCount > maxGapTolerance) {
                    currentRun = 0
                    gapCount = 0
                }
            }
        }

        return maxRun
    }

    /**
     * Groups consecutive integer positions into single representative positions.
     * Adjacent pixels (within 3px gap) are considered part of the same line.
     * Returns the center position of each group.
     */
    private fun groupConsecutivePositions(positions: List<Int>): List<Int> {
        if (positions.isEmpty()) return emptyList()

        val groups = mutableListOf<MutableList<Int>>()
        var currentGroup = mutableListOf(positions[0])

        for (i in 1 until positions.size) {
            if (positions[i] - positions[i - 1] <= 3) {
                currentGroup.add(positions[i])
            } else {
                groups.add(currentGroup)
                currentGroup = mutableListOf(positions[i])
            }
        }
        groups.add(currentGroup)

        return groups.map { group -> group[group.size / 2] }
    }

    // ===== OCR Text Cleaning =====

    /**
     * Corrects common OCR misreads for clue numbers.
     * ML Kit sometimes confuses visually similar characters:
     *   O → 0, o → 0, l → 1, I → 1, S → 5, s → 5, B → 8, Z → 2, z → 2
     */
    private fun cleanOcrText(text: String): String {
        return text.map { ch ->
            when (ch) {
                'O', 'o' -> '0'
                'l', 'I' -> '1'
                'S', 's' -> '5'
                'B' -> '8'
                'Z', 'z' -> '2'
                else -> ch
            }
        }.joinToString("")
    }

    // ===== Merged Digit Splitting =====

    /**
     * Splits a raw text string from ML Kit into individual clue numbers.
     *
     * ML Kit may merge adjacent clue digits with no space between them.
     * For example, the four clues "1 4 1 3" stacked side-by-side in a single
     * row cell can be returned as the single string "1413".
     *
     * Detection strategy:
     * - Single character digit → keep as-is
     * - Exactly 2 digits and the number ≤ gridSize → keep as single clue (e.g. "12" on 15×15)
     * - Exactly 2 digits and the number > gridSize → split into 2 individual digits
     * - 3+ digits → always split into individual digits (no valid clue is 100+)
     * - Non-digits → discard (not a clue number)
     *
     * Examples with gridSize=15:
     *   "8"    → [8]           (single digit, kept)
     *   "12"   → [12]          (2 digits, 12 ≤ 15, kept as single clue)
     *   "21"   → [2, 1]        (2 digits, 21 > 15, split)
     *   "1413" → [1, 4, 1, 3]  (4 digits, always split)
     */
    private fun splitMergedDigits(text: String, rect: Rect, gridSize: Int): List<NumWithRect> {
        // Strip any remaining non-digit characters
        val cleaned = text.filter { it.isDigit() }
        if (cleaned.isEmpty()) return emptyList()

        // Single digit — always a valid clue
        if (cleaned.length == 1) {
            val digit = cleaned.toInt()
            return listOf(NumWithRect(digit, rect))
        }

        // Exactly 2 digits — could be a valid two-digit clue (e.g. 12 on a 15×15 grid)
        if (cleaned.length == 2) {
            val asInt = cleaned.toInt()
            if (asInt in 1..gridSize) {
                return listOf(NumWithRect(asInt, rect))
            }
            // Exceeds grid size — split into individual digits
            return splitIntoDigits(cleaned, rect)
        }

        // 3+ digits — always split (no valid clue is 100+)
        return splitIntoDigits(cleaned, rect)
    }

    /**
     * Splits a digit string into individual single-digit NumWithRect items,
     * dividing the bounding box evenly among them.
     */
    private fun splitIntoDigits(digits: String, rect: Rect): List<NumWithRect> {
        val count = digits.length
        val subWidth = if (count > 0) rect.width() / count else rect.width()
        return digits.mapIndexed { index, ch ->
            val subLeft = rect.left + index * subWidth
            val subRect = Rect(subLeft, rect.top, subLeft + subWidth, rect.bottom)
            NumWithRect(ch.digitToInt(), subRect)
        }
    }

    // ===== Clue Number Clustering =====

    /**
     * Groups items by position using a fixed gap threshold.
     * Items are sorted by position, then split into groups wherever
     * the gap between consecutive items exceeds [gapThreshold].
     *
     * The threshold is derived from the detected cell dimensions,
     * so it adapts to any grid size (5×5 through 15×15).
     */
    private fun clusterByGap(
        items: List<NumWithRect>,
        gapThreshold: Int,
        posExtractor: (NumWithRect) -> Int
    ): List<List<NumWithRect>> {
        if (items.isEmpty()) return emptyList()

        val sorted = items.sortedBy { posExtractor(it) }

        val groups = mutableListOf<MutableList<NumWithRect>>()
        var currentGroup = mutableListOf(sorted[0])

        for (i in 1 until sorted.size) {
            val gap = posExtractor(sorted[i]) - posExtractor(sorted[i - 1])
            if (gap > gapThreshold) {
                groups.add(currentGroup)
                currentGroup = mutableListOf(sorted[i])
            } else {
                currentGroup.add(sorted[i])
            }
        }
        groups.add(currentGroup)

        return groups
    }

    // ===== Pixel Classification =====

    /**
     * Returns true if the pixel is darker than the given luminance threshold.
     * Default threshold of 80 detects only thick black grid lines.
     * A relaxed threshold (~130) also catches dark gray internal dividers.
     */
    private fun isDarkPixel(pixel: Int, luminanceThreshold: Int = 80): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val luminance = 0.299 * r + 0.587 * g + 0.114 * b
        return luminance < luminanceThreshold
    }

    private data class NumWithRect(val value: Int, val rect: Rect)
}
