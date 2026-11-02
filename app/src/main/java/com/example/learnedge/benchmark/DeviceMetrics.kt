package com.example.learnedge.benchmark

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object DeviceMetrics {
    /** Proportional set size of this app's process in MB, including resident pages of the mapped model. */
    fun pssMb(): Double = Debug.getPss() / 1024.0

    /** Battery temperature in °C from the sticky ACTION_BATTERY_CHANGED broadcast, or null if unavailable. */
    fun batteryTempC(context: Context): Double? {
        val intent = ContextCompat.registerReceiver(
            context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        ) ?: return null
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (tenths == Int.MIN_VALUE) null else tenths / 10.0
    }
}

/** Samples PSS in the background while a block runs and keeps the highest value seen. */
class PeakPssSampler(private val intervalMs: Long = 250) {
    @Volatile var peakMb = 0.0
        private set

    suspend fun <T> measure(block: suspend () -> T): T = coroutineScope {
        sample()
        val sampler = launch(Dispatchers.Default) {
            while (isActive) {
                delay(intervalMs)
                sample()
            }
        }
        try {
            block()
        } finally {
            sampler.cancel()
            sample()
        }
    }

    private fun sample() {
        peakMb = maxOf(peakMb, DeviceMetrics.pssMb())
    }
}
