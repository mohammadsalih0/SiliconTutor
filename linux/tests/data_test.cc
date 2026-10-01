#define main silicontutor_desktop_main
#include "../runner/main.cc"
#include "../tools/uart_protocol.hpp"
#undef main

#include <iostream>
#include <stdexcept>

namespace {

void require(bool condition, const char* message) {
  if (!condition) throw std::runtime_error(message);
}

sqlite3_int64 count_rows(sqlite3* database, const char* table) {
  const std::string query = "SELECT COUNT(*) FROM " + std::string(table);
  sqlite3_stmt* statement = nullptr;
  require(sqlite3_prepare_v2(database, query.c_str(), -1, &statement, nullptr) ==
              SQLITE_OK,
          "Could not prepare row count query.");
  require(sqlite3_step(statement) == SQLITE_ROW, "Could not read row count.");
  const sqlite3_int64 count = sqlite3_column_int64(statement, 0);
  sqlite3_finalize(statement);
  return count;
}

std::string chunk_value(sqlite3* database, sqlite3_int64 id,
                        const char* column) {
  const std::string query =
      "SELECT " + std::string(column) + " FROM chunks WHERE id=?";
  sqlite3_stmt* statement = nullptr;
  require(sqlite3_prepare_v2(database, query.c_str(), -1, &statement, nullptr) ==
              SQLITE_OK,
          "Could not prepare chunk lookup.");
  sqlite3_bind_int64(statement, 1, id);
  require(sqlite3_step(statement) == SQLITE_ROW, "Could not read chunk.");
  const auto* value =
      reinterpret_cast<const char*>(sqlite3_column_text(statement, 0));
  const std::string result = value ? value : "";
  sqlite3_finalize(statement);
  return result;
}

}  // namespace

int main() {
  try {
    std::string curriculum_error;
    require(load_curriculum(&curriculum_error),
            ("Could not load curriculum: " + curriculum_error).c_str());
    require(kLessons.size() == 30 && kBuiltInQuestions.size() == 600,
            "Curriculum must contain 30 units and 600 review questions.");
    for (const std::string level : {"Beginner", "Intermediate", "Expert"}) {
      require(std::count_if(kLessons.begin(), kLessons.end(),
                            [&](const Lesson& lesson) {
                              return lesson.level == level;
                            }) == 10,
              "Each curriculum level must contain exactly 10 units.");
    }
    for (const auto& lesson : kLessons) {
      require(lesson.questions.size() >= 20 &&
                  lesson.testbench.size() > 0 &&
                  lesson.test_objective.size() > 0,
              "Every curriculum unit must include its own tests and 20 questions.");
      for (const auto& question : lesson.questions) {
        require(question.options.size() == 4 && question.answer >= 0 &&
                    question.answer < static_cast<int>(question.options.size()) &&
                    !question.explanation.empty() &&
                    question.unit_id == lesson.id &&
                    question.level == lesson.level,
                "A curriculum question has invalid options, answer, or unit metadata.");
      }
    }
    require(silicontutor::uart::valid_timing(10'000'000, 115'200),
            "UART timing should accept a 10 MHz clock at 115200 baud.");
    require(silicontutor::uart::cycles_per_bit(10'000'000, 115'200) == 87,
            "UART cycle conversion should round 86.806 clocks to 87.");
    require(silicontutor::uart::cycles_per_bit(10'500'000, 1'000'000) == 11,
            "UART cycle conversion should round an exact half-cycle upward.");
    require(!silicontutor::uart::valid_timing(900'000, 115'200),
            "UART timing must reject clocks below 8x the baud rate.");
    require(!silicontutor::uart::valid_timing(10'000'000, 0),
            "UART timing must reject a zero baud rate.");
    for (unsigned value = 0; value <= 0xff; ++value) {
      const auto frame =
          silicontutor::uart::frame_bits(static_cast<uint8_t>(value));
      uint8_t decoded = 0;
      require(frame.size() == 10 &&
                  silicontutor::uart::decode_frame(frame, &decoded) &&
                  decoded == value,
              "UART 8-N-1 serial bit conversion failed.");
    }
    const auto frame = silicontutor::uart::frame_bits(0xa5);
    uint8_t decoded = 0;
    require(!silicontutor::uart::decode_frame({}, &decoded),
            "UART receiver accepted a frame with the wrong number of bits.");
    auto invalid_frame = frame;
    invalid_frame.front() = true;
    require(!silicontutor::uart::decode_frame(invalid_frame, &decoded),
            "UART receiver accepted an invalid start bit.");
    invalid_frame = frame;
    invalid_frame.back() = false;
    require(!silicontutor::uart::decode_frame(invalid_frame, &decoded),
            "UART receiver accepted an invalid stop bit.");
    const std::string uart_firmware_path = "/firmware with spaces.elf";
    const auto uart_qemu =
        silicontutor::uart::qemu_arguments(32456, uart_firmware_path);
    require(uart_qemu.back() == uart_firmware_path &&
                uart_qemu[uart_qemu.size() - 4] ==
                    "tcp:127.0.0.1:32456,server=on,wait=on" &&
                uart_qemu[0] == "qemu-system-riscv64",
            "UART QEMU argv must keep serial endpoint and firmware separate.");
    const auto verilator_args = silicontutor::uart::verilator_arguments(
        "verilator", "/work dir/obj", "/source dir/top.sv",
        "/helper dir/harness.cpp");
    require(verilator_args[0] == "verilator" &&
                verilator_args[7] == "/work dir/obj" &&
                verilator_args[8] == "/source dir/top.sv" &&
                verilator_args[9] == "/helper dir/harness.cpp" &&
                std::find(verilator_args.begin(), verilator_args.end(),
                          "sh -c") == verilator_args.end(),
            "Verilator argv must preserve paths as individual arguments.");
    const auto image_fixture =
        std::filesystem::current_path() / "pdf-image-helper-test-data";
    std::error_code fixture_error;
    std::filesystem::remove_all(image_fixture, fixture_error);
    std::filesystem::create_directories(image_fixture);
    const auto safe_directory = safe_pdf_image_directory(
        "/home/test/a PDF with spaces.pdf", "../../notes <final>.pdf");
    require(std::filesystem::path(safe_directory).parent_path() ==
                std::filesystem::absolute(pdf_image_root()).lexically_normal(),
            "PDF image directory escaped the app data folder.");
    require(std::filesystem::path(safe_directory).filename().string().find('/') ==
                std::string::npos &&
                safe_pdf_image_directory("/home/test/a PDF with spaces.pdf",
                                         "../../notes <final>.pdf") ==
                    safe_directory,
            "PDF image directory name was not safely stable.");
    require(is_supported_pdf_image_name("image-000.png") &&
                is_supported_pdf_image_name("image-001.JpEg") &&
                !is_supported_pdf_image_name("../image-002.png") &&
                !is_supported_pdf_image_name("image-003.svg"),
            "Embedded-image filename allowlist is incorrect.");
    {
      std::ofstream(image_fixture / "image-000.png") << "raster";
      std::ofstream(image_fixture / "image-001.jpg") << "raster";
      std::ofstream(image_fixture / "image-002.svg") << "vector";
      const auto inventory =
          inspect_pdf_image_directory(image_fixture.string());
      require(!inventory.limit_exceeded && inventory.file_count == 3 &&
                  inventory.preview_paths.size() == 2,
              "Image inventory did not restrict previews to raster outputs.");
    }
    std::filesystem::remove_all(image_fixture, fixture_error);
    std::filesystem::create_directories(image_fixture);
    for (size_t i = 0; i <= kPdfImageCountLimit; ++i) {
      std::ofstream(image_fixture /
                    ("image-" + std::to_string(i) + ".png"))
          << "x";
    }
    require(inspect_pdf_image_directory(image_fixture.string()).limit_exceeded,
            "Image inventory did not enforce the image-count cap.");
    std::filesystem::remove_all(image_fixture, fixture_error);
    std::filesystem::create_directories(image_fixture);
    {
      std::ofstream oversized(image_fixture / "image-000.png");
      oversized.seekp(static_cast<std::streamoff>(kPdfImageSizeLimit));
      oversized.put('x');
    }
    require(inspect_pdf_image_directory(image_fixture.string()).limit_exceeded,
            "Image inventory did not enforce the per-image byte cap.");
    std::filesystem::remove_all(image_fixture, fixture_error);
    std::filesystem::create_directories(image_fixture);
    for (size_t i = 0; i < 4; ++i) {
      std::ofstream image(image_fixture /
                          ("image-" + std::to_string(i) + ".png"));
      image.seekp(static_cast<std::streamoff>(kPdfImageSizeLimit - 1));
      image.put('x');
    }
    std::ofstream(image_fixture / "image-004.png") << "x";
    require(inspect_pdf_image_directory(image_fixture.string()).limit_exceeded,
            "Image inventory did not enforce the aggregate byte cap.");
    std::filesystem::remove_all(image_fixture, fixture_error);

    const std::string firmware_path = "/tmp/firmware with spaces.elf";
    const auto qemu_arguments = qemu_riscv_arguments(firmware_path);
    require(qemu_arguments.front() == "qemu-system-riscv64" &&
                qemu_arguments.back() == firmware_path &&
                qemu_arguments[qemu_arguments.size() - 2] == "-kernel",
            "QEMU firmware path must remain a single argv argument.");
    require(vcd_bus_value_label("1", 8) == "0x01",
            "Known VCD bus values were not zero-extended and converted to hex.");
    require(vcd_bus_value_label("1111_0000", 8) == "0xF0",
            "VCD bus value conversion did not ignore digit separators.");
    require(vcd_bus_value_label("10XZ", 4) == "b10xz",
            "VCD unknown/high-impedance bus states were not preserved.");
    require(vcd_bus_value_label("100000001", 8) == "0x01",
            "Over-width VCD bus values were not truncated to declared width.");
    require(vcd_bus_value_label("z", 4) == "bzzzz",
            "VCD high-impedance state was not extended to bus width.");
    require(vcd_bus_value_label("1", 1) == "0x1",
            "Single-bit values did not have a stable compact representation.");
    require(vcd_bus_value_label("1", 257) == "0x…1",
            "Very wide bus labels did not mark omitted high bits.");

    AppState state;
    require(sqlite3_open(":memory:", &state.db) == SQLITE_OK,
            "Could not open in-memory SQLite database.");
    require(execute(
                state.db,
                "CREATE TABLE documents(id INTEGER PRIMARY KEY,name TEXT NOT "
                "NULL,path TEXT,imported_at INTEGER NOT NULL);"
                "CREATE TABLE chunks(id INTEGER PRIMARY KEY,document_id "
                "INTEGER NOT NULL,content TEXT NOT NULL,page INTEGER,"
                "embedding TEXT);"
                "CREATE TABLE generated_lessons(id INTEGER PRIMARY KEY,"
                "source TEXT NOT NULL,title TEXT NOT NULL,description TEXT "
                "NOT NULL,content TEXT NOT NULL,example TEXT NOT NULL,"
                "key_points_json TEXT NOT NULL,created_at INTEGER NOT NULL);"
                "CREATE TABLE generated_questions(id INTEGER PRIMARY KEY,"
                "source TEXT NOT NULL,question TEXT NOT NULL,options_json "
                "TEXT NOT NULL,answer INTEGER NOT NULL,explanation TEXT NOT "
                "NULL,created_at INTEGER NOT NULL);"
                "CREATE TABLE app_settings(key TEXT PRIMARY KEY,value TEXT "
                "NOT NULL);"),
            "Could not initialize in-memory schema.");

    PdfDigestJob pdf{&state, "/home/test/rtl-notes.pdf", "rtl-notes.pdf"};
    const std::string text =
        "A flip-flop stores state on a clock edge. "
        "Nonblocking assignments model simultaneous register updates. "
        "Combinational logic must assign every output on every path. "
        "Assertions check temporal requirements in a simulation.";
    std::string error;
    require(store_pdf_digest(&state, &pdf, text, &error),
            "PDF text digestion failed.");
    require(count_rows(state.db, "documents") == 1,
            "PDF document record was not saved.");
    require(count_rows(state.db, "chunks") >= 1,
            "PDF text chunks were not saved.");
    require(count_rows(state.db, "generated_lessons") == 1,
            "PDF study note was not created.");
    require(count_rows(state.db, "generated_questions") >= 2,
            "Offline source-check questions were not created.");
    sqlite3_stmt* vector_query = nullptr;
    require(sqlite3_prepare_v2(state.db, "SELECT embedding FROM chunks LIMIT 1",
                               -1, &vector_query, nullptr) == SQLITE_OK &&
                sqlite3_step(vector_query) == SQLITE_ROW,
            "Could not read a newly generated feature vector.");
    const auto* saved_vector =
        reinterpret_cast<const char*>(sqlite3_column_text(vector_query, 0));
    FeatureVector parsed_vector{};
    require(saved_vector && parse_features(saved_vector, &parsed_vector),
            "New text chunk did not persist a supported hashed feature vector.");
    double vector_norm = 0.0;
    for (const double value : parsed_vector) vector_norm += value * value;
    require(std::abs(vector_norm - 1.0) < 0.001,
            "Persisted hashed feature vector was not normalized.");
    sqlite3_finalize(vector_query);

    const auto stable_a = vector_text("Clock-edge registers update together.");
    const auto stable_b = vector_text("Clock-edge registers update together.");
    require(stable_a == stable_b,
            "Hashed feature vectors are not stable across repeated generation.");
    require(cosine_similarity(hashed_features("flip-flop"),
                              hashed_features("flip flop")) > 0.1,
            "Character features did not provide useful spelling overlap.");

    sqlite3_stmt* legacy_document = nullptr;
    require(sqlite3_prepare_v2(
                state.db,
                "INSERT INTO documents(name,path,imported_at) VALUES(?,?,0)",
                -1, &legacy_document, nullptr) == SQLITE_OK,
            "Could not prepare legacy document.");
    sqlite3_bind_text(legacy_document, 1, "legacy.txt", -1, SQLITE_STATIC);
    sqlite3_bind_text(legacy_document, 2, "/legacy.txt", -1, SQLITE_STATIC);
    require(sqlite3_step(legacy_document) == SQLITE_DONE,
            "Could not create legacy document.");
    sqlite3_finalize(legacy_document);
    const sqlite3_int64 legacy_document_id = sqlite3_last_insert_rowid(state.db);
    sqlite3_stmt* legacy_chunk = nullptr;
    require(sqlite3_prepare_v2(
                state.db,
                "INSERT INTO chunks(document_id,content,page,embedding) "
                "VALUES(?,?,NULL,NULL)",
                -1, &legacy_chunk, nullptr) == SQLITE_OK,
            "Could not prepare legacy chunk.");
    sqlite3_bind_int64(legacy_chunk, 1, legacy_document_id);
    sqlite3_bind_text(legacy_chunk, 2,
                      "Legacy chunk with clock-edge migration text.", -1,
                      SQLITE_STATIC);
    require(sqlite3_step(legacy_chunk) == SQLITE_DONE,
            "Could not create legacy chunk.");
    sqlite3_finalize(legacy_chunk);
    const sqlite3_int64 legacy_chunk_id = sqlite3_last_insert_rowid(state.db);
    require(sqlite3_prepare_v2(
                state.db,
                "INSERT INTO chunks(document_id,content,page,embedding) "
                "VALUES(?,?,NULL,?)",
                -1, &legacy_chunk, nullptr) == SQLITE_OK,
            "Could not prepare legacy chunk with an old vector format.");
    sqlite3_bind_int64(legacy_chunk, 1, legacy_document_id);
    sqlite3_bind_text(legacy_chunk, 2, "A chunk from a previous vector format.",
                      -1, SQLITE_STATIC);
    sqlite3_bind_text(legacy_chunk, 3, "old-vector-format", -1, SQLITE_STATIC);
    require(sqlite3_step(legacy_chunk) == SQLITE_DONE,
            "Could not create legacy chunk with an old vector format.");
    sqlite3_finalize(legacy_chunk);
    const sqlite3_int64 old_format_chunk_id =
        sqlite3_last_insert_rowid(state.db);
    backfill_chunk_vectors(state.db);
    const auto migrated_vector = chunk_value(state.db, legacy_chunk_id, "embedding");
    require(parse_features(migrated_vector, &parsed_vector),
            "Existing chunk vector was not safely backfilled.");
    require(parse_features(chunk_value(state.db, old_format_chunk_id, "embedding"),
                           &parsed_vector),
            "An incompatible stored vector was not safely migrated.");
    require(chunk_value(state.db, legacy_chunk_id, "content") ==
                "Legacy chunk with clock-edge migration text.",
            "Backfilling changed the source chunk text.");
    const auto matches = rank_guide_matches(state.db, "always_ff reset polarity");
    require(std::is_sorted(matches.begin(), matches.end(),
                           [](const GuideMatch& left, const GuideMatch& right) {
                             return left.score > right.score;
                           }),
            "Design Guide matches are not ordered by combined relevance.");
    require(std::any_of(matches.begin(), matches.end(), [](const GuideMatch& match) {
      return match.source.find("Built-in: Reliable Sequential RTL") == 0;
            }),
            "Design Guide ranking omitted relevant built-in lesson content.");
    const auto local_matches =
        rank_guide_matches(state.db, "nonblocking assignments");
    require(std::any_of(local_matches.begin(), local_matches.end(),
                        [](const GuideMatch& match) {
                          return match.source == "rtl-notes.pdf";
                        }),
            "Design Guide ranking omitted relevant local document chunks.");

    PdfDigestJob ocr_pdf{&state, "/home/test/scanned-notes.pdf",
                        "scanned-notes.pdf"};
    error.clear();
    require(store_pdf_digest(
                &state, &ocr_pdf,
                "A scanned lesson explains that a flip-flop stores state on each "
                "active clock transition.",
                &error),
            "OCR text could not use the existing PDF digest workflow.");
    require(count_rows(state.db, "documents") == 3 &&
                count_rows(state.db, "chunks") >= 2 &&
                count_rows(state.db, "generated_lessons") == 2,
            "OCR text was not persisted as a normal searchable PDF digest.");

    size_t generated_count = 0;
    error.clear();
    const std::string model_questions =
        R"({"questions":[{"question":"What models a register update?","options":["Nonblocking assignment","Continuous assignment"],"answer":0,"explanation":"The source describes nonblocking assignments for registers."}]})";
    require(store_llm_questions(&state, "pdf:rtl-notes.pdf", model_questions,
                               &generated_count, &error),
            "Valid local-model MCQ JSON was rejected.");
    require(generated_count == 1, "Incorrect generated-question count.");
    require(count_rows(state.db, "generated_questions") >= 3,
            "Local-model question was not saved.");

    const auto before_invalid = count_rows(state.db, "generated_questions");
    error.clear();
    require(!store_llm_questions(
                &state, "pdf:rtl-notes.pdf",
                R"({"questions":[{"question":"Bad answer index","options":["A","B"],"answer":2,"explanation":"Invalid."}]})",
                &generated_count, &error),
            "Invalid answer index should be rejected.");
    require(count_rows(state.db, "generated_questions") == before_invalid,
            "Invalid model output partially modified the question bank.");

    error.clear();
    require(!store_pdf_digest(&state, &pdf, " \n ", &error),
            "Empty PDF extraction should be rejected.");
    require(count_rows(state.db, "documents") == 3,
            "Empty PDF extraction should not create a document.");
    sqlite3_close(state.db);
    std::cout << "Native PDF digest, MCQ persistence, and VCD bus-value checks passed.\n";
    return 0;
  } catch (const std::exception& error) {
    std::cerr << error.what() << '\n';
    return 1;
  }
}
