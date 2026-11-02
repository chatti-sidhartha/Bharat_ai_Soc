package com.example.learnedge.domain

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keeps domain/ pure Kotlin so every rule in it runs as a plain JVM unit test. */
class DomainPurityTest {
    @Test
    fun domainSources_haveNoAndroidImports() {
        val projectDir = System.getProperty("projectDir") ?: error("projectDir system property not set by Gradle")
        val domainDir = File(projectDir, "src/main/java/com/example/learnedge/domain")
        val sources = domainDir.walk().filter { it.extension == "kt" }.toList()
        assertTrue("No domain sources found in $domainDir", sources.isNotEmpty())

        val forbidden = Regex("""^import\s+(android|androidx|com\.example\.learnedge\.(data|ui|llm))\.""")
        val violations = sources.flatMap { file ->
            file.readLines().filter { forbidden.containsMatchIn(it.trim()) }.map { "${file.name}: ${it.trim()}" }
        }
        assertTrue("domain/ must stay pure Kotlin:\n${violations.joinToString("\n")}", violations.isEmpty())
    }
}
