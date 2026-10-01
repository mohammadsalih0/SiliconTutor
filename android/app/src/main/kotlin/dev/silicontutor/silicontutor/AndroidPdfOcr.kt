package dev.silicontutor.silicontutor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

internal data class PdfPreviewResult(
    val pages: List<PdfPagePreview>,
    val limitation: String?,
)

internal fun renderPdfPagePreviews(context: Context, bytes: ByteArray): PdfPreviewResult {
    require(bytes.size <= 25 * 1024 * 1024) { "File exceeds the 25 MB import limit." }
    val temporaryPdf = File.createTempFile("silicontutor-preview-", ".pdf", context.cacheDir)
    try {
        FileOutputStream(temporaryPdf).use { it.write(bytes) }
        val deadline = SystemClock.elapsedRealtime() + MAX_PDF_PREVIEW_MILLIS
        val previews = mutableListOf<PdfPagePreview>()
        var totalBytes = 0
        var limitation: String? = null
        ParcelFileDescriptor.open(temporaryPdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val pageCount = minOf(renderer.pageCount, MAX_PDF_PREVIEW_PAGES)
                if (renderer.pageCount > pageCount) {
                    limitation =
                        "Page previews are limited to the first $MAX_PDF_PREVIEW_PAGES of ${renderer.pageCount} PDF pages."
                }
                for (pageIndex in 0 until pageCount) {
                    require(SystemClock.elapsedRealtime() < deadline) {
                        "PDF page preview rendering exceeded the ${MAX_PDF_PREVIEW_MILLIS / 1000}-second limit."
                    }
                    renderer.openPage(pageIndex).use { page ->
                        val (width, height) = boundedPdfPreviewDimensions(page.width, page.height)
                        val bitmap = try {
                            Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
                        } catch (error: OutOfMemoryError) {
                            throw IllegalStateException(
                                "Not enough memory to preview PDF page ${pageIndex + 1}.",
                                error,
                            )
                        }
                        try {
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val output = java.io.ByteArrayOutputStream()
                            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 68, output)) {
                                "Could not encode PDF page ${pageIndex + 1} preview."
                            }
                            val image = output.toByteArray()
                            if (image.size > MAX_PDF_PREVIEW_BYTES) {
                                limitation = "A page preview exceeded the 512 KB image limit and was omitted."
                                break
                            }
                            if (totalBytes + image.size > MAX_PDF_PREVIEW_TOTAL_BYTES) {
                                limitation = "Further page previews exceeded the 3 MB total image limit."
                                break
                            }
                            totalBytes += image.size
                            previews += PdfPagePreview(pageIndex + 1, image)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        }
        return PdfPreviewResult(previews, limitation)
    } catch (error: Exception) {
        return PdfPreviewResult(
            emptyList(),
            error.message ?: "Could not render PDF page previews.",
        )
    } finally {
        temporaryPdf.delete()
    }
}

internal fun boundedPdfPreviewDimensions(width: Int, height: Int): Pair<Int, Int> {
    require(width > 0 && height > 0) { "PDF page has invalid dimensions." }
    val scale = minOf(
        1.0,
        MAX_PDF_PREVIEW_DIMENSION.toDouble() / width,
        MAX_PDF_PREVIEW_DIMENSION.toDouble() / height,
    )
    return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
}

internal const val MAX_PDF_PREVIEW_PAGES = 6
internal const val MAX_PDF_PREVIEW_DIMENSION = 900
internal const val MAX_PDF_PREVIEW_BYTES = 512 * 1024
internal const val MAX_PDF_PREVIEW_TOTAL_BYTES = 3 * 1024 * 1024
internal const val MAX_PDF_PREVIEW_MILLIS = 30_000L

internal fun recognizePdfOffline(context: Context, bytes: ByteArray): PdfOcrResult {
    require(bytes.size <= 25 * 1024 * 1024) { "File exceeds the 25 MB import limit." }
    val temporaryPdf = File.createTempFile("silicontutor-ocr-", ".pdf", context.cacheDir)
    try {
        FileOutputStream(temporaryPdf).use { it.write(bytes) }
        val deadline = SystemClock.elapsedRealtime() + MAX_PDF_OCR_MILLIS
        val output = StringBuilder()
        var pagesProcessed = 0
        var truncated = false
        var pageLimitReached = false
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            ParcelFileDescriptor.open(temporaryPdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    require(renderer.pageCount > 0) { "PDF contains no pages to OCR." }
                    val pageCount = minOf(renderer.pageCount, MAX_PDF_OCR_PAGES)
                    pageLimitReached = renderer.pageCount > pageCount
                    for (pageIndex in 0 until pageCount) {
                        val remaining = deadline - SystemClock.elapsedRealtime()
                        require(remaining > 0) {
                            "Offline PDF OCR exceeded the ${MAX_PDF_OCR_MILLIS / 1000}-second time limit after $pagesProcessed page(s)."
                        }
                        renderer.openPage(pageIndex).use { page ->
                            val (width, height) = boundedPdfOcrDimensions(page.width, page.height)
                            val bitmap = try {
                                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            } catch (error: OutOfMemoryError) {
                                throw IllegalStateException("Not enough memory to render PDF page ${pageIndex + 1} for OCR.", error)
                            }
                            try {
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val recognized = try {
                                    Tasks.await(
                                        recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                                        deadline - SystemClock.elapsedRealtime(),
                                        TimeUnit.MILLISECONDS,
                                    )
                                } catch (error: Exception) {
                                    if (SystemClock.elapsedRealtime() >= deadline) {
                                        throw IllegalStateException(
                                            "Offline PDF OCR exceeded the ${MAX_PDF_OCR_MILLIS / 1000}-second time limit after $pagesProcessed page(s).",
                                            error,
                                        )
                                    }
                                    throw IllegalStateException(
                                        "Offline OCR failed on PDF page ${pageIndex + 1}: ${error.message ?: "recognition error"}.",
                                        error,
                                    )
                                }
                                val pageText = recognized.text
                                val separatorLength = if (pageText.isNotBlank() && output.isNotEmpty()) 1 else 0
                                val remainingChars = (MAX_PDF_OCR_OUTPUT_CHARS - output.length - separatorLength).coerceAtLeast(0)
                                if (pageText.length > remainingChars) {
                                    if (separatorLength > 0 && remainingChars > 0) output.append('\n')
                                    output.append(pageText.take(remainingChars))
                                    truncated = true
                                    pagesProcessed++
                                    break
                                }
                                if (pageText.isNotBlank()) {
                                    if (separatorLength > 0) output.append('\n')
                                    output.append(pageText)
                                }
                                pagesProcessed++
                                if (output.length >= MAX_PDF_OCR_OUTPUT_CHARS) {
                                    truncated = true
                                    break
                                }
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }
            }
        } finally {
            recognizer.close()
        }
        require(SystemClock.elapsedRealtime() <= deadline) {
            "Offline PDF OCR exceeded the ${MAX_PDF_OCR_MILLIS / 1000}-second time limit after $pagesProcessed page(s)."
        }
        return PdfOcrResult(output.toString(), pagesProcessed, truncated, pageLimitReached)
    } finally {
        temporaryPdf.delete()
    }
}
