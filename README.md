# SiliconTutor

SiliconTutor is an offline-first learning app for SystemVerilog, digital logic,
and RTL design. The primary app now uses native interfaces: Jetpack Compose on
Android and GTK4/libadwaita on Linux.

## Linux

Install CMake, a C++20 compiler, GTK 4.10+, libadwaita 1.4+, SQLite, and
JSON-GLib and librsvg development packages. On Debian/Ubuntu:

```bash
sudo apt install build-essential cmake pkg-config libgtk-4-dev \
  libadwaita-1-dev libsqlite3-dev libjson-glib-dev librsvg2-dev \
  poppler-utils tesseract-ocr
cmake -S linux -B build-native/linux
cmake --build build-native/linux
./build-native/linux/silicontutor
```

Install the app, desktop entry, and icon with:

```bash
cmake --install build-native/linux --prefix ~/.local
```

The app stores its local database under the platform's user data directory.
The built-in course contains 30 units—10 each at Beginner, Intermediate, and
Expert level. Every unit includes a substantial chapter, SystemVerilog example,
key points, a self-checking testbench or verification exercise, and at least
20 unit-specific multiple-choice review questions (600 questions total).
Selecting a unit's review action filters the Review screen to that unit.
Lesson details can send the example and testbench to RTL Lab; the simulation
result text is shown even when the test does not produce a waveform.
The JSON source curriculum is shared by Linux and Android and is installed with
the Linux app. Advanced SVA, constrained-random, and UVM exercises identify
their required simulator/verification environment; basic runnable testbenches
are self-checking.
The Linux window uses a single shared top bar and a bottom status area.
Curriculum lessons and review questions open as full-size in-app pages with
slide transitions and an in-page return action rather than modal popups.
Lesson pages include previous/next navigation through the built-in curriculum.
The RTL Lab uses a resizable editor/control split, with the tool controls kept
in a narrow independently scrollable pane. The RTL Lab provides a source
editor, lightweight structural checks, tool
detection, Icarus Verilog compile/run, and Yosys JSON synthesis. If `netlistsvg`
is installed, Yosys output is also rendered to an SVG in the reported work
directory with a pannable/zoomable in-app viewer. Linux simulation is bounded
by a 20-second process timeout. The RTL Lab can also run a selected RISC-V ELF
under QEMU's `virt` machine with a 90-second runtime limit and capped serial
output. Do not run untrusted firmware. This is standalone emulation, not
Verilator co-simulation.

The adjacent **Run UART QEMU / Verilator co-simulation** action is a separate,
UART-only workflow. Edit the source as `module top(input logic clk, rst_n,
uart_rx, output logic uart_tx); ... endmodule`, select a RISC-V ELF with the
file chooser, and set the RTL clock and UART baud. Verilator builds the edited
top through its C++ backend; a local raw TCP connection to QEMU `virt`'s
16550 serial device carries bytes which the simulator converts to/from
8-N-1 UART pin transitions. It does not enable arbitrary DPI or other device
models. Install `verilator` and `qemu-system-riscv64` to use this optional
action; the Linux install target includes the co-simulation helper and its
simulation harness.

Co-simulation bounds: firmware ELF 1 byte–32 MiB; edited RTL 1 byte–128 KiB;
RTL clock 1 kHz–50 MHz; baud 1,200–1,000,000; clock at least 8 times baud;
50 million simulation cycles; a 512 MiB private workspace; 45 seconds for
Verilator compilation; 75 seconds for simulation (with an 80-second helper
watchdog) and 135 seconds for the app job; 64 KiB of guest serial output and
16 KiB of compiler diagnostics. The UART bit period is rounded to the nearest
whole RTL cycle. Temporary workspace files are created under `XDG_RUNTIME_DIR`
and removed at completion. Firmware runs as a host QEMU
process: do not select untrusted guest code. This is bounded, but it is not a
security sandbox or a claim that guest firmware is safe.

Run the native PDF/MCQ storage tests with:

```bash
cmake --build build-native/linux --target silicontutor-data-tests
ctest --test-dir build-native/linux --output-on-failure
```

## Android

Build a debug APK with the Gradle wrapper:

```bash
cd android
./gradlew assembleDebug
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.
The bundled offline Latin OCR model increases the debug APK to about 53 MB.
Android provides native Compose tabs for the dashboard, curriculum, review,
RTL Lab, Design Guide, Materials, and file viewers. RTL simulation is handed
off to Termux when it is installed and configured with Icarus Verilog and
command-run access. In RTL Lab, compile and run the edited `top` module and
testbench; each Icarus compile and simulation process is limited to 20 seconds.
If the testbench writes `dump.vcd`, SiliconTutor imports the parsed waveform
automatically when it is no larger than 6 KiB. RTL input is limited to 64 KiB,
and the app waits up to 90 seconds for Termux's result callback. This executes
user RTL under the Termux account, not in a sandbox; only run source you trust.

## Material bundles

Android and Linux import the version-1 `.stmaterial` UTF-8 JSON exchange format:

```json
{
  "format": "silicontutor-material",
  "version": 1,
  "title": "Example course",
  "lessons": [
    {
      "title": "Combinational logic",
      "description": "Introductory lesson",
      "content": "Combinational outputs depend on current inputs.",
      "example": "assign y = a & b;",
      "keyPoints": ["Assign every output on every path"]
    }
  ],
  "questions": [
    {
      "question": "Which assignment is commonly used for a flip-flop?",
      "options": ["=", "<="],
      "answer": 1,
      "explanation": "Non-blocking assignments model edge-triggered storage."
    }
  ]
}
```

The Android app can import bundles from the Materials tab; Linux provides the
same import workflow. Imported lessons and questions are stored locally.
Both platforms can import PDFs, split extracted text into searchable chunks,
and create offline study questions. Android's PDF reader supports common text
operators and deflate streams. Android Materials can optionally ask the
configured Termux GGUF model to generate validated `.stmaterial` lessons and
MCQs from extracted PDF chunks; generated answer keys are structurally checked
but not semantically verified. Linux uses Poppler's `pdftotext` and falls back
to `pdftoppm` plus `tesseract` when no text is extracted; install
`tesseract-ocr` to enable optional OCR. Linux PDF inputs are capped at 25 MiB;
text/OCR output is capped at 8 MiB and processing at 90 seconds per PDF.
Linux OCR is limited to the first 10
pages, a 1400-pixel maximum render dimension, 12 MB per render, 1 MB per page
of OCR output, 8 MB total extracted text, and 90 seconds per PDF. Rendered
pages use a private temporary directory under the user's runtime directory and
are removed when processing ends. Android first uses its fast PDF text parser,
then falls back to the bundled offline ML Kit Latin-script OCR model only when
no selectable text is found. Android OCR renders at most the first 10 pages,
with a 1400-pixel maximum dimension, 1.5 MB total text output, and a 90-second
deadline checked around rendering and recognition; the Android PDF renderer
cannot preempt a native page render already in progress. Its private cached PDF
copy is deleted after processing. OCR status and errors are reported during
import.

Android also stores up to six rendered full-page previews per imported PDF for
viewing in Files. Each JPEG is limited to 900 pixels on its longest side and
512 KiB, with a 3 MiB aggregate and 30-second rendering limit. These previews
show the original page (including visible diagrams and tables); they do not
extract individual figures or table data.

Linux also uses Poppler's `pdfimages` for bounded embedded-raster extraction
after the text/OCR digest is saved. It examines only pages 1–20, retains at most
12 images of at most 8 MiB each and 32 MiB total, and stops the extractor after
45 seconds. Extracted PNG/JPEG files are stored persistently in
`$XDG_DATA_HOME/silicontutor/pdf-images/` (normally
`~/.local/share/silicontutor/pdf-images/`) and shown in the Materials image
gallery; clicking a thumbnail opens a larger preview. Missing `pdfimages` or
extraction failures are reported without discarding the saved text digest.
Other Poppler image formats are not previewed/retained. This does not recover
vector artwork or table structure, and the page cap means later-page images
are not considered.
Question generation without a model is a transparent text-based cloze
heuristic, not a semantic assessment. Linux can optionally generate a question
bank with `llama-cli`; model output is schema-validated, not semantically
verified.

The Android Files tab parses VCD timelines and displays a discrete timing plot,
renders safe SVG diagrams with pinch zoom/pan, summarizes Yosys JSON netlists,
and retains local file excerpts. Linux plots scalar transitions and labeled
multi-bit VCD bus segments, with searchable signal selection, vertical and
horizontal scrolling, zoom, and expandable event details. It also renders
pan/zoom SVG schematics and can synthesize RTL with Yosys/netlistsvg.
Known bus values use compact hexadecimal labels; X/Z states remain binary.
Inline labels are omitted for segments narrower than 34 pixels, and values
wider than 256 bits mark omitted high bits in the plot label.

## Authoring material

Create a lesson and multiple-choice question directly in the Materials tab on
Android or Linux, then export a `.stmaterial` bundle for sharing.

## Migration status

Design Guide can optionally run a user-selected GGUF model. Linux invokes
`llama-cli` locally; Android calls Termux's `llama-cli` RUN_COMMAND service.
Android retrieval ranks local lesson/document chunks with persisted normalized
hashed token/character-feature vectors mixed with keyword overlap. Linux uses
the same kind of persisted hashed-feature vectors for document chunks and
combines cosine similarity with lexical overlap. These lightweight offline
indexes are not semantic ONNX embeddings. No embedding model is downloaded or
supplied. Linux simulation opens a generated VCD trace automatically.

The native builds cover the dashboard, built-in curriculum, quizzes, local
progress, material import/authoring, PDF text digestion, bounded OCR and
Linux embedded-raster previews, RTL
editing, and the viewers and tool integrations described above.
Remaining limitations include PDF figures beyond the supported Linux PNG/JPEG
embedded-image extraction (text-based table layout is retained where
available), OCR beyond the first 10 pages, non-Latin
Android OCR, semantic ONNX embeddings, semantic AI question validation,
waveform editing, and full system/device co-simulation beyond the UART-only
QEMU/Verilator bridge. Transition details retain recorded values when plot
labels are omitted. The existing VCD viewer limits remain 32 MB per file and
12,000 parsed transitions.
