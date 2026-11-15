package com.example.learnedge.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuizLoopTest {
    private val eps = 1e-9
    private val start = 0.26

    // Expected values computed independently of the app code (plain BKT arithmetic, P(T)=0.15, P(S)=0.10, P(G)=0.20).
    private val threeCorrect = listOf(0.6706806282722513, 0.9163760068819895, 0.9831055252937092)
    private val threeWrong = listOf(0.18576051779935274, 0.17356777122983286, 0.17174385392372116)
    private val wrongThenTwoCorrect = listOf(0.18576051779935274, 0.5805844283192783, 0.8824215414866368)

    private fun attempt(id: String, correct: Boolean, at: Long = 0) = QuizAttempt(id, "t", correct, at, 0.0, 0.0)

    private fun replay(vararg answers: Boolean) =
        AttemptHistory.replay(start, answers.mapIndexed { i, c -> attempt("q${i + 1}", c) }, emptySet())

    // --- A correct/wrong sequence gives the expected P(L) ---

    @Test
    fun threeCorrectAnswers_giveTheExpectedMastery_andMasterTheTopic() {
        val after = replay(true, true, true).map { it.pLAfter }
        threeCorrect.forEachIndexed { i, expected -> assertEquals("after answer ${i + 1}", expected, after[i], eps) }
        assertFalse("two correct answers are not enough", Bkt.isMastered(after[1]))
        assertTrue("three correct answers master it (P(L) >= 0.95)", Bkt.isMastered(after[2]))
    }

    @Test
    fun threeWrongAnswers_keepMasteryLow() {
        val after = replay(false, false, false).map { it.pLAfter }
        threeWrong.forEachIndexed { i, expected -> assertEquals("after answer ${i + 1}", expected, after[i], eps) }
        assertFalse(Bkt.isMastered(after.last()))
    }

    @Test
    fun aMixedSequence_givesTheExpectedMastery_andTheAttemptsChain() {
        val chain = replay(false, true, true)
        wrongThenTwoCorrect.forEachIndexed { i, expected -> assertEquals("after answer ${i + 1}", expected, chain[i].pLAfter, eps) }
        assertEquals(start, chain[0].pLBefore, eps)
        assertEquals(chain[0].pLAfter, chain[1].pLBefore, eps)
        assertEquals(chain[1].pLAfter, chain[2].pLBefore, eps)
    }

    // --- Reporting a question restores mastery ---

    @Test
    fun reportingTheWrongAnswer_restoresMasteryToWhatItWouldHaveBeen() {
        val history = replay(false, true, true)
        val afterReport = AttemptHistory.replay(history.first().pLBefore, history, reported = setOf("q1"))
        assertEquals(Bkt.updateAll(start, listOf(true, true)), afterReport.last().pLAfter, eps)
        assertEquals(0.9163760068819895, afterReport.last().pLAfter, eps)
        // The reported attempt no longer changes anything, and the later ones are re-chained from the start value.
        assertEquals(start, afterReport[0].pLBefore, eps)
        assertEquals(start, afterReport[0].pLAfter, eps)
        assertEquals(start, afterReport[1].pLBefore, eps)
    }

    @Test
    fun reportingEveryQuestion_restoresTheStartingMastery() {
        val history = replay(false, true, true)
        val afterReport = AttemptHistory.replay(start, history, reported = setOf("q1", "q2", "q3"))
        assertEquals(start, afterReport.last().pLAfter, eps)
    }

    @Test
    fun reportingALaterQuestion_leavesTheEarlierAnswersAlone() {
        val history = replay(true, true, false)
        // The app replays only from the reported attempt's own starting point.
        val reportedAttempt = history[2]
        val replayed = AttemptHistory.replay(reportedAttempt.pLBefore, listOf(reportedAttempt), reported = setOf("q3"))
        assertEquals(history[1].pLAfter, replayed.single().pLAfter, eps)
        assertEquals(0.9163760068819895, replayed.single().pLAfter, eps)
    }

    @Test
    fun reportingAQuestionAnsweredTwice_removesBothAttempts() {
        val twice = AttemptHistory.replay(
            start,
            listOf(attempt("q1", true, 1), attempt("q2", true, 2), attempt("q1", false, 3)),
            emptySet(),
        )
        val afterReport = AttemptHistory.replay(start, twice, reported = setOf("q1"))
        // Only q2 (correct) is left.
        assertEquals(threeCorrect[0], afterReport.last().pLAfter, eps)
    }

    // --- QuizSelector ---

    private fun question(id: String, difficulty: Int) =
        BankQuestion(id, "t", "Question $id?", listOf("a", "b", "c", "d"), 0, "because", difficulty)

    private val bank = listOf(question("q1", 2), question("q2", 1), question("q3", 3), question("q4", 2), question("q5", 1), question("q6", 3))

    @Test
    fun selector_prefersQuestionsNotSeenRecently() {
        val seen = mapOf("q1" to 100L, "q2" to 50L, "q3" to 200L)
        val three = QuizSelector.select(bank, seen, hidden = emptySet(), count = 3).map { it.id }
        // q4, q5 and q6 were never seen, so they come first.
        assertEquals(setOf("q4", "q5", "q6"), three.toSet())
        val five = QuizSelector.select(bank, seen, hidden = emptySet(), count = 5).map { it.id }.toSet()
        // After the unseen ones, the least recently seen (q2 at 50, then q1 at 100) are chosen; q3 (200) waits.
        assertEquals(setOf("q4", "q5", "q6", "q2", "q1"), five)
    }

    @Test
    fun selector_neverUsesReportedQuestions_andAsksEasiestFirst() {
        val picked = QuizSelector.select(bank, emptyMap(), hidden = setOf("q2", "q5"), count = 5)
        assertTrue(picked.none { it.id == "q2" || it.id == "q5" })
        assertEquals(picked.map { it.difficulty }.sorted(), picked.map { it.difficulty })
        assertEquals(4, picked.size)
    }

    @Test
    fun selector_staysWithinThreeToFiveQuestions() {
        assertEquals(5, QuizSelector.select(bank, emptyMap(), emptySet(), count = 99).size)
        assertEquals(3, QuizSelector.select(bank, emptyMap(), emptySet(), count = 1).size)
        // A bank of exactly three questions gives all three.
        assertEquals(3, QuizSelector.select(bank.take(3), emptyMap(), emptySet()).size)
        // With one of three reported only two are left: the bank is the limit, not the rule.
        assertEquals(2, QuizSelector.select(bank.take(3), emptyMap(), setOf("q1")).size)
    }

    // --- The plan after a quiz ---

    private val today = LocalDate.of(2026, 11, 16)
    private val track = Track(
        "t", "T",
        listOf(Skill("java", "Java", 8, 1.0), Skill("sql", "SQL", 7, 1.0)),
        KnowledgeGraph(
            listOf(Topic("j1", "J1", "java", 60), Topic("j2", "J2", "java", 60), Topic("s1", "S1", "sql", 60)),
            mapOf("j2" to setOf("j1")),
        ),
    )
    private val profile = LearnerProfile(
        trackId = "t", interests = "", selfRatings = mapOf("java" to 4, "sql" to 4),
        dailyMinutes = 60, deadline = today.plusDays(5), diagnosticCompleted = true,
    )

    /** The plan for the given mastery of j1; j2 is untouched and s1 is already mastered. */
    private fun planWithJ1At(j1: Double, forProfile: LearnerProfile = profile) =
        StudyPlanner.generate(track, forProfile, mapOf("j1" to j1, "j2" to 0.1, "s1" to 0.99), today)

    @Test
    fun aTopicFailedInAQuiz_staysInTheNextPlan() {
        val ids = planWithJ1At(threeWrong.last()).days.flatMap { it.items }.map { it.topicId }
        assertTrue("j1 is still to be studied", "j1" in ids)
        assertTrue("and its dependent j2 is planned after it", ids.indexOf("j1") < ids.indexOf("j2"))
    }

    @Test
    fun aMasteredTopic_disappearsFromThePlan_andItsDependentBecomesSchedulable() {
        // One free day: with j1 not mastered, j2 must wait behind its prerequisite and does not fit.
        val tight = profile.copy(deadline = today.plusDays(1))
        val before = planWithJ1At(threeWrong.last(), tight)
        val after = planWithJ1At(threeCorrect.last(), tight)

        assertEquals(listOf("j1"), before.days.flatMap { it.items }.map { it.topicId })
        assertEquals(listOf("j2"), before.deferred.map { it.topicId })

        val afterIds = after.days.flatMap { it.items }.map { it.topicId }
        assertFalse("a mastered topic is no longer planned", "j1" in afterIds)
        assertEquals("its dependent is now schedulable", listOf("j2"), afterIds)
        assertTrue(after.deferred.isEmpty())
    }
}
