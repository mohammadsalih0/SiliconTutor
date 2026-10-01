#pragma once

#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <vector>

namespace silicontutor::uart {

inline constexpr uint64_t kMaxClockHz = 50'000'000;
inline constexpr uint64_t kMaxBaud = 1'000'000;
inline constexpr uint64_t kMaxCycles = 50'000'000;
inline constexpr uint64_t kMaxFirmwareBytes = 32 * 1024 * 1024;
inline constexpr size_t kMaxRtlBytes = 128 * 1024;
inline constexpr size_t kMaxSerialBytes = 64 * 1024;

inline bool valid_timing(uint64_t clock_hz, uint64_t baud) {
  return clock_hz >= 1'000 && clock_hz <= kMaxClockHz && baud >= 1'200 &&
         baud <= kMaxBaud && clock_hz >= baud * 8;
}

inline uint64_t cycles_per_bit(uint64_t clock_hz, uint64_t baud) {
  if (!valid_timing(clock_hz, baud)) {
    throw std::invalid_argument("Unsupported clock/baud ratio.");
  }
  return (clock_hz + baud / 2) / baud;
}

inline std::vector<bool> frame_bits(uint8_t byte) {
  std::vector<bool> bits;
  bits.reserve(10);
  bits.push_back(false);
  for (unsigned bit = 0; bit < 8; ++bit) bits.push_back((byte >> bit) & 1);
  bits.push_back(true);
  return bits;
}

inline bool decode_frame(const std::vector<bool>& bits, uint8_t* byte) {
  if (!byte || bits.size() != 10 || bits.front() || !bits.back()) return false;
  uint8_t value = 0;
  for (unsigned bit = 0; bit < 8; ++bit) {
    if (bits[bit + 1]) value |= static_cast<uint8_t>(1u << bit);
  }
  *byte = value;
  return true;
}

inline std::vector<std::string> qemu_arguments(unsigned port,
                                                const std::string& firmware) {
  return {"qemu-system-riscv64",
          "-machine",
          "virt",
          "-m",
          "128M",
          "-bios",
          "none",
          "-display",
          "none",
          "-monitor",
          "none",
          "-serial",
          "tcp:127.0.0.1:" + std::to_string(port) + ",server=on,wait=on",
          "-no-reboot",
          "-kernel",
          firmware};
}

inline std::vector<std::string> verilator_arguments(
    const std::string& executable, const std::string& work_directory,
    const std::string& rtl, const std::string& harness) {
  return {executable, "--cc", "--exe", "--build", "--top-module", "top",
          "--Mdir", work_directory, rtl, harness, "--build-jobs", "2",
          "-CFLAGS", "-std=c++20"};
}

}  // namespace silicontutor::uart
