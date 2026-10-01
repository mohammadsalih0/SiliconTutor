package dev.silicontutor.silicontutor

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxSimulationTest {
    @Test
    fun extractsAndParsesVcdReturnedByTermux() {
        val vcd = """
            ${'$'}timescale 1 ns ${'$'}end
            ${'$'}scope module top ${'$'}end
            ${'$'}var wire 1 ! clock ${'$'}end
            ${'$'}upscope ${'$'}end
            ${'$'}enddefinitions ${'$'}end
            #0
            0!
            #10
            1!
        """.trimIndent()
        val result = parseTermuxSimulationResult(
            LocalModelResult(
                17,
                0,
                "Icarus ran successfully.\n__SILICONTUTOR_VCD_BEGIN__\n$vcd\n__SILICONTUTOR_VCD_END__",
                "",
            ),
        )

        assertNotNull(result)
        assertTrue(result?.summary.orEmpty().contains("1 ns"))
        assertTrue(result?.details.orEmpty().contains("clock"))
    }

    @Test
    fun allowsSuccessfulRunWithoutWaveformButRejectsFailedOrOversizedRun() {
        assertNull(parseTermuxSimulationResult(LocalModelResult(18, 0, "done", "")))

        assertTrue(expectFailure {
            parseTermuxSimulationResult(LocalModelResult(19, 1, "", "compile error"))
        }.message.orEmpty().contains("compile error"))

        assertTrue(expectFailure {
            parseTermuxSimulationResult(
                LocalModelResult(20, 0, "__SILICONTUTOR_VCD_TOO_LARGE__", ""),
            )
        }.message.orEmpty().contains("6 KB"))
    }

    @Test
    fun rejectsMalformedAndIncompleteVcdCallbacks() {
        assertTrue(expectFailure {
            parseTermuxSimulationResult(
                LocalModelResult(
                    21,
                    0,
                    "__SILICONTUTOR_VCD_BEGIN__\nnot a VCD\n__SILICONTUTOR_VCD_END__",
                    "",
                ),
            )
        }.message.orEmpty().contains("VCD"))

        assertTrue(expectFailure {
            parseTermuxSimulationResult(
                LocalModelResult(22, 0, "__SILICONTUTOR_VCD_BEGIN__\n", ""),
            )
        }.message.orEmpty().contains("incomplete"))
    }

    private fun expectFailure(block: () -> Unit): Exception =
        try {
            block()
            throw AssertionError("Expected simulation-result failure")
        } catch (error: Exception) {
            error
        }
}
