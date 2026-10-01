package dev.silicontutor.silicontutor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeImportParsersTest {
    @Test
    fun parsesPdfLiteralHexAndArrayTextFromFixture() {
        val parsed = parseFixture("sample.pdf", "application/pdf") as ParsedImport.Pdf
        val text = parsed.chunks.joinToString(" ")

        assertTrue(text.contains("PDF literal with (escaped) punctuation."))
        assertTrue(text.contains("Hex"))
        assertTrue(text.contains("array"))
        assertTrue(text.contains("text"))
        assertFalse(parsed.limitation.isNullOrBlank())
        assertTrue(parsed.chunks.all { it.length <= 1100 })
    }

    @Test
    fun rejectsPdfWithoutSelectableText() {
        val error = expectFailure {
            parseNativeImport("empty.pdf", "application/pdf", "%PDF-1.4\n%%EOF".toByteArray())
        }
        assertTrue(error.message.orEmpty().contains("No selectable text"))
    }

    @Test
    fun usesOfflineOcrOnlyAfterFastTextExtractionFindsNothing() {
        val searchable = parsePdfImportWithOcrFallback(
            "sample.pdf",
            fixtureBytes("sample.pdf"),
        ) { throw AssertionError("OCR must not run for searchable PDF text") }
        assertTrue(searchable.extractionStatus.contains("Selectable"))

        val scannedBytes = "%PDF-1.4\n%%EOF".toByteArray()
        val ocr = parsePdfImportWithOcrFallback("scan.pdf", scannedBytes) { input ->
            assertTrue(input.contentEquals(scannedBytes))
            PdfOcrResult("Recognized scanned page text.", 1, false, false)
        }
        assertEquals("Recognized scanned page text.", ocr.chunks.single())
        assertTrue(ocr.extractionStatus.contains("Offline OCR extracted text from 1 page"))
    }

    @Test
    fun ocrFallbackReportsEmptyResultsAndCapsOutput() {
        val error = expectFailure {
            parsePdfImportWithOcrFallback("scan.pdf", "%PDF-1.4".toByteArray()) {
                PdfOcrResult("", 2, false, false)
            }
        }
        assertTrue(error.message.orEmpty().contains("OCR found no readable text"))

        val capped = parsePdfImportWithOcrFallback("scan.pdf", "%PDF-1.4".toByteArray()) {
            PdfOcrResult("x".repeat(MAX_PDF_OCR_OUTPUT_CHARS + 1), 10, true, true)
        }
        assertEquals(MAX_PDF_OCR_OUTPUT_CHARS, capped.chunks.sumOf(String::length))
        assertTrue(capped.extractionStatus.contains("first $MAX_PDF_OCR_PAGES pages"))
        assertTrue(capped.extractionStatus.contains("output was capped"))
    }

    @Test
    fun ocrPageRenderDimensionsNeverExceedConfiguredBound() {
        assertEquals(1400 to 1050, boundedPdfOcrDimensions(3200, 2400))
        assertEquals(500 to 700, boundedPdfOcrDimensions(500, 700))
        assertTrue(expectFailure { boundedPdfOcrDimensions(0, 100) }.message.orEmpty().contains("dimensions"))
    }

    @Test
    fun pagePreviewDimensionsAndPersistedImageBudgetsAreBounded() {
        assertEquals(900 to 675, boundedPdfPreviewDimensions(3200, 2400))
        assertEquals(600 to 800, boundedPdfPreviewDimensions(600, 800))
        assertTrue(MAX_PDF_PREVIEW_PAGES <= 6)
        assertTrue(MAX_PDF_PREVIEW_BYTES <= 512 * 1024)
        assertTrue(MAX_PDF_PREVIEW_TOTAL_BYTES <= 3 * 1024 * 1024)
        assertTrue(expectFailure {
            boundedPdfPreviewDimensions(800, 0)
        }.message.orEmpty().contains("dimensions"))
        validatePdfPagePreviews(
            listOf(PdfPagePreview(1, byteArrayOf(1)), PdfPagePreview(2, byteArrayOf(2))),
        )
        assertTrue(expectFailure {
            validatePdfPagePreviews(
                listOf(PdfPagePreview(1, byteArrayOf(1)), PdfPagePreview(1, byteArrayOf(2))),
            )
        }.message.orEmpty().contains("duplicate"))
        assertTrue(expectFailure {
            validatePdfPagePreviews(
                listOf(PdfPagePreview(7, byteArrayOf(1))),
            )
        }.message.orEmpty().contains("outside the supported range"))
        assertTrue(expectFailure {
            validatePdfPagePreviews(
                listOf(PdfPagePreview(1, ByteArray(MAX_PDF_PREVIEW_BYTES + 1))),
            )
        }.message.orEmpty().contains("512 KB"))
    }

    @Test
    fun chunksNormalizeAndCapExtractedText() {
        val chunks = splitTextChunks("x".repeat(1_600_000))

        assertEquals(1_500_000, chunks.sumOf(String::length))
        assertTrue(chunks.all { it.length <= 1100 })
        assertTrue(splitTextChunks(" \u0000 \n\t ").isEmpty())
    }

    @Test
    fun parsesVcdHierarchyScalarVectorAndEventLimit() {
        val parsed = parseFixture("sample.vcd", "text/x-vcd") as ParsedImport.Vcd
        val details = JSONObject(parsed.details)
        val signals = details.getJSONArray("signals")
        val events = details.getJSONArray("events")

        assertEquals("1 ns", details.getString("timescale"))
        assertEquals("top.clock", signals.getString(0))
        assertEquals("top.state [3:0]", signals.getString(1))
        assertEquals("top.clock", events.getJSONObject(0).getString("signal"))
        assertEquals("10xZ", events.getJSONObject(3).getString("value"))
        assertFalse(details.getBoolean("truncated"))

        val manyChanges = buildString {
            append("\$scope module top \$end\n\$var wire 1 ! clock \$end\n\$enddefinitions \$end\n")
            repeat(20_001) { append(if (it % 2 == 0) "0!\n" else "1!\n") }
        }
        val capped = JSONObject((parseNativeImport("many.vcd", null, manyChanges.toByteArray()) as ParsedImport.Vcd).details)
        assertEquals(20_000, capped.getJSONArray("events").length())
        assertTrue(capped.getBoolean("truncated"))
    }

    @Test
    fun formatsKnownBusValuesAsCompactHexAndKeepsUnknownsVisible() {
        assertEquals("0xA", vcdBusValueLabel("1010"))
        assertEquals("0x0", vcdBusValueLabel("0000"))
        assertEquals("10xZ", vcdBusValueLabel("10xZ"))
        assertEquals("1", vcdBusValueLabel("1"))
        assertTrue(vcdBusValueLabel("1".repeat(300)).endsWith("…"))
    }

    @Test
    fun rejectsMalformedAndOversizedVcd() {
        assertTrue(expectFailure {
            parseNativeImport("bad.vcd", null, "\$enddefinitions \$end".toByteArray())
        }.message.orEmpty().contains("declarations"))
        val negativeTime =
            "\$scope module top \$end\n\$var wire 1 ! clock \$end\n\$enddefinitions \$end\n#-1\n0!\n"
        assertTrue(expectFailure {
            parseNativeImport("negative-time.vcd", null, negativeTime.toByteArray())
        }.message.orEmpty().contains("cannot be negative"))

        assertTrue(expectFailure {
            parseNativeImport("large.vcd", null, "x".repeat(1_500_001).toByteArray())
        }.message.orEmpty().contains("1.5 MB"))
    }

    @Test
    fun parsesSafeSvgFixtureAndRejectsActiveOrExternalContent() {
        val parsed = parseFixture("sample.svg", "image/svg+xml") as ParsedImport.Design

        assertEquals("SVG", parsed.kind)
        assertTrue(parsed.summary.contains("120 × 80"))
        assertTrue(parsed.summary.contains("2 shape elements"))

        listOf(
            """<svg onload="alert(1)"></svg>""",
            """<svg><script>alert(1)</script></svg>""",
            """<svg><image href="https://example.test/a.png"/></svg>""",
        ).forEach { unsafe ->
            assertTrue(expectFailure {
                parseNativeImport("unsafe.svg", null, unsafe.toByteArray())
            }.message.orEmpty().contains("rejected"))
        }
    }

    @Test
    fun rejectsOversizedSvg() {
        val oversized = """<svg>${" ".repeat(1_500_001)}</svg>"""
        assertTrue(expectFailure {
            parseNativeImport("large.svg", null, oversized.toByteArray())
        }.message.orEmpty().contains("1.5 MB"))
    }

    @Test
    fun parsesYosysNetlistAndRejectsMalformedOrExcessiveModules() {
        val parsed = parseFixture("sample-netlist.json", "application/json") as ParsedImport.Design

        assertEquals("Yosys JSON netlist", parsed.kind)
        assertTrue(parsed.summary.contains("2 modules • 1 cells • 1 named nets"))
        assertTrue(parsed.summary.contains("top: 1 ports, 1 cells, 1 named nets"))

        assertTrue(expectFailure {
            parseNativeImport("broken.json", null, "{".toByteArray())
        }.message.orEmpty().contains("Invalid JSON"))
        assertTrue(expectFailure {
            parseNativeImport("missing-modules.json", null, """{"cells":{}}""".toByteArray())
        }.message.orEmpty().contains("modules object"))

        val tooManyModules = JSONObject().put("modules", JSONObject().apply {
            repeat(501) { put("module$it", JSONObject()) }
        })
        assertTrue(expectFailure {
            parseNativeImport("large.json", null, tooManyModules.toString().toByteArray())
        }.message.orEmpty().contains("1 to 500 modules"))
    }

    @Test
    fun enforcesWholeFileImportLimit() {
        val oversized = ByteArray(25 * 1024 * 1024 + 1)
        assertTrue(expectFailure {
            parseNativeImport("too-large.vcd", null, oversized)
        }.message.orEmpty().contains("25 MB"))
    }

    private fun parseFixture(name: String, mimeType: String): ParsedImport {
        val bytes = fixtureBytes(name)
        return parseNativeImport(name, mimeType, bytes)
    }

    private fun fixtureBytes(name: String): ByteArray =
        javaClass.getResourceAsStream("/fixtures/$name")!!.use { it.readBytes() }

    private fun expectFailure(block: () -> Unit): Exception =
        try {
            block()
            throw AssertionError("Expected parser failure")
        } catch (error: Exception) {
            error
        }
}
