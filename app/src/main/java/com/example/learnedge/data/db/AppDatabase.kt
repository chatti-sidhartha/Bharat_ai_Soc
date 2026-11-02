package com.example.learnedge.data.db

import android.content.Context
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
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun curriculumDao(): CurriculumDao
    abstract fun learnerStateDao(): LearnerStateDao

    companion object {
        const val NAME = "learnedge.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME).build()
    }
}
