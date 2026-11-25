package com.example.learnedge.data.nightshift

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.learnedge.appContainer
import java.util.concurrent.TimeUnit

/**
 * One background run of Night Shift. WorkManager only starts it while the phone is charging and the battery is
 * not low. It prepares lessons for at most the governor's run budget (about 8 minutes, under WorkManager's 10),
 * and if work remains it enqueues the next run itself, so no foreground service is needed.
 */
class NightShiftWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val night = applicationContext.appContainer.nightShift
        if (!night.prefs.enabled.value) return Result.success()
        val result = night.run(foreground = false)
        Log.i(TAG, "Night Shift run finished: $result")
        if (result.remaining > 0) {
            // Budget reached or the learner needed the model: carry on soon. Heat, charging or battery: wait longer.
            val soon = result.stopReason?.let { "budget" in it || "learner" in it } ?: true
            NightShiftScheduler.scheduleContinuation(applicationContext, if (soon) 1 else night.config.rescheduleDelayMinutes)
        }
        return Result.success()
    }

    private companion object {
        const val TAG = "LearnEdgeNight"
    }
}

object NightShiftScheduler {
    private const val PERIODIC = "nightshift-periodic"
    private const val CONTINUE = "nightshift-continue"

    private fun constraints() = Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build()

    /** A check every 6 hours (new plan days appear), and one prompt run so a phone that is already charging starts at once. */
    fun ensureScheduled(context: Context) {
        val work = WorkManager.getInstance(context)
        work.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<NightShiftWorker>(6, TimeUnit.HOURS).setConstraints(constraints()).build(),
        )
        scheduleContinuation(context, delayMinutes = 0)
    }

    /** The next run, after [delayMinutes]. Appended, so a run that is working right now is never replaced by its own successor. */
    fun scheduleContinuation(context: Context, delayMinutes: Long) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            CONTINUE, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<NightShiftWorker>().setConstraints(constraints()).setInitialDelay(delayMinutes, TimeUnit.MINUTES).build(),
        )
    }

    fun cancelAll(context: Context) {
        val work = WorkManager.getInstance(context)
        work.cancelUniqueWork(PERIODIC)
        work.cancelUniqueWork(CONTINUE)
    }
}
