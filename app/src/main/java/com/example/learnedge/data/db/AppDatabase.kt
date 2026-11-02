package com.example.learnedge.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** Everything the app stores lives in this on-device SQLite database; nothing is sent off the phone. */
@Database(
    entities = [
        TrackEntity::class,
        SkillEntity::class,
        TopicEntity::class,
        PrerequisiteEntity::class,
        LearnerTopicStateEntity::class,
        LearnerProfileEntity::class,
        SkillRatingEntity::class,
    ],
    version = 2,
    exportSchema = true,
    // Schema changes are migrated, never destructive: learner data must survive app updates.
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun curriculumDao(): CurriculumDao
    abstract fun learnerStateDao(): LearnerStateDao
    abstract fun profileDao(): ProfileDao

    companion object {
        const val NAME = "learnedge.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME).build()
    }
}
