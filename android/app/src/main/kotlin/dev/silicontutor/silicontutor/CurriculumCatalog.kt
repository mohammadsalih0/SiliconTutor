package dev.silicontutor.silicontutor

import android.content.Context
import org.json.JSONObject

internal data class CurriculumCatalog(
    val lessons: List<Lesson>,
) {
    val questions: List<QuizQuestion> = lessons.flatMap(Lesson::questions)

    companion object {
        private val files = listOf(
            "beginner.json" to "Beginner",
            "intermediate.json" to "Intermediate",
            "expert.json" to "Expert",
        )

        fun fromAssets(context: Context): CurriculumCatalog =
            fromJson(files.associate { (fileName, _) ->
                fileName to context.assets.open(fileName).bufferedReader(Charsets.UTF_8).use { it.readText() }
            })

        fun fromJson(documents: Map<String, String>): CurriculumCatalog {
            val lessons = files.flatMap { (fileName, expectedLevel) ->
                val source = documents[fileName]
                    ?: error("Curriculum file $fileName is missing.")
                val root = JSONObject(source)
                require(root.getString("level") == expectedLevel) {
                    "$fileName has an unexpected curriculum level."
                }
                val units = root.getJSONArray("units")
                require(units.length() == 10) {
                    "$fileName must contain exactly 10 units."
                }
                List(units.length()) { unitIndex ->
                    val unit = units.getJSONObject(unitIndex)
                    require(unit.optString("level", expectedLevel) == expectedLevel) {
                        "A unit in $fileName has an inconsistent level."
                    }
                    val unitId = unit.getString("id")
                    require(unitId.isNotBlank()) { "Curriculum unit ID cannot be blank." }
                    val questionStems = mutableSetOf<String>()
                    val questionArray = unit.getJSONArray("questions")
                    require(questionArray.length() >= 20) {
                        "$unitId must contain at least 20 review questions."
                    }
                    val questions = List(questionArray.length()) { questionIndex ->
                        val question = questionArray.getJSONObject(questionIndex)
                        val options = question.getJSONArray("options")
                        require(options.length() == 4) {
                            "Question $questionIndex in $unitId must have exactly four options."
                        }
                        val answer = question.getInt("answer")
                        require(answer in 0 until options.length()) {
                            "Question $questionIndex in $unitId has an invalid answer index."
                        }
                        val stem = question.getString("question").trim()
                        require(stem.isNotEmpty() && questionStems.add(stem.lowercase())) {
                            "$unitId contains a blank or duplicate review question."
                        }
                        require(question.getString("explanation").isNotBlank()) {
                            "$unitId has a review question without an explanation."
                        }
                        QuizQuestion(
                            question = stem,
                            options = List(options.length(), options::getString),
                            answer = answer,
                            explanation = question.getString("explanation"),
                            unitId = unitId,
                            level = expectedLevel,
                        )
                    }
                    val content = unit.getString("content")
                    require(content.trim().split(Regex("\\s+")).size >= 250) {
                        "$unitId needs a substantial lesson chapter of at least 250 words."
                    }
                    val test = unit.getJSONObject("test")
                    require(test.getString("objective").isNotBlank()) {
                        "$unitId needs a unit-test objective."
                    }
                    require(test.getString("testbench").isNotBlank()) {
                        "$unitId needs a testbench or verification exercise."
                    }
                    val keyPoints = unit.getJSONArray("keyPoints")
                    require(keyPoints.length() >= 4) {
                        "$unitId needs at least four key points."
                    }
                    Lesson(
                        title = unit.getString("title"),
                        description = unit.getString("description"),
                        content = content,
                        example = unit.getString("example"),
                        keyPoints = List(keyPoints.length(), keyPoints::getString),
                        level = expectedLevel,
                        unitId = unitId,
                        testObjective = test.getString("objective"),
                        testbench = test.getString("testbench"),
                        testNotes = test.optString("integrationAssumptions", ""),
                        questions = questions,
                    )
                }
            }
            require(lessons.map(Lesson::unitId).distinct().size == 30) {
                "Curriculum unit IDs must be unique across all levels."
            }
            require(lessons.map(Lesson::title).distinct().size == lessons.size) {
                "Curriculum unit titles must be unique."
            }
            return CurriculumCatalog(lessons)
        }
    }
}
