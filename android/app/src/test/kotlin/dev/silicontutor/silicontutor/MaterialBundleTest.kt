package dev.silicontutor.silicontutor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterialBundleTest {
    @Test
    fun validatesAndParsesGeneratedLessonsAndQuestions() {
        val bundle = parseGeneratedMaterialBundle(
            """
            {"format":"silicontutor-material","version":1,"title":"RTL basics",
             "lessons":[{"title":"Registers","description":"Clocked state",
              "content":"Registers update on a clock edge.","example":"q <= d;",
              "keyPoints":["Use nonblocking assignments"]}],
             "questions":[{"question":"What updates a register?","options":["<=","assign"],
              "answer":0,"explanation":"The source says registers use nonblocking assignments."}]}
            """.trimIndent(),
        )

        assertEquals("RTL basics", bundle.title)
        assertEquals("Registers", bundle.lessons.single().title)
        assertEquals("Use nonblocking assignments", bundle.lessons.single().keyPoints.single())
        assertEquals(0, bundle.questions.single().answer)
    }

    @Test
    fun rejectsInvalidAnswerIndexAndDuplicateOptions() {
        val invalidAnswer = generatedBundle(answer = 3)
        assertTrue(expectFailure { parseGeneratedMaterialBundle(invalidAnswer) }
            .message.orEmpty().contains("answer index"))

        val duplicate = generatedBundle(options = listOf("same", "same"))
        assertTrue(expectFailure { parseGeneratedMaterialBundle(duplicate) }
            .message.orEmpty().contains("answer index"))
    }

    @Test
    fun enforcesGeneratedQuestionCountAndOutputLimit() {
        val noQuestions = generatedBundle(includeQuestion = false)
        assertTrue(expectFailure { parseGeneratedMaterialBundle(noQuestions) }
            .message.orEmpty().contains("1 to 10 lessons and 1 to 30"))

        assertTrue(expectFailure {
            parseGeneratedMaterialBundle(" ".repeat(50_001))
        }.message.orEmpty().contains("50,000-character"))
    }

    private fun generatedBundle(
        answer: Int = 0,
        options: List<String> = listOf("correct", "distractor"),
        includeQuestion: Boolean = true,
    ): String {
        val question = if (includeQuestion) {
            """"questions":[{"question":"Which is correct?","options":["${options[0]}","${options[1]}"],"answer":$answer,"explanation":"The source supports this."}]"""
        } else {
            """"questions":[]"""
        }
        return """{"format":"silicontutor-material","version":1,"title":"Generated","lessons":[{"title":"Lesson","description":"Description","content":"Content","keyPoints":["Key point"]}],$question}"""
    }

    private fun expectFailure(block: () -> Unit): Exception =
        try {
            block()
            throw AssertionError("Expected validation failure")
        } catch (error: Exception) {
            error
        }
}
