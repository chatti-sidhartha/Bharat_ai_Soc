package com.example.learnedge.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteTable
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec

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
        StudyPlanEntity::class,
        PlanItemEntity::class,
        DailyTipEntity::class,
        GeneratedContentEntity::class,
        QuestionAttemptEntity::class,
        ReportedQuestionEntity::class,
    ],
    version = 7,
    exportSchema = true,
    // Schema changes are migrated, never destructive: learner data must survive app updates.
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        // v5 had a topic_lessons table (one JSON-style AI lesson per topic); generated_content replaces it.
        AutoMigration(from = 4, to = 6),
        AutoMigration(from = 5, to = 6, spec = AppDatabase.Migration5To6::class),
        // v6 -> v7: question_attempts and reported_questions are new tables.
        AutoMigration(from = 6, to = 7),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun curriculumDao(): CurriculumDao
    abstract fun learnerStateDao(): LearnerStateDao
    abstract fun profileDao(): ProfileDao
    abstract fun planDao(): PlanDao
    abstract fun tipDao(): TipDao
    abstract fun generatedContentDao(): GeneratedContentDao
    abstract fun quizDao(): QuizDao

    /** v5 -> v6: the topic_lessons table (one lesson per topic) is replaced by generated_content. */
    @DeleteTable(tableName = "topic_lessons")
    class Migration5To6 : AutoMigrationSpec

    companion object {
        const val NAME = "learnedge.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME).build()
    }
}
