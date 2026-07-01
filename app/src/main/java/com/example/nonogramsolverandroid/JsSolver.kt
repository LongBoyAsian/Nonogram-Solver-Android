package com.example.nonogramsolverandroid

import android.content.Context
import androidx.javascriptengine.JavaScriptSandbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

class JsSolver(private val context: Context) {

    suspend fun solve(clues: PuzzleClues): List<List<Int>>? = withContext(Dispatchers.IO) {
        val sandbox = JavaScriptSandbox.createConnectedInstanceAsync(context).get()
        val isolate = sandbox.createIsolate()

        try {
            val solverJs = context.assets.open("solver.js").bufferedReader().use { it.readText() }
            isolate.evaluateJavaScriptAsync(solverJs).get()

            val rowCluesJson = JSONArray(clues.rowClues.map { JSONArray(it) })
            val colCluesJson = JSONArray(clues.colClues.map { JSONArray(it) })

            val script = """
                (function() {
                    try {
                        const solver = new NonogramSolver(${clues.size}, $rowCluesJson, $colCluesJson);
                        if (solver.solve()) {
                            return JSON.stringify(solver.grid);
                        }
                    } catch (e) {
                        return "ERROR: " + e.message;
                    }
                    return "NULL";
                })();
            """.trimIndent()

            val resultJson = isolate.evaluateJavaScriptAsync(script).get()
            
            if (resultJson == null || resultJson == "NULL" || resultJson.startsWith("ERROR")) {
                return@withContext null
            }

            val gridArray = JSONArray(resultJson)
            List(gridArray.length()) { r ->
                val row = gridArray.getJSONArray(r)
                List(row.length()) { c -> row.getInt(c) }
            }
        } catch (e: Exception) {
            null
        } finally {
            isolate.close()
            sandbox.close()
        }
    }
}
