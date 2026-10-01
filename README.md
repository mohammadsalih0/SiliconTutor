# SiliconTutor

SiliconTutor is an offline-first study app for digital logic, SystemVerilog,
and RTL hardware design. It provides native interfaces for Linux and Android.

| Platform | UI | Main capabilities |
| --- | --- | --- |
| Linux | GTK 4 and libadwaita | Lessons, quizzes, material authoring, PDF digestion, RTL tools, waveform and schematic viewers |
| Android | Jetpack Compose | Lessons, quizzes, material authoring, PDF digestion, file viewers, and optional Termux simulation |

## Learning content

The shared curriculum has 30 units: 10 each for Beginner, Intermediate, and
Expert. Each unit includes a lesson chapter, a SystemVerilog example, key
points, a test or verification exercise, and at least 20 review questions
(600 questions total).

Lessons can open their example and testbench in the RTL Lab. Reviews can be
filtered to a selected unit. Advanced SVA, constrained-random, and UVM
exercises identify verification tools that are not supported by basic
Icarus Verilog.

## Linux

### Dependencies

On Debian or Ubuntu:

```bash
sudo apt install build-essential cmake pkg-config libgtk-4-dev \
  libadwaita-1-dev libsqlite3-dev libjson-glib-dev librsvg2-dev \
  poppler-utils tesseract-ocr
```

GTK 4.10+, libadwaita 1.4+, and a C++20 compiler are required.
`poppler-utils` and `tesseract-ocr` enable PDF text extraction and OCR.

### Build, run, and install

From the repository root:

```bash
cmake -S linux -B build-native/linux
cmake --build build-native/linux
./build-native/linux/silicontutor
cmake --install build-native/linux --prefix ~/.local
```

The app stores its database and imported material in the user's local data
directory.

### Tests

```bash
cmake --build build-native/linux --target silicontutor-data-tests
ctest --test-dir build-native/linux --output-on-failure
```

### RTL tools

The Linux RTL Lab supports:

- Source editing and lightweight structural checks.
- Icarus Verilog compile/run, with a 20-second process timeout.
- Yosys JSON synthesis and optional SVG generation with `netlistsvg`.
- VCD viewing and automatic display of simulation traces.
- Optional RISC-V ELF emulation using QEMU's `virt` machine.
- Optional UART-only QEMU/Verilator co-simulation.

Install `iverilog` for simulation, `yosys` for synthesis, and `netlistsvg` for
schematic output. The standalone QEMU runner requires `qemu-system-riscv64`.
The UART co-simulation action requires both `verilator` and
`qemu-system-riscv64`.

The co-simulation bridge models UART pins over a local TCP connection to
QEMU's 16550 serial device. It does not provide arbitrary DPI or general
device models. Its inputs and execution are bounded:

- Firmware: 1 byte to 32 MiB; RTL: 1 byte to 128 KiB.
- RTL clock: 1 kHz to 50 MHz; baud: 1,200 to 1,000,000.
- Maximum 50 million simulation cycles.
- Private 512 MiB temporary workspace.
- Compiler, simulation, and overall job watchdogs.
- Guest serial output capped at 64 KiB; compiler diagnostics capped at 16 KiB.

Firmware runs as a host QEMU process. Temporary workspace files are removed
when the job finishes. **These limits do not make guest firmware safe or
provide a security sandbox. Only run firmware and RTL you trust.**

## Android

Build a debug APK from the repository root:

```bash
cd android
./gradlew assembleDebug
```

The APK is written to
`android/app/build/outputs/apk/debug/app-debug.apk`. The bundled offline
Latin OCR model makes the debug APK approximately 53 MB.

Android's RTL Lab can hand source and testbench files to Termux when Termux is
installed and configured with Icarus Verilog and command-run access. Each
compile and simulation process has a 20-second limit. If a testbench writes a
VCD file no larger than 6 KiB, SiliconTutor imports it as a waveform.

The RTL input limit is 64 KiB, and the app waits up to 90 seconds for Termux
to return a result. RTL runs under the Termux account; this is not a sandbox.
Only run source you trust.

## Study material

### Material bundles

Linux and Android import and export version-1 `.stmaterial` UTF-8 JSON
bundles. Lessons and questions are stored locally.

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

Create lessons and questions in the Materials tab, then export a bundle for
sharing or import one created in another SiliconTutor installation.

### PDF import

Both apps extract PDF text, divide it into searchable chunks, and can create
offline review questions. They can optionally use a local model to generate
material. Generated question structure is validated, but answer accuracy is
not semantically verified. Without a model, question generation uses a
text-based cloze heuristic.

| Capability | Linux | Android |
| --- | --- | --- |
| Text extraction | Poppler `pdftotext` | Built-in text parser |
| OCR fallback | Poppler `pdftoppm` and Tesseract | Bundled offline ML Kit Latin OCR |
| Page limit for OCR | First 10 pages | First 10 pages |
| PDF input limit | 25 MiB | — |
| Extracted text limit | 8 MiB | 1.5 MiB |
| Embedded-image previews | Up to 12 PNG/JPEG images, first 20 pages | Up to 6 rendered page previews |

Linux image extraction is limited to embedded PNG/JPEG raster images; it does
not extract vector artwork or table structure. Android page previews show the
whole rendered page and do not extract individual figures or table data.
Android's PDF renderer cannot interrupt a native page render already in
progress. Import progress and errors are reported in the app.

Linux can optionally use `llama-cli` for question generation. Android's
Materials feature can use a configured Termux `llama-cli` model to generate
`.stmaterial` content from extracted chunks.

### Local search and AI

The apps can use a user-provided local GGUF model:

- Linux invokes `llama-cli`.
- Android invokes Termux's `llama-cli` service.
- Retrieval ranks local material with hashed text features and keyword
  overlap.

The local search index is not an ONNX embedding model or a semantic vector
database. SiliconTutor does not download or supply a language or embedding
model.

## Supported files and viewers

| Format | Use |
| --- | --- |
| `.sv`, `.svh` | RTL source and headers |
| `.pdf` | Study material digestion and previews |
| `.stmaterial` | SiliconTutor lesson and question bundles |
| `.vcd` | Digital waveform timelines |
| `.svg` | Schematic and diagram viewing |
| Yosys JSON netlist | Synthesis summary and schematic generation |
| RISC-V ELF | Optional Linux QEMU emulation/co-simulation |
| GGUF | Optional user-supplied local language model |

The viewers render discrete VCD signal transitions and pan/zoom SVG diagrams.
Linux VCD files are limited to 32 MiB and 12,000 parsed transitions. Linux
waveforms include signal search and scrolling; multi-bit bus values use
compact hexadecimal labels where possible. Android's netlist viewer
summarizes Yosys JSON.

## Limitations

- PDF figure extraction, OCR page counts, and output sizes are bounded.
- Android OCR is limited to Latin script.
- PDF table layout may be retained in extracted text, but tables are not
  reconstructed as structured data.
- Local search uses lightweight hashed text features, not semantic ONNX
  embeddings.
- Generated quiz answers are not semantically verified.
- Waveforms are displayed, not edited.
- QEMU/Verilator integration is limited to the UART bridge; full SoC/device
  co-simulation is not implemented.

## License

SiliconTutor is licensed under the Apache License, Version 2.0. See
[LICENSE](LICENSE) for the full license text.
