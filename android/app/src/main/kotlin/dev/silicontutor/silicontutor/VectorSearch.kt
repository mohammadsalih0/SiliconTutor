package dev.silicontutor.silicontutor

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.sqrt

private const val VECTOR_DIMENSIONS = 384

internal object LocalVectorSearch {
    fun encode(text: String): ByteArray {
        val values = FloatArray(VECTOR_DIMENSIONS)
        val terms = Regex("[a-z0-9_]{2,}").findAll(text.lowercase(Locale.ROOT))
            .map { it.value }.toList()

        terms.forEach { term ->
            addFeature(values, term, 1f)
            if (term.length >= 4) {
                ("^$term$").windowed(3).forEach { addFeature(values, it, 0.35f) }
            }
        }
        normalize(values)

        return ByteBuffer.allocate(VECTOR_DIMENSIONS * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .also { buffer -> values.forEach(buffer::putFloat) }
            .array()
    }

    fun cosine(left: ByteArray?, right: ByteArray?): Float {
        if (left == null || right == null ||
            left.size != VECTOR_DIMENSIONS * Float.SIZE_BYTES ||
            right.size != VECTOR_DIMENSIONS * Float.SIZE_BYTES
        ) return 0f
        val leftBuffer = ByteBuffer.wrap(left).order(ByteOrder.LITTLE_ENDIAN)
        val rightBuffer = ByteBuffer.wrap(right).order(ByteOrder.LITTLE_ENDIAN)
        var dot = 0f
        repeat(VECTOR_DIMENSIONS) { dot += leftBuffer.float * rightBuffer.float }
        return dot.coerceIn(-1f, 1f)
    }

    fun keywordOverlap(queryTerms: List<String>, text: String): Float {
        if (queryTerms.isEmpty()) return 0f
        val lower = text.lowercase(Locale.ROOT)
        return queryTerms.count { it in lower }.toFloat() / queryTerms.size
    }

    private fun addFeature(vector: FloatArray, feature: String, weight: Float) {
        var hash = 0x811c9dc5.toInt()
        feature.toByteArray(Charsets.UTF_8).forEach { byte ->
            hash = (hash xor (byte.toInt() and 0xff)) * 0x01000193
        }
        val index = (hash and Int.MAX_VALUE) % VECTOR_DIMENSIONS
        val sign = if (hash ushr 31 == 0) 1f else -1f
        vector[index] += sign * weight
    }

    private fun normalize(vector: FloatArray) {
        val magnitude = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
        if (magnitude > 0f) {
            for (index in vector.indices) vector[index] /= magnitude
        }
    }
}
