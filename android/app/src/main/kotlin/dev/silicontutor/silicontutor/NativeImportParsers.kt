package dev.silicontutor.silicontutor

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.InflaterInputStream

internal data class ImportedDocument(
    val id: Long,
    val name: String,
    val kind: String,
    val summary: String,
    val details: String,
)

internal data class TextChunk(val index: Int, val text: String)

internal data class VcdChange(val time: Long, val signal: String, val value: String)

internal data class PdfPagePreview(val pageNumber: Int, val jpeg: ByteArray)

internal sealed class ParsedImport {
    data class Pdf(
        val chunks: List<String>,
        val limitation: String?,
        val extractionStatus: String,
    ) : ParsedImport()
    data class Vcd(val summary: String, val details: String) : ParsedImport()
    data class Design(val kind: String, val summary: String, val details: String) : ParsedImport()
}

internal data class PdfOcrResult(
    val text: String,
    val pagesProcessed: Int,
    val truncated: Boolean,
    val pageLimitReached: Boolean,
)

internal const val MAX_PDF_OCR_PAGES = 10
internal const val MAX_PDF_OCR_DIMENSION = 1400
internal const val MAX_PDF_OCR_OUTPUT_CHARS = 1_500_000
internal const val MAX_PDF_OCR_MILLIS = 90_000L

internal class PdfNoSelectableTextException : IllegalArgumentException(
    "No selectable text was extracted. Scanned/image-only PDFs, encrypted files, and unsupported PDF encodings may require OCR.",
)

internal fun boundedPdfOcrDimensions(width: Int, height: Int): Pair<Int, Int> {
    require(width > 0 && height > 0) { "PDF page has invalid dimensions." }
    val scale = minOf(
        1.0,
        MAX_PDF_OCR_DIMENSION.toDouble() / width,
        MAX_PDF_OCR_DIMENSION.toDouble() / height,
    )
    return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
}

internal fun validatePdfPagePreviews(previews: List<PdfPagePreview>) {
    require(previews.size <= MAX_PDF_PREVIEW_PAGES) {
        "PDF page previews exceed the $MAX_PDF_PREVIEW_PAGES-page limit."
    }
    require(previews.map { it.pageNumber }.distinct().size == previews.size) {
        "PDF page previews contain duplicate page numbers."
    }
    var totalBytes = 0L
    previews.forEach { preview ->
        require(preview.pageNumber in 1..MAX_PDF_PREVIEW_PAGES) {
            "PDF page preview number is outside the supported range."
        }
        require(preview.jpeg.size <= MAX_PDF_PREVIEW_BYTES) {
            "PDF page preview exceeds the 512 KB storage limit."
        }
        totalBytes += preview.jpeg.size
    }
    require(totalBytes <= MAX_PDF_PREVIEW_TOTAL_BYTES) {
        "PDF page previews exceed the 3 MB total image limit."
    }
}

internal fun parseNativeImport(name: String, mimeType: String?, bytes: ByteArray): ParsedImport {
    require(bytes.size <= MAX_IMPORT_BYTES) { "File exceeds the 25 MB import limit." }
    val extension = name.substringAfterLast('.', "").lowercase()
    return when {
        extension == "pdf" || mimeType == "application/pdf" -> {
            val extracted = extractPdfText(bytes)
            val chunks = splitTextChunks(extracted)
            if (chunks.isEmpty()) throw PdfNoSelectableTextException()
            ParsedImport.Pdf(
                chunks,
                "Text extraction reads PDF text operators; scanned/image-only pages, OCR, encrypted PDFs, and some font encodings are unsupported.",
                "Selectable PDF text extracted.",
            )
        }

        extension == "vcd" || mimeType == "text/x-vcd" -> parseVcd(bytes.toString(Charsets.UTF_8))
        extension == "svg" || mimeType == "image/svg+xml" -> parseSvg(name, bytes.toString(Charsets.UTF_8))
        extension in setOf("json", "netlist") || mimeType == "application/json" -> parseNetlist(name, bytes.toString(Charsets.UTF_8))
        else -> error("Unsupported file type. Choose a PDF, VCD, SVG, or Yosys JSON netlist.")
    }
}

internal fun parsePdfImportWithOcrFallback(
    name: String,
    bytes: ByteArray,
    ocr: (ByteArray) -> PdfOcrResult,
): ParsedImport.Pdf {
    try {
        return parseNativeImport(name, "application/pdf", bytes) as ParsedImport.Pdf
    } catch (error: PdfNoSelectableTextException) {
        val result = ocr(bytes)
        val chunks = splitTextChunks(result.text.take(MAX_PDF_OCR_OUTPUT_CHARS))
        require(chunks.isNotEmpty()) {
            "Offline OCR found no readable text in ${result.pagesProcessed} page(s)."
        }
        val status = buildString {
            append("Offline OCR extracted text from ${result.pagesProcessed} page(s).")
            if (result.pageLimitReached) append(" Only the first $MAX_PDF_OCR_PAGES pages were processed.")
            if (result.truncated) append(" OCR output was capped at $MAX_PDF_OCR_OUTPUT_CHARS characters.")
        }
        return ParsedImport.Pdf(
            chunks,
            "OCR uses the bundled offline Latin-script ML Kit model; scanned pages may still require review.",
            status,
        )
    }
}

private const val MAX_IMPORT_BYTES = 25 * 1024 * 1024
private const val MAX_EXTRACTED_CHARS = 1_500_000

internal fun splitTextChunks(text: String): List<String> {
    val normalized = text.replace('\u0000', ' ')
        .replace(Regex("[\\t\\x0B\\f\\r ]+"), " ")
        .replace(Regex(" *\\n *"), "\n")
        .trim()
    if (normalized.isEmpty()) return emptyList()
    val limited = normalized.take(MAX_EXTRACTED_CHARS)
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < limited.length) {
        var end = minOf(start + 1100, limited.length)
        if (end < limited.length) {
            val boundary = maxOf(
                limited.lastIndexOf('\n', end),
                limited.lastIndexOf(". ", end),
                limited.lastIndexOf(' ', end),
            )
            if (boundary > start + 500) end = boundary + 1
        }
        limited.substring(start, end).trim().takeIf(String::isNotEmpty)?.let(chunks::add)
        start = end
        while (start < limited.length && limited[start].isWhitespace()) start++
    }
    return chunks
}

internal fun vcdBusValueLabel(value: String): String {
    val bounded = value.take(256)
    if (bounded.length < 2 || bounded.any { it != '0' && it != '1' }) return bounded
    val padded = bounded.padStart(((bounded.length + 3) / 4) * 4, '0')
    val hex = padded.chunked(4).joinToString("") { nibble ->
        nibble.toInt(2).toString(16).uppercase()
    }.trimStart('0').ifEmpty { "0" }
    val truncated = value.length > bounded.length
    return "0x$hex${if (truncated) "…" else ""}"
}

private fun extractPdfText(bytes: ByteArray): String {
    val raw = bytes.toString(Charsets.ISO_8859_1)
    val streams = mutableListOf<String>()
    val streamMatcher = Regex("(?s)(<<.*?>>)\\s*stream\\r?\\n").toPattern().matcher(raw)
    while (streamMatcher.find()) {
        val end = raw.indexOf("endstream", streamMatcher.end())
        if (end < 0) continue
        val start = streamMatcher.end()
        val payload = bytes.copyOfRange(start, end - if (end > start && bytes[end - 1] == '\r'.code.toByte()) 1 else 0)
        val dictionary = streamMatcher.group(1).orEmpty()
        val content = if ("/FlateDecode" in dictionary) {
            runCatching { InflaterInputStream(ByteArrayInputStream(payload)).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_EXTRACTED_CHARS * 4) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray().toString(Charsets.ISO_8859_1)
            } }.getOrNull()
        } else if ("/Filter" !in dictionary) payload.toString(Charsets.ISO_8859_1) else null
        if (content != null) streams += content
    }
    val result = StringBuilder()
    val textPattern = Regex("""\(((?:\\.|[^\\)])*)\)\s*(?:Tj|'|")""")
    val hexPattern = Regex("""<([0-9A-Fa-f\s]+)>\s*Tj""")
    val arrayPattern = Regex("""\[((?:\\.|[^\]])*)\]\s*TJ""")
    val arrayLiteralPattern = Regex("""\(((?:\\.|[^\\)])*)\)""")
    val arrayHexPattern = Regex("""<([0-9A-Fa-f\s]+)>""")
    for (stream in streams) {
        for (match in textPattern.findAll(stream)) {
            result.append(decodePdfLiteral(match.groupValues[1])).append(' ')
            if (result.length >= MAX_EXTRACTED_CHARS) break
        }
        for (match in hexPattern.findAll(stream)) {
            result.append(decodePdfHex(match.groupValues[1])).append(' ')
            if (result.length >= MAX_EXTRACTED_CHARS) break
        }
        for (array in arrayPattern.findAll(stream)) {
            arrayLiteralPattern.findAll(array.groupValues[1]).forEach { result.append(decodePdfLiteral(it.groupValues[1])).append(' ') }
            arrayHexPattern.findAll(array.groupValues[1]).forEach { result.append(decodePdfHex(it.groupValues[1])).append(' ') }
            if (result.length >= MAX_EXTRACTED_CHARS) break
        }
        if (result.length >= MAX_EXTRACTED_CHARS) break
    }
    return result.toString().take(MAX_EXTRACTED_CHARS)
}

private fun decodePdfLiteral(value: String): String {
    val output = ByteArrayOutputStream()
    var index = 0
    while (index < value.length) {
        val character = value[index++]
        if (character != '\\' || index >= value.length) {
            output.write(character.code and 0xff)
            continue
        }
        when (val escaped = value[index++]) {
            'n' -> output.write('\n'.code)
            'r' -> output.write('\r'.code)
            't' -> output.write('\t'.code)
            'b' -> output.write('\b'.code)
            'f' -> output.write(12)
            '\n' -> Unit
            '\r' -> if (index < value.length && value[index] == '\n') index++
            in '0'..'7' -> {
                var octal = escaped.digitToInt()
                repeat(2) {
                    if (index < value.length && value[index] in '0'..'7') octal = octal * 8 + value[index++].digitToInt()
                }
                output.write(octal and 0xff)
            }
            else -> output.write(escaped.code and 0xff)
        }
    }
    return output.toByteArray().toString(Charsets.ISO_8859_1)
}

private fun decodePdfHex(value: String): String {
    val digits = value.filterNot(Char::isWhitespace).let { if (it.length % 2 == 0) it else "${it}0" }
    val bytes = runCatching { ByteArray(digits.length / 2) { index -> digits.substring(index * 2, index * 2 + 2).toInt(16).toByte() } }.getOrNull()
        ?: return ""
    return if (bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()) {
        bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE)
    } else bytes.toString(Charsets.ISO_8859_1)
}

private fun parseVcd(source: String): ParsedImport.Vcd {
    require(source.length <= MAX_EXTRACTED_CHARS) { "VCD text exceeds the 1.5 MB parse limit." }
    val declarations = linkedMapOf<String, String>()
    val changes = mutableListOf<VcdChange>()
    var timescale = "unspecified"
    var time = 0L
    var inDefinitions = true
    val scope = mutableListOf<String>()
    for (rawLine in source.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        if (inDefinitions) {
            when {
                line.startsWith("\$timescale") -> timescale = line.removePrefix("\$timescale").removeSuffix("\$end").trim().ifBlank { "declared" }
                line.startsWith("\$scope ") -> line.removePrefix("\$scope").removeSuffix("\$end").trim().split(Regex("\\s+")).lastOrNull()?.let(scope::add)
                line.startsWith("\$upscope") -> if (scope.isNotEmpty()) scope.removeAt(scope.lastIndex)
                line.startsWith("\$var ") -> {
                    val fields = line.removePrefix("\$var").removeSuffix("\$end").trim().split(Regex("\\s+"))
                    if (fields.size >= 4) {
                        val reference = fields.drop(3).joinToString(" ")
                        declarations[fields[2]] = (scope + reference).joinToString(".")
                    }
                }
                line.startsWith("\$enddefinitions") -> inDefinitions = false
            }
            continue
        }
        if (line.startsWith("#")) {
            time = line.drop(1).toLongOrNull()
                ?: error("VCD contains an invalid simulation timestamp.")
            require(time >= 0) { "VCD simulation timestamps cannot be negative." }
            continue
        }
        if (line.startsWith("\$")) continue
        val vector = Regex("^([bB][01xXzZ]+)\\s+(\\S+)$").matchEntire(line)
        if (vector != null) {
            changes += VcdChange(time, declarations[vector.groupValues[2]] ?: vector.groupValues[2], vector.groupValues[1].drop(1))
        } else {
            val scalar = Regex("^([01xXzZ])(.+)$").matchEntire(line)
            if (scalar != null) changes += VcdChange(time, declarations[scalar.groupValues[2]] ?: scalar.groupValues[2], scalar.groupValues[1])
        }
        if (changes.size >= 20_000) break
    }
    require(declarations.isNotEmpty()) { "No VCD signal declarations found." }
    require(!inDefinitions) { "VCD has no \$enddefinitions marker." }
    val signals = declarations.values.toList()
    val eventJson = JSONArray().apply {
        changes.forEach { put(JSONObject().put("time", it.time).put("signal", it.signal).put("value", it.value)) }
    }
    val details = JSONObject()
        .put("timescale", timescale)
        .put("signals", JSONArray(signals))
        .put("events", eventJson)
        .put("truncated", changes.size >= 20_000)
        .toString()
    val finalTime = changes.lastOrNull()?.time ?: 0
    val summary = "${signals.size} signals • ${changes.size} value changes • $timescale • last timestamp $finalTime"
    return ParsedImport.Vcd(summary, details)
}

private fun parseSvg(name: String, source: String): ParsedImport.Design {
    require(source.length <= MAX_EXTRACTED_CHARS) { "SVG exceeds the 1.5 MB viewing limit." }
    require(Regex("<svg\\b", RegexOption.IGNORE_CASE).containsMatchIn(source)) { "File has no SVG root element." }
    require(!Regex("<\\s*(script|foreignObject|iframe|object|embed|image|audio|video)\\b", RegexOption.IGNORE_CASE).containsMatchIn(source)) {
        "SVG contains active or externally loaded elements and was rejected for safe rendering."
    }
    require(!Regex("\\bon[a-z]+\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(source)) {
        "SVG contains event-handler attributes and was rejected for safe rendering."
    }
    require(!Regex("""(?:href|src)\s*=\s*["'](?!#)[^"']+["']""", RegexOption.IGNORE_CASE).containsMatchIn(source)) {
        "SVG references external resources and was rejected for safe rendering."
    }
    require(!Regex("""url\s*\(\s*["']?(?!#|data:image/)[^)"']+""", RegexOption.IGNORE_CASE).containsMatchIn(source)) {
        "SVG references external paint resources and was rejected for safe rendering."
    }
    val width = Regex("""\bwidth\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(source)?.groupValues?.get(1)
    val height = Regex("""\bheight\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(source)?.groupValues?.get(1)
    val paths = Regex("<(?:path|line|polyline|polygon|rect|circle|ellipse)\\b", RegexOption.IGNORE_CASE).findAll(source).count()
    return ParsedImport.Design("SVG", "$name • ${width ?: "?"} × ${height ?: "?"} • $paths shape elements\nSafe local rendering; scripts and external resources are rejected.", source)
}

private fun parseNetlist(name: String, source: String): ParsedImport.Design {
    require(source.length <= MAX_EXTRACTED_CHARS) { "Netlist exceeds the 1.5 MB viewing limit." }
    val root = runCatching { JSONObject(source) }.getOrElse { throw IllegalArgumentException("Invalid JSON netlist: ${it.message}") }
    val modules = root.optJSONObject("modules") ?: error("Expected a Yosys JSON netlist with a modules object.")
    require(modules.length() in 1..500) { "Netlist must contain 1 to 500 modules." }
    val lines = mutableListOf<String>()
    var cells = 0
    var nets = 0
    for (moduleName in modules.keys()) {
        val module = modules.optJSONObject(moduleName) ?: continue
        val moduleCells = module.optJSONObject("cells")?.length() ?: 0
        val moduleNets = module.optJSONObject("netnames")?.length() ?: 0
        cells += moduleCells
        nets += moduleNets
        lines += "$moduleName: ${module.optJSONObject("ports")?.length() ?: 0} ports, $moduleCells cells, $moduleNets named nets"
    }
    val summary = "$name • ${modules.length()} modules • $cells cells • $nets named nets\n${lines.joinToString("\n")}"
    return ParsedImport.Design("Yosys JSON netlist", summary, root.toString(2))
}
