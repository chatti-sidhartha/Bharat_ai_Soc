package com.example.learnedge.data.nightshift

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.learnedge.domain.GovernorInputs
import com.example.learnedge.domain.ThermalStatus
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Reads the real phone: thermal status, battery temperature, battery level and whether it is charging. */
class DeviceSensors(context: Context) {
    private val appContext = context.applicationContext

    fun read(): GovernorInputs {
        val battery = ContextCompat.registerReceiver(
            appContext, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ThermalStatus.fromAndroid(appContext.getSystemService(PowerManager::class.java).currentThermalStatus)
        } else {
            ThermalStatus.NONE
        }
        return GovernorInputs(
            thermal = thermal,
            batteryTempC = if (tenths == Int.MIN_VALUE) null else tenths / 10.0,
            batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else 100,
            charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
        )
    }
}

/** The learner's Night Shift switch. On by default. */
class NightShiftPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("nightshift", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
    }
}

/** One row of nightshift.csv: one unit of preparation work, or one pause between units. */
data class NightShiftRow(
    val mode: String,
    val kind: String,
    val item: String,
    val tokens: Int,
    val seconds: Double,
    val thermal: String,
    val batteryTempStartC: Double?,
    val batteryTempEndC: Double?,
    val charging: Boolean,
    val batteryPercent: Int,
    val result: String,
) {
    fun toCsv(timestamp: String): String = listOf(
        timestamp, mode, kind, item, tokens.toString(), String.format(Locale.US, "%.1f", seconds), thermal,
        batteryTempStartC?.let { String.format(Locale.US, "%.1f", it) }.orEmpty(),
        batteryTempEndC?.let { String.format(Locale.US, "%.1f", it) }.orEmpty(),
        charging.toString(), batteryPercent.toString(), result,
    ).joinToString(",")

    companion object {
        const val HEADER =
            "timestamp,mode,kind,item,tokens,seconds,thermal,battery_temp_start_c,battery_temp_end_c,charging,battery_percent,result"
    }
}

/** Appends every Night Shift item to nightshift.csv in the app's external files folder (readable with adb pull). */
class NightShiftLog(context: Context) {
    private val file = File(context.getExternalFilesDir(null) ?: context.filesDir, "nightshift.csv")

    fun append(row: NightShiftRow) {
        val line = row.toCsv(now())
        Log.i(TAG, line)
        try {
            synchronized(this) {
                if (!file.exists() || file.length() == 0L) file.writeText(NightShiftRow.HEADER + "\n")
                file.appendText(line + "\n")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Could not write ${file.absolutePath}", e)
        }
    }

    private companion object {
        const val TAG = "LearnEdgeNight"
    }
}

/** How long a lesson took to open, for the "prepared vs live" measurement: lesson_open.csv. */
class LessonOpenLog(context: Context) {
    private val file = File(context.getExternalFilesDir(null) ?: context.filesDir, "lesson_open.csv")

    /**
     * @param kind "cached" (an AI explanation was there at once), "live" (it was generated while the learner waited)
     *   or "bundled" (the key points were shown because generation did not produce valid text).
     * @param firstTextMs time from opening the lesson until the first explanation text was readable.
     * @param readyMs time until the explanation was complete.
     */
    fun append(topicId: String, kind: String, firstTextMs: Long, readyMs: Long) {
        val line = "${now()},$topicId,$kind,$firstTextMs,$readyMs"
        Log.i(TAG, line)
        try {
            synchronized(this) {
                if (!file.exists() || file.length() == 0L) file.writeText("timestamp,topic,kind,first_text_ms,ready_ms\n")
                file.appendText(line + "\n")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Could not write ${file.absolutePath}", e)
        }
    }

    private companion object {
        const val TAG = "LearnEdgeLessonOpen"
    }
}

private fun now(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
