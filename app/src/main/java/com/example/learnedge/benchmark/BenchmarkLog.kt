package com.example.learnedge.benchmark

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Appends every [BenchmarkRecord] to a CSV and mirrors it to logcat under [TAG].
 * The CSV sits in the app's external files dir (next to model.pte) so `adb pull` can read it
 * without root: /sdcard/Android/data/com.example.learnedge/files/benchmarks.csv
 */
class BenchmarkLog(context: Context) {
    val file = File(context.getExternalFilesDir(null) ?: context.filesDir, FILE_NAME)

    @Volatile private var headerLogged = false

    suspend fun append(record: BenchmarkRecord) = withContext(Dispatchers.IO) {
        val row = record.toCsvRow()
        if (!headerLogged) {
            Log.i(TAG, "header,${BenchmarkRecord.CSV_HEADER}")
            headerLogged = true
        }
        Log.i(TAG, "row,$row")
        try {
            synchronized(this@BenchmarkLog) {
                if (!file.exists() || file.length() == 0L) file.writeText(BenchmarkRecord.CSV_HEADER + "\n")
                file.appendText(row + "\n")
            }
        } catch (e: IOException) {
            // Losing a CSV row must never break the chat; the row is still in logcat.
            Log.e(TAG, "Could not write ${file.absolutePath}", e)
        }
    }

    fun logSummary(lines: List<String>) {
        lines.forEach { Log.i(TAG, "summary,$it") }
    }

    companion object {
        const val TAG = "LearnEdgeBench"
        const val FILE_NAME = "benchmarks.csv"
    }
}
