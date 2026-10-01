package dev.silicontutor.silicontutor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalVectorSearchTest {
    @Test
    fun vectorsAreStableNormalizedAndDimensionChecked() {
        val text = "Nonblocking assignments update flip-flops on a clock edge."
        val first = LocalVectorSearch.encode(text)
        val second = LocalVectorSearch.encode(text)

        assertEquals(384 * Float.SIZE_BYTES, first.size)
        assertTrue(first.contentEquals(second))
        assertEquals(1f, LocalVectorSearch.cosine(first, second), 0.0001f)
        assertEquals(0f, LocalVectorSearch.cosine(first, ByteArray(8)), 0f)
    }

    @Test
    fun featureSimilarityRanksRelatedRtlTextAboveUnrelatedText() {
        val query = LocalVectorSearch.encode("nonblocking assignment flip flop clock edge")
        val related = LocalVectorSearch.encode(
            "A nonblocking assignment updates register state at the active clock edge.",
        )
        val unrelated = LocalVectorSearch.encode(
            "Photosynthesis converts sunlight into chemical energy in plant leaves.",
        )

        assertTrue(LocalVectorSearch.cosine(query, related) >
            LocalVectorSearch.cosine(query, unrelated))
        assertTrue(
            LocalVectorSearch.keywordOverlap(
                listOf("nonblocking", "assignment", "clock"),
                "Nonblocking assignments update state at the clock edge.",
            ) > 0f,
        )
    }
}
