package com.example.learnedge

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import com.example.learnedge.data.content.ContentRepository
import com.example.learnedge.data.content.QuestionBankRepository
import com.example.learnedge.data.curriculum.CurriculumRepository
import com.example.learnedge.data.db.AppDatabase
import com.example.learnedge.data.diagnostic.DiagnosticRepository
import com.example.learnedge.data.learner.LearnerStateRepository
import com.example.learnedge.data.plan.PlanRepository
import com.example.learnedge.data.profile.ProfileRepository
import com.example.learnedge.data.tips.TipsRepository
import com.example.learnedge.llm.LlmService
import com.example.learnedge.llm.benchmark.BenchmarkLog
import com.example.learnedge.llm.benchmark.GenerationRecorder
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LearnEdgeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.appScope.launch { container.curriculum.seedIfEmpty() }
    }
}

/** Manual dependency wiring: one database, one model and one instance of each repository for the whole app. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database = AppDatabase.build(context)
    val curriculum = CurriculumRepository(database, context.assets)
    val profiles = ProfileRepository(database)
    val learnerState = LearnerStateRepository(database)
    val diagnostics = DiagnosticRepository(context.assets)
    val plans = PlanRepository(database, curriculum, profiles, learnerState)

    /** Starts loading the default (on-device) model in the background right away. */
    val llm = LlmService(context.applicationContext, appScope)
    val benchmarkLog = BenchmarkLog(context)
    val recorder = GenerationRecorder(context, benchmarkLog)
    val tips = TipsRepository(database, llm, recorder, appScope)

    /** The trusted, hand-written question bank (used by the quiz; the LLM never writes questions). */
    val questionBank = QuestionBankRepository(context.assets)

    /**
     * Explanations: cached AI text, live generation, or the bundled key points. The flag file lets a
     * debug build force every validation to fail, to show the fallback on the phone.
     */
    val content = ContentRepository(
        database, curriculum, profiles, learnerState, llm, recorder, appScope,
        forceInvalid = {
            val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            debuggable && File(context.getExternalFilesDir(null), FORCE_INVALID_FLAG_FILE).exists()
        },
    )

    private companion object {
        const val FORCE_INVALID_FLAG_FILE = "force_validation_failure"
    }
}

val Context.appContainer: AppContainer get() = (applicationContext as LearnEdgeApp).container
