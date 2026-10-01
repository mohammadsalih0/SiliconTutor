package dev.silicontutor.silicontutor

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Locale

private const val TERMUX_PACKAGE = "com.termux"
private const val TERMUX_ACTION = "com.termux.RUN_COMMAND"
private const val RESULT_ACTION = "dev.silicontutor.silicontutor.LOCAL_MODEL_RESULT"
private const val RESULT_BUNDLE = "result"
private const val RESULT_STDOUT = "stdout"
private const val RESULT_STDERR = "stderr"
private const val RESULT_EXIT_CODE = "exitCode"
private const val MAX_RESULT_CHARS = 12_000
private const val SIMULATION_VCD_START = "__SILICONTUTOR_VCD_BEGIN__"
private const val SIMULATION_VCD_END = "__SILICONTUTOR_VCD_END__"

internal fun runTermuxSimulation(context: Context, source: String): Int {
    require(runCatching { context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0) }.isSuccess) {
        "Termux is not installed."
    }
    require(
        context.checkSelfPermission("com.termux.permission.RUN_COMMAND") ==
            android.content.pm.PackageManager.PERMISSION_GRANTED,
    ) {
        "Termux RUN_COMMAND permission is not granted. Enable SiliconTutor in Termux's external-app/RUN_COMMAND settings."
    }
    require(source.isNotBlank() && source.length <= 64 * 1024) {
        "SystemVerilog source must contain 1 to 65,536 characters."
    }
    val requestCode = System.currentTimeMillis().toInt()
    val callbackIntent = Intent(RESULT_ACTION)
        .setPackage(context.packageName)
        .setData(android.net.Uri.parse("silicontutor://simulation-result/$requestCode"))
        .putExtra("silicontutor.requestId", requestCode)
    val callback = PendingIntent.getBroadcast(
        context,
        requestCode,
        callbackIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val script = """
        mkdir -p "${'$'}HOME/silicontutor" &&
        rm -f "${'$'}HOME/silicontutor/dump.vcd" &&
        cat > "${'$'}HOME/silicontutor/top.sv"
        status=${'$'}?
        if [ "${'$'}status" -eq 0 ]; then
          cd "${'$'}HOME/silicontutor" &&
          timeout -k 2s 20s iverilog -g2012 -o sim.out top.sv &&
          timeout -k 2s 20s ./sim.out
          status=${'$'}?
        fi
        if [ "${'$'}status" -eq 0 ] && [ -s "${'$'}HOME/silicontutor/dump.vcd" ]; then
          bytes=${'$'}(wc -c < "${'$'}HOME/silicontutor/dump.vcd")
          if [ "${'$'}bytes" -le 6000 ]; then
            printf '\\n$SIMULATION_VCD_START\\n'
            cat "${'$'}HOME/silicontutor/dump.vcd"
            printf '\\n$SIMULATION_VCD_END\\n'
          else
            printf '\\n__SILICONTUTOR_VCD_TOO_LARGE__\\n'
          fi
        fi
        exit "${'$'}status"
    """.trimIndent()
    val command = Intent(TERMUX_ACTION).apply {
        setClassName(TERMUX_PACKAGE, "$TERMUX_PACKAGE.app.RunCommandService")
        putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
        putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", script))
        putExtra("com.termux.RUN_COMMAND_STDIN", source)
        putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", callback)
    }
    context.startService(command)
    return requestCode
}

internal fun parseTermuxSimulationResult(result: LocalModelResult): ParsedImport.Vcd? {
    require(result.exitCode == 0) {
        result.stderr.trim().take(500).ifBlank {
            "Termux/Icarus simulation failed with exit code ${result.exitCode}."
        }
    }
    require("__SILICONTUTOR_VCD_TOO_LARGE__" !in result.stdout) {
        "The generated waveform exceeds the 6 KB Termux transfer limit. Shorten the testbench or inspect the VCD directly in Termux."
    }
    val start = result.stdout.indexOf(SIMULATION_VCD_START)
    if (start < 0) return null
    val bodyStart = start + SIMULATION_VCD_START.length
    val end = result.stdout.indexOf(SIMULATION_VCD_END, bodyStart)
    require(end >= bodyStart) { "Termux returned an incomplete VCD waveform." }
    val vcd = result.stdout.substring(bodyStart, end).trim()
    require(vcd.isNotBlank() && vcd.length <= 6_000) {
        "Termux returned an empty or oversized VCD waveform."
    }
    return parseNativeImport("termux-simulation.vcd", "text/x-vcd", vcd.toByteArray()) as? ParsedImport.Vcd
        ?: error("Termux returned an invalid VCD waveform.")
}

internal data class LocalModelResult(
    val requestId: Int,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

internal fun runLocalModel(
    context: Context,
    modelPath: String,
    prompt: String,
    maxTokens: Int = 256,
): Int {
    require(runCatching { context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0) }.isSuccess) {
        "Termux is not installed."
    }
    require(
        context.checkSelfPermission("com.termux.permission.RUN_COMMAND") ==
            android.content.pm.PackageManager.PERMISSION_GRANTED,
    ) {
        "Termux RUN_COMMAND permission is not granted. Enable SiliconTutor in Termux's external-app/RUN_COMMAND settings."
    }
    val path = modelPath.trim()
    require(path.startsWith("/") && path.lowercase(Locale.ROOT).endsWith(".gguf")) {
        "Configure an absolute, Termux-readable .gguf model path before requesting local inference."
    }
    require(prompt.isNotBlank() && prompt.length <= 12_000) {
        "The local model prompt must contain 1 to 12,000 characters."
    }
    require(maxTokens in 64..2048) { "The local model output limit must be between 64 and 2,048 tokens." }

    val requestCode = System.currentTimeMillis().toInt()
    val callbackIntent = Intent(RESULT_ACTION)
        .setPackage(context.packageName)
        .setData(android.net.Uri.parse("silicontutor://llama-result/$requestCode"))
        .putExtra("silicontutor.requestId", requestCode)
    val callback = PendingIntent.getBroadcast(
        context,
        requestCode,
        callbackIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val command = Intent(TERMUX_ACTION).apply {
        setClassName(TERMUX_PACKAGE, "$TERMUX_PACKAGE.app.RunCommandService")
        putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/llama-cli")
        putExtra(
            "com.termux.RUN_COMMAND_ARGUMENTS",
            arrayOf(
                "-m",
                path,
                "-c",
                "4096",
                "-n",
                maxTokens.toString(),
                "-p",
                prompt,
                "--no-display-prompt",
            ),
        )
        putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", callback)
    }
    context.startService(command)
    return requestCode
}

@Composable
internal fun ListenForLocalModelResults(onResult: (LocalModelResult) -> Unit) {
    val context = LocalContext.current
    val latestCallback by rememberUpdatedState(onResult)
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val result = intent.getBundleExtra(RESULT_BUNDLE)
                latestCallback(
                    LocalModelResult(
                        requestId = intent.getIntExtra(
                            "silicontutor.requestId",
                            intent.data?.lastPathSegment?.toIntOrNull() ?: -1,
                        ),
                        exitCode = result?.getInt(RESULT_EXIT_CODE, -1) ?: -1,
                        stdout = result?.getString(RESULT_STDOUT).orEmpty(),
                        stderr = result?.getString(RESULT_STDERR).orEmpty(),
                    ),
                )
            }
        }
        val filter = IntentFilter(RESULT_ACTION).apply { addDataScheme("silicontutor") }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
}

@Composable
internal fun DesignGuideScreen(
    store: MaterialStore,
    modelPath: String,
    onModelPathChanged: (String) -> Unit,
    inferenceResult: LocalModelResult?,
) {
    val context = LocalContext.current
    var prompt by rememberSaveable { mutableStateOf("") }
    var answer by rememberSaveable { mutableStateOf<String?>(null) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    var running by rememberSaveable { mutableStateOf(false) }
    var activePrompt by rememberSaveable { mutableStateOf("") }
    var pendingRequestId by rememberSaveable { mutableIntStateOf(-1) }

    LaunchedEffect(pendingRequestId) {
        val requestId = pendingRequestId
        if (requestId >= 0 && running) {
            delay(180_000)
            if (running && pendingRequestId == requestId) {
                answer = localExplanation(activePrompt)
                status = "No Termux result arrived within 3 minutes. Check Termux, llama-cli, model readability, and available memory. Offline helper fallback is shown below."
                running = false
                pendingRequestId = -1
            }
        }
    }
    LaunchedEffect(inferenceResult?.requestId) {
        val result = inferenceResult
        if (result != null && result.requestId == pendingRequestId) {
            running = false
            pendingRequestId = -1
            val stdout = result.stdout.trim()
            if (result.exitCode == 0 && stdout.isNotBlank()) {
                answer = stdout.take(MAX_RESULT_CHARS)
                status = "Local llama-cli inference completed in Termux using the configured GGUF model."
            } else {
                answer = localExplanation(activePrompt)
                val reason = result.stderr.trim().take(500)
                    .ifBlank { "llama-cli returned exit code ${result.exitCode} without output." }
                status = "Local model failed: $reason Offline helper fallback is shown below."
            }
        }
    }

    fun useOfflineFallback(reason: String) {
        answer = localExplanation(prompt)
        status = "$reason Offline helper fallback is shown below."
        running = false
    }

    fun askLocalModel() {
        if (prompt.isBlank()) return
        try {
            val retrieved = store.lexicalContext(prompt)
            val fullPrompt = """
                You are an offline study assistant for digital design and RTL.
                Answer the learner's question using the retrieved context when relevant.
                Context is selected by local hashed token and character-feature vector similarity mixed with exact keyword overlap. This is not a neural or ONNX embedding model.
                If the context does not answer the question, say so and answer only from general knowledge.

                RETRIEVED CONTEXT:
                $retrieved

                LEARNER QUESTION:
                ${prompt.take(2_000)}

                Give a concise, technically careful answer. Do not claim to have compiled or simulated anything.
            """.trimIndent().take(8_000)
            val requestCode = runLocalModel(context, modelPath, fullPrompt)
            activePrompt = prompt
            pendingRequestId = requestCode
            answer = null
            status = "Running llama-cli in Termux. The model stays on-device; first inference may take a while."
            running = true
        } catch (exception: Exception) {
            useOfflineFallback("Could not start local inference: ${exception.message ?: "Termux rejected the request."}")
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Design Guide", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Ask the built-in offline helper, or optionally run a local GGUF model through Termux llama-cli.")
        OutlinedTextField(
            value = modelPath,
            onValueChange = onModelPathChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Local GGUF model path") },
            placeholder = { Text("/data/data/com.termux/files/home/models/model.gguf") },
            supportingText = {
                Text("Saved on this device. Enter an absolute path readable by Termux; install llama-cli yourself. SiliconTutor does not download models.")
            },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            singleLine = true,
        )
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("What are you working on?") },
            minLines = 3,
        )
        Button(onClick = ::askLocalModel, enabled = prompt.isNotBlank() && !running) {
            Text(if (running) "Waiting for local model…" else "Ask local GGUF model")
        }
        OutlinedButton(onClick = {
            answer = localExplanation(prompt)
            status = "Built-in offline helper used. It is rule-based and does not run a language model."
        }, enabled = prompt.isNotBlank() && !running) {
            Text("Use offline helper")
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        answer?.let {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Response", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(it, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Default))
                }
            }
        }
        Text(
            "Context uses offline hashed token/character-feature vectors plus keyword overlap across built-in/imported lessons, extracted PDF chunks, and parsed VCD signals/events. This is local vector retrieval, not semantic neural embeddings or an ONNX model. Inference requires Termux, its RUN_COMMAND integration, a readable GGUF file, and a separately installed llama-cli.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun localExplanation(prompt: String): String {
    val query = prompt.lowercase()
    return when {
        "latch" in query || "combinational" in query ->
            "Combinational logic depends only on current inputs. Assign every output on every path; incomplete assignments infer storage such as a latch. Use always_comb for procedural combinational logic."
        "nonblocking" in query || "sequential" in query || "flip" in query ->
            "Sequential logic updates state on a clock edge. Use always_ff and non-blocking assignments (<=) to model registers and avoid ordering races."
        "fsm" in query || "state machine" in query ->
            "An FSM stores its current state, computes a next state from current inputs, and defines outputs. Enumerate states and handle default/illegal cases explicitly."
        "uvm" in query || "monitor" in query || "scoreboard" in query ->
            "In UVM, a driver applies transactions, a monitor reconstructs observed transactions, and a scoreboard compares observed results with expected behavior."
        else ->
            "Start by describing the module's inputs, outputs, and state. Keep combinational next-state logic separate from clocked registers, and add a self-checking testbench for the behavior you expect."
    }
}
