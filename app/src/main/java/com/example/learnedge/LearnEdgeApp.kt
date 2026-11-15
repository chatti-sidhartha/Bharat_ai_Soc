package com.example.learnedge

import android.app.Application
import android.content.Context
import com.example.learnedge.data.content.ContentRepository
import com.example.learnedge.data.curriculum.CurriculumRepository
import com.example.learnedge.data.db.AppDatabase
import com.example.learnedge.data.diagnostic.DiagnosticRepository
import com.example.learnedge.data.learner.LearnerStateRepository
import com.example.learnedge.data.lessons.LessonRepository
import com.example.learnedge.data.plan.PlanRepository
import com.example.learnedge.data.profile.ProfileRepository
import com.example.learnedge.data.tips.TipsRepository
import com.example.learnedge.llm.LlmService
import com.example.learnedge.llm.benchmark.BenchmarkLog
import com.example.learnedge.llm.benchmark.GenerationRecorder
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

    /** Hand-written key points and question bank, bundled in assets. */
    val content = ContentRepository(context.assets)
    val lessons = LessonRepository(database, llm, recorder, appScope)
}

val Context.appContainer: AppContainer get() = (applicationContext as LearnEdgeApp).container
