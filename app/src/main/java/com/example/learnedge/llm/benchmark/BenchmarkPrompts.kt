package com.example.learnedge.llm.benchmark

data class BenchmarkPrompt(val label: String, val text: String)

/** Fixed prompts so runs are comparable across builds, engines and devices. Don't edit casually. */
object BenchmarkPrompts {
    const val RUNS_PER_PROMPT = 3

    val SHORT = BenchmarkPrompt(
        "short",
        "What is a variable in Java? Answer in one sentence.",
    )

    val MEDIUM = BenchmarkPrompt(
        "medium",
        "Explain the difference between a for loop and a while loop in Java. " +
            "Give one short code example of each and say when to prefer which.",
    )

    val LONG = BenchmarkPrompt(
        "long",
        "I am a second-year computer science student. My goal is to become a Java backend developer " +
            "in 60 days and I can study 2 hours every day. My current skills out of 10 are: Java 6, " +
            "SQL 4, Git 3, REST APIs 2 and Spring Boot 2. I learn best with small projects and short " +
            "quizzes. Write a detailed 7-day study plan for the first week. For each day give the topic, " +
            "what to read, one hands-on exercise and one self-check question.",
    )

    val ALL = listOf(SHORT, MEDIUM, LONG)

    /**
     * Run order: rounds of short, medium, long, repeated [runs] times. Interleaving spreads the
     * phone's heat build-up over all prompts instead of penalising whichever runs last.
     */
    fun schedule(runs: Int = RUNS_PER_PROMPT): List<Pair<BenchmarkPrompt, Int>> =
        (1..runs).flatMap { run -> ALL.map { it to run } }
}
