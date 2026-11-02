package com.example.learnedge

import android.app.Application
import android.content.Context
import com.example.learnedge.data.curriculum.CurriculumRepository
import com.example.learnedge.data.db.AppDatabase
import com.example.learnedge.data.profile.ProfileRepository
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

/** Manual dependency wiring: one database and one instance of each repository for the whole app. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database = AppDatabase.build(context)
    val curriculum = CurriculumRepository(database, context.assets)
    val profiles = ProfileRepository(database)
}

val Context.appContainer: AppContainer get() = (applicationContext as LearnEdgeApp).container
