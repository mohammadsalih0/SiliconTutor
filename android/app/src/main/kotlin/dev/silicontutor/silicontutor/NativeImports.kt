package dev.silicontutor.silicontutor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.Image
import com.caverock.androidsvg.SVG
import android.graphics.drawable.PictureDrawable
import android.graphics.BitmapFactory
import android.widget.ImageView
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun makeOfflineQuestions(title: String, chunks: List<String>): List<GeneratedQuestion> {
    val sentences = chunks.flatMap { chunk ->
        chunk.split(Regex("(?<=[.!?])\\s+|\\n+"))
            .map(String::trim)
            .filter { it.length in 35..260 }
    }.distinct()
    val commonWords = setOf(
        "about", "after", "also", "always", "another", "because", "before", "being", "between",
        "both", "could", "does", "each", "from", "have", "into", "more", "most", "other",
        "over", "same", "should", "some", "such", "than", "that", "their", "there", "these",
        "they", "this", "through", "under", "using", "very", "when", "where", "which", "while",
        "with", "would", "your",
    )
    val keywords = sentences.flatMap { sentence ->
        Regex("\\b[A-Za-z_][A-Za-z0-9_]*\\b").findAll(sentence)
            .map { it.value }
            .filter { it.length >= 4 && it.lowercase() !in commonWords }
            .toList()
    }.distinctBy(String::lowercase)
    if (keywords.size < 2) return emptyList()
    val keywordSet = keywords.map(String::lowercase).toSet()
    return sentences.asSequence().mapNotNull { sentence ->
        val target = Regex("\\b[A-Za-z_][A-Za-z0-9_]*\\b").findAll(sentence)
            .map { it.value }
            .filter { it.length >= 4 && it.lowercase() !in commonWords && it.lowercase() in keywordSet }
            .maxByOrNull(String::length) ?: return@mapNotNull null
        val distractors = keywords.filterNot { it.equals(target, ignoreCase = true) }.take(3)
        val choices = (listOf(target) + distractors).distinct()
        if (choices.size < 2) return@mapNotNull null
        val shuffled = choices.sortedBy { (it + sentence).hashCode() }
        GeneratedQuestion(
            "Complete this sentence from the extracted text in “$title”: ${sentence.replaceFirst(Regex("\\b${Regex.escape(target)}\\b"), "_____")}",
            shuffled,
            shuffled.indexOf(target),
            "The missing word is copied from the sentence in your imported text. This cloze exercise uses fixed local rules; no LLM, semantic verification, or external service was used.",
        )
    }.take(30).toList()
}

internal data class GeneratedQuestion(
    val question: String,
    val options: List<String>,
    val answer: Int,
    val explanation: String,
)

@Composable
internal fun FileStudyScreen(
    documents: List<ImportedDocument>,
    onImport: () -> Unit,
    onGenerate: (Long) -> String,
    onLoadChunks: (Long) -> List<TextChunk>,
    onLoadDetails: (Long) -> String,
    onLoadPreviews: (Long) -> List<PdfPagePreview>,
) {
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var chunks by remember { mutableStateOf<List<TextChunk>>(emptyList()) }
    var previews by remember { mutableStateOf<List<PdfPagePreview>>(emptyList()) }
    var details by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedId) {
        val id = selectedId ?: return@LaunchedEffect
        val content = withContext(Dispatchers.IO) {
            Triple(onLoadDetails(id), onLoadChunks(id), onLoadPreviews(id))
        }
        details = content.first
        chunks = content.second
        previews = content.third
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text("Files & study", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Import PDFs, VCD waveforms, SVG diagrams, or Yosys JSON netlists using Android's document picker.")
        OutlinedButton(onClick = onImport) { Text("Import a file") }
        if (documents.isEmpty()) Text("Nothing imported yet. Files are read via SAF and extracted content is stored in the app database.")
        documents.forEach { document ->
            Card(
                Modifier.fillMaxWidth().clickable {
                    selectedId = document.id
                    chunks = emptyList()
                    previews = emptyList()
                    details = null
                },
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(document.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(document.kind, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(document.summary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        val selected = documents.firstOrNull { it.id == selectedId }
        if (selected != null) {
            Text(selected.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (selected.kind == "PDF") {
                Text("Extracted text • ${chunks.size} persisted chunks", style = MaterialTheme.typography.titleMedium)
                if (previews.isNotEmpty()) {
                    Text(
                        "PDF page previews • first ${previews.size} rendered pages",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    previews.forEach { preview -> PdfPagePreviewCard(preview) }
                } else {
                    Text(
                        "No page previews were saved. ${details.orEmpty().take(500)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                chunks.take(20).forEach { chunk ->
                    Card(Modifier.fillMaxWidth()) {
                        Text(chunk.text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (chunks.size > 20) Text("Showing first 20 chunks. All chunks remain stored locally.")
                OutlinedButton(onClick = { details = onGenerate(selected.id) }) { Text("Generate offline study questions") }
            }
            details?.let {
                if (selected.kind == "VCD") {
                    VcdEventViewer(it)
                } else if (selected.kind == "SVG") {
                    SafeSvgViewer(it)
                } else {
                    Card(Modifier.fillMaxWidth()) {
                        val preview = it.take(60_000)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(14.dp)) {
                            Text(preview, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                            if (it.length > preview.length) Text("Showing the first 60,000 characters. Full imported data is stored locally.")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPagePreviewCard(preview: PdfPagePreview) {
    val decoded by produceState<Result<android.graphics.Bitmap>?>(initialValue = null, preview.jpeg) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                BitmapFactory.decodeByteArray(preview.jpeg, 0, preview.jpeg.size)
                    ?: error("Stored page preview is not a readable JPEG.")
            }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Page ${preview.pageNumber}", style = MaterialTheme.typography.labelLarge)
            val result = decoded
            if (result == null) {
                CircularProgressIndicator()
            } else if (result.isFailure) {
                Text("Could not decode page preview: ${result.exceptionOrNull()?.message}")
            } else {
                Image(
                    bitmap = result.getOrThrow().asImageBitmap(),
                    contentDescription = "Preview of PDF page ${preview.pageNumber}, including page figures and tables",
                    modifier = Modifier.fillMaxWidth().height(340.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

@Composable
private fun VcdEventViewer(details: String) {
        val parsed = remember(details) { JSONObject(details) }
        val signals = remember(details) {
            val array = parsed.optJSONArray("signals") ?: JSONArray()
            List(array.length()) { array.getString(it) }
        }
        val events = remember(details) {
            val array = parsed.optJSONArray("events") ?: JSONArray()
            List(array.length()) { index ->
                array.getJSONObject(index).let { VcdChange(it.optLong("time"), it.optString("signal"), it.optString("value")) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Waveform summary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${signals.size} signals • ${events.size} value changes • timescale ${parsed.optString("timescale")}")
            if (parsed.optBoolean("truncated")) Text("Event list is capped at 20,000 changes.")
            Text("Signals", style = MaterialTheme.typography.titleSmall)
            signals.take(100).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (signals.size > 100) Text("Showing first 100 declared signals.")
            VcdTimingDiagram(signals, events)
            Text("Event timeline", style = MaterialTheme.typography.titleSmall)
            events.take(300).forEach { event ->
                Text("#${event.time}  ${event.signal} = ${event.value}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
            if (events.size > 300) Text("Showing first 300 events. All parsed events are persisted.")
        }
}

@Composable
private fun VcdTimingDiagram(signals: List<String>, events: List<VcdChange>) {
        val density = LocalDensity.current
        val rowHeight = with(density) { 38.dp.toPx() }
        val labelWidth = with(density) { 138.dp.toPx() }
        var signalPage by remember(signals) { mutableIntStateOf(0) }
        var zoom by remember(signals, events) { mutableFloatStateOf(1f) }
        var pan by remember(signals, events) { mutableFloatStateOf(0f) }
        val pageCount = ((signals.size + 7) / 8).coerceAtLeast(1)
        val visiblePage = signalPage.coerceIn(0, pageCount - 1)
        val visibleSignals = remember(signals, visiblePage) {
            signals.drop(visiblePage * 8).take(8)
        }
        val selectedEvents = remember(events, visibleSignals) {
            events.filter { it.signal in visibleSignals }.take(5_000)
        }
        val waveformColor = MaterialTheme.colorScheme.primary
        val minimumTime = selectedEvents.minOfOrNull { it.time } ?: 0L
        val maximumTime = selectedEvents.maxOfOrNull { it.time } ?: minimumTime
        val totalTimeRange = (maximumTime - minimumTime).coerceAtLeast(1L).toDouble()
        val visibleTimeRange = (totalTimeRange / zoom).coerceAtLeast(1.0)
        val timeStart = minimumTime + (totalTimeRange - visibleTimeRange) * pan
        val timeEnd = timeStart + visibleTimeRange
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Timing diagram • signals ${visiblePage * 8 + 1}–${visiblePage * 8 + visibleSignals.size} of ${signals.size}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { signalPage = (visiblePage - 1).coerceAtLeast(0) },
                        enabled = visiblePage > 0,
                    ) { Text("Previous signals") }
                    OutlinedButton(
                        onClick = { signalPage = (visiblePage + 1).coerceAtMost(pageCount - 1) },
                        enabled = visiblePage < pageCount - 1,
                    ) { Text("Next signals") }
                }
                Text("Horizontal zoom • ${"%.1f".format(zoom)}×", style = MaterialTheme.typography.labelMedium)
                Slider(value = zoom, onValueChange = { zoom = it; pan = pan.coerceIn(0f, 1f) }, valueRange = 1f..8f)
                if (zoom > 1.01f) {
                    Text("Pan through the selected time window", style = MaterialTheme.typography.labelSmall)
                    Slider(value = pan, onValueChange = { pan = it })
                }
                Canvas(
                    Modifier.fillMaxWidth().height((visibleSignals.size * 38 + 32).dp),
                ) {
                    val top = 12.dp.toPx()
                    val bottom = top + visibleSignals.size * rowHeight
                    val right = size.width - 8.dp.toPx()
                    val left = labelWidth
                    for (step in 0..4) {
                        val x = left + (right - left) * step / 4f
                        drawLine(Color.LightGray, Offset(x, top), Offset(x, bottom), strokeWidth = 1.dp.toPx())
                    }
                    visibleSignals.forEachIndexed { row, signal ->
                        val center = top + row * rowHeight + rowHeight / 2
                        val high = center - rowHeight * .24f
                        val low = center + rowHeight * .24f
                        drawLine(Color.LightGray, Offset(left, center), Offset(right, center), strokeWidth = .5.dp.toPx())
                        drawIntoCanvas { canvas ->
                            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                color = android.graphics.Color.DKGRAY
                                textSize = with(density) { 11.dp.toPx() }
                            }
                            canvas.nativeCanvas.drawText(signal.take(20), 0f, center + paint.textSize / 3, paint)
                        }
                        val signalEvents = selectedEvents.filter { it.signal == signal }
                        var currentValue = "x"
                        var previousX = left
                        signalEvents.forEach { event ->
                            val x = left + ((event.time - timeStart) / (timeEnd - timeStart) * (right - left)).toFloat()
                            if (x < left || x > right) {
                                if (event.time <= timeStart) currentValue = event.value
                                return@forEach
                            }
                            val previous = currentValue
                            val vectorEvent = event.value.length > 1 || previous.length > 1
                            if (vectorEvent) {
                                val segmentLeft = previousX.coerceAtLeast(left)
                                val segmentRight = x.coerceAtMost(right)
                                if (segmentRight - segmentLeft > 2.dp.toPx()) {
                                    val rectTop = center - rowHeight * .31f
                                    val rectBottom = center + rowHeight * .31f
                                    drawLine(
                                        waveformColor,
                                        Offset(segmentLeft, rectTop + 3.dp.toPx()),
                                        Offset(segmentRight, rectTop + 3.dp.toPx()),
                                        strokeWidth = 1.5.dp.toPx(),
                                    )
                                    drawLine(
                                        waveformColor,
                                        Offset(segmentLeft, rectBottom - 3.dp.toPx()),
                                        Offset(segmentRight, rectBottom - 3.dp.toPx()),
                                        strokeWidth = 1.5.dp.toPx(),
                                    )
                                    if (segmentRight - segmentLeft > 42.dp.toPx()) {
                                        drawIntoCanvas { canvas ->
                                            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                                color = android.graphics.Color.rgb(32, 80, 132)
                                                textSize = with(density) { 10.dp.toPx() }
                                                textAlign = android.graphics.Paint.Align.CENTER
                                            }
                                            canvas.nativeCanvas.drawText(
                                                vcdBusValueLabel(previous).take(16),
                                                (segmentLeft + segmentRight) / 2,
                                                center + paint.textSize / 3,
                                                paint,
                                            )
                                        }
                                    }
                                }
                            }
                            val level = when (event.value.lowercase()) {
                                "1" -> high
                                "0" -> low
                                else -> center
                            }
                            val oldLevel = when (previous.lowercase()) {
                                "1" -> high
                                "0" -> low
                                else -> center
                            }
                            drawLine(waveformColor, Offset(previousX, oldLevel), Offset(x, oldLevel), strokeWidth = 2.dp.toPx())
                            if (x > previousX) {
                                drawLine(waveformColor, Offset(x, oldLevel), Offset(x, level), strokeWidth = 2.dp.toPx())
                            }
                            currentValue = event.value
                            previousX = x
                        }
                        val finalLevel = when (currentValue.lowercase()) {
                            "1" -> high
                            "0" -> low
                            else -> center
                        }
                        drawLine(waveformColor, Offset(previousX, finalLevel), Offset(right, finalLevel), strokeWidth = 2.dp.toPx())
                    }
                }
                Text(
                    "Scalar values use high/low levels; multi-bit values appear as labeled bus segments. The event list preserves exact values.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
}

@Composable
private fun SafeSvgViewer(source: String) {
        val drawable = remember(source) {
            runCatching { PictureDrawable(SVG.getFromString(source).renderToPicture()) }
                .getOrNull()
        }
        var zoom by remember(source) { mutableFloatStateOf(1f) }
        var panX by remember(source) { mutableFloatStateOf(0f) }
        var panY by remember(source) { mutableFloatStateOf(0f) }
        val transform = rememberTransformableState { zoomChange, panChange, _ ->
            zoom = (zoom * zoomChange).coerceIn(1f, 8f)
            panX += panChange.x
            panY += panChange.y
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Interactive schematic • pinch to zoom • drag to pan", style = MaterialTheme.typography.labelMedium)
                if (drawable == null) {
                    Text("Could not render this SVG. The imported source remains available below.")
                } else {
                    AndroidView(
                        factory = { context ->
                            ImageView(context).apply {
                                scaleType = ImageView.ScaleType.FIT_CENTER
                                adjustViewBounds = true
                                setBackgroundColor(android.graphics.Color.WHITE)
                                setImageDrawable(drawable)
                            }
                        },
                        update = { it.setImageDrawable(drawable) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                            .clipToBounds()
                            .graphicsLayer(
                                scaleX = zoom,
                                scaleY = zoom,
                                translationX = panX,
                                translationY = panY,
                            )
                            .transformable(transform),
                    )
                }
            }
        }
}
