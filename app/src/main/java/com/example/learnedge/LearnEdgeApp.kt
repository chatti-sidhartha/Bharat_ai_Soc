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
import com.example.learnedge.data.nightshift.DeviceSensors
import com.example.learnedge.data.nightshift.LessonOpenLog
import com.example.learnedge.data.nightshift.NightShiftLog
import com.example.learnedge.data.nightshift.NightShiftPrefs
import com.example.learnedge.data.nightshift.NightShiftRepository
import com.example.learnedge.data.nightshift.NightShiftScheduler
import com.example.learnedge.data.profile.ProfileRepository
import com.example.learnedge.data.quiz.QuizRepository
import com.example.learnedge.data.tips.TipsRepository
import com.example.learnedge.domain.ThermalGovernor
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
        if (container.nightShift.prefs.enabled.value) NightShiftScheduler.ensureScheduled(this)
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

    /** Real phone readings and the thermal policy (all thresholds are in GovernorConfig). */
    val sensors = DeviceSensors(context)
    val governor = ThermalGovernor()

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
        foregroundPolicy = { governor.foreground(sensors.read()) },
    )

    /** Quiz answers -> mastery (one BKT update each) -> plan rebuilt from tomorrow. */
    val quiz = QuizRepository(database, questionBank, learnerState, plans)

    /** Night Shift: prepares upcoming lessons while the phone charges (and on "Prepare now"). */
    val nightShift = NightShiftRepository(
        context.applicationContext, curriculum, profiles, learnerState, plans, content, tips, llm,
        sensors, governor, NightShiftLog(context), NightShiftPrefs(context),
    )

    /** Times how long lessons take to open (lesson_open.csv). */
    val lessonOpenLog = LessonOpenLog(context)

    private companion object {
        const val FORCE_INVALID_FLAG_FILE = "force_validation_failure"
    }
}

val Context.appContainer: AppContainer get() = (applicationContext as LearnEdgeApp).container
