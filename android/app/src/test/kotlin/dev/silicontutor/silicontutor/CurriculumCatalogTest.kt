package dev.silicontutor.silicontutor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurriculumCatalogTest {
    @Test
    fun shipsThirtySubstantiveUnitsWithTwentyQuestionsAndATestEach() {
        val documents = listOf("beginner.json", "intermediate.json", "expert.json")
            .associateWith { name ->
                javaClass.classLoader!!.getResource(name)!!.readText()
            }
        val catalog = CurriculumCatalog.fromJson(documents)

        assertEquals(30, catalog.lessons.size)
        assertEquals(600, catalog.questions.size)
        assertEquals(10, catalog.lessons.count { it.level == "Beginner" })
        assertEquals(10, catalog.lessons.count { it.level == "Intermediate" })
        assertEquals(10, catalog.lessons.count { it.level == "Expert" })
        catalog.lessons.forEach { lesson ->
            assertTrue(lesson.content.trim().split(Regex("\\s+")).size >= 250)
            assertTrue(lesson.testObjective.isNotBlank())
            assertTrue(lesson.testbench.isNotBlank())
            assertTrue(lesson.questions.size >= 20)
            assertEquals(lesson.questions.size, lesson.questions.map { it.question }.distinct().size)
            lesson.questions.forEach { question ->
                assertEquals(4, question.options.size)
                assertTrue(question.answer in question.options.indices)
                assertTrue(question.explanation.isNotBlank())
            }
        }
    }
}
