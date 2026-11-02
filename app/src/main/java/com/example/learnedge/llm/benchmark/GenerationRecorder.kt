package com.example.learnedge.llm.benchmark

import android.content.Context
import com.example.learnedge.llm.GenerationStats
import com.example.learnedge.llm.LlmEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MeasuredGeneration(val stats: GenerationStats, val record: BenchmarkRecord)

/** Runs [LlmEngine.generate] with device metrics around it and logs the result to [log]. */
class GenerationRecorder(context: Context, private val log: BenchmarkLog) {
    private val appContext = context.applicationContext

    suspend fun generate(
        engine: LlmEngine,
        promptLabel: String,
        run: Int,
        prompt: String,
        onToken: (String) -> Unit,
    ): MeasuredGeneration {
        val tempStart = DeviceMetrics.batteryTempC(appContext)
        val sampler = PeakPssSampler()
        val stats = sampler.measure { engine.generate(prompt, onToken) }
        val record = BenchmarkRecord(
            timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()),
            engine = engine.name,
            promptLabel = promptLabel,
            run = run,
            promptChars = prompt.length,
            loadTimeMs = engine.loadTimeMs,
            timeToFirstTokenMs = stats.timeToFirstTokenMs,
            decodeTokensPerSecond = stats.tokensPerSecond,
            totalTokens = stats.tokenCount,
            totalTimeMs = stats.totalTimeMs,
            peakPssMb = sampler.peakMb,
            batteryTempStartC = tempStart,
            batteryTempEndC = DeviceMetrics.batteryTempC(appContext),
        )
        log.append(record)
        return MeasuredGeneration(stats, record)
    }
}
