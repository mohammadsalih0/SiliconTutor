#include "Vtop.h"
#include "verilated.h"

#include "uart_protocol.hpp"

#include <arpa/inet.h>
#include <fcntl.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cerrno>
#include <charconv>
#include <chrono>
#include <cstdint>
#include <deque>
#include <iostream>
#include <string>
#include <vector>

namespace {

class UartTransmitter {
 public:
  explicit UartTransmitter(uint64_t cycles_per_bit)
      : cycles_per_bit_(cycles_per_bit) {}

  bool line() const { return bits_.empty() || bits_.front(); }

  void push(uint8_t byte) {
    if (bits_.empty()) bits_ = silicontutor::uart::frame_bits(byte);
    else queued_.push_back(byte);
  }

  bool idle() const { return bits_.empty(); }

  void tick() {
    if (bits_.empty()) return;
    if (++cycles_ < cycles_per_bit_) return;
    cycles_ = 0;
    bits_.erase(bits_.begin());
    if (bits_.empty() && !queued_.empty()) {
      bits_ = silicontutor::uart::frame_bits(queued_.front());
      queued_.pop_front();
    }
  }

 private:
  uint64_t cycles_per_bit_;
  uint64_t cycles_{};
  std::vector<bool> bits_;
  std::deque<uint8_t> queued_;
};

class UartReceiver {
 public:
  explicit UartReceiver(uint64_t cycles_per_bit)
      : cycles_per_bit_(cycles_per_bit) {}

  void tick(bool line) {
    if (state_ == State::Idle) {
      if (!line) {
        state_ = State::Start;
        countdown_ = cycles_per_bit_ / 2;
      }
      return;
    }
    if (countdown_ > 0) {
      --countdown_;
      return;
    }
    if (state_ == State::Start) {
      if (line) {
        state_ = State::Idle;
        return;
      }
      state_ = State::Data;
      bit_ = 0;
      value_ = 0;
      countdown_ = cycles_per_bit_ - 1;
      return;
    }
    if (state_ == State::Data) {
      if (line) value_ |= static_cast<uint8_t>(1u << bit_);
      if (++bit_ == 8) state_ = State::Stop;
      countdown_ = cycles_per_bit_ - 1;
      return;
    }
    if (line) received_.push_back(value_);
    state_ = State::Idle;
  }

  bool has_byte() const { return !received_.empty(); }
  uint8_t pop_byte() {
    const uint8_t byte = received_.front();
    received_.pop_front();
    return byte;
  }

 private:
  enum class State { Idle, Start, Data, Stop };
  uint64_t cycles_per_bit_;
  uint64_t countdown_{};
  unsigned bit_{};
  uint8_t value_{};
  State state_{State::Idle};
  std::deque<uint8_t> received_;
};

bool parse_number(const char* text, uint64_t* value) {
  if (!text || !*text) return false;
  const std::string input(text);
  const auto parsed =
      std::from_chars(input.data(), input.data() + input.size(), *value);
  return parsed.ec == std::errc{} && parsed.ptr == input.data() + input.size();
}

}  // namespace

int main(int argc, char** argv) {
  if (argc != 5) {
    std::cerr << "Usage: uart-sim <port> <clock-hz> <baud> <max-cycles>\n";
    return 2;
  }
  uint64_t port = 0, clock_hz = 0, baud = 0, max_cycles = 0;
  if (!parse_number(argv[1], &port) || port == 0 || port > 65535 ||
      !parse_number(argv[2], &clock_hz) || !parse_number(argv[3], &baud) ||
      !parse_number(argv[4], &max_cycles) ||
      !silicontutor::uart::valid_timing(clock_hz, baud) || max_cycles == 0 ||
      max_cycles > silicontutor::uart::kMaxCycles) {
    std::cerr << "Invalid UART simulation settings.\n";
    return 2;
  }
  const uint64_t bit_cycles = silicontutor::uart::cycles_per_bit(clock_hz, baud);
  const auto start = std::chrono::steady_clock::now();
  int socket_fd = -1;
  for (unsigned attempt = 0; attempt < 100; ++attempt) {
    socket_fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (socket_fd < 0) break;
    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_port = htons(static_cast<uint16_t>(port));
    inet_pton(AF_INET, "127.0.0.1", &address.sin_addr);
    if (connect(socket_fd, reinterpret_cast<sockaddr*>(&address),
                sizeof(address)) == 0) {
      break;
    }
    close(socket_fd);
    socket_fd = -1;
    usleep(100'000);
  }
  if (socket_fd < 0) {
    std::cerr << "Could not connect to QEMU's local UART TCP socket.\n";
    return 1;
  }
  const int flags = fcntl(socket_fd, F_GETFL, 0);
  fcntl(socket_fd, F_SETFL, flags | O_NONBLOCK);

  Verilated::commandArgs(argc, argv);
  Vtop top;
  top.clk = 0;
  top.rst_n = 0;
  top.uart_rx = 1;
  for (unsigned i = 0; i < 8; ++i) {
    top.clk = 0;
    top.eval();
    top.clk = 1;
    top.eval();
  }
  top.rst_n = 1;
  UartTransmitter transmitter(bit_cycles);
  UartReceiver receiver(bit_cycles);
  std::deque<uint8_t> incoming;
  std::string guest_output;
  std::string guest_pending;
  bool output_exceeded = false;
  uint8_t input_buffer[256];
  uint64_t cycles_run = 0;
  bool socket_closed = false;

  for (uint64_t cycle = 0; cycle < max_cycles; ++cycle) {
    cycles_run = cycle + 1;
    if ((cycle & 1023) == 0) {
      pollfd descriptor{socket_fd, POLLIN, 0};
      if (poll(&descriptor, 1, 0) > 0) {
        const ssize_t count =
            recv(socket_fd, input_buffer, sizeof(input_buffer), 0);
        if (count == 0) {
          socket_closed = true;
          break;
        }
        if (count > 0) {
          if (incoming.size() + static_cast<size_t>(count) >
              silicontutor::uart::kMaxSerialBytes) {
            std::cerr << "UART input exceeded the 64 KB limit.\n";
            close(socket_fd);
            return 1;
          }
          for (ssize_t i = 0; i < count; ++i) incoming.push_back(input_buffer[i]);
        }
      }
    }
    if (transmitter.idle() && !incoming.empty()) {
      transmitter.push(incoming.front());
      incoming.pop_front();
    }
    top.clk = 0;
    top.uart_rx = transmitter.line();
    top.eval();
    top.clk = 1;
    top.eval();
    receiver.tick(top.uart_tx);
    transmitter.tick();
    while (receiver.has_byte()) {
      const char byte = static_cast<char>(receiver.pop_byte());
      if (guest_output.size() == silicontutor::uart::kMaxSerialBytes) {
        output_exceeded = true;
        break;
      }
      guest_output.push_back(byte);
      guest_pending.push_back(byte);
    }
    if (output_exceeded) break;
    if (!guest_pending.empty()) {
      const ssize_t sent = send(socket_fd, guest_pending.data(),
                                guest_pending.size(), MSG_NOSIGNAL);
      if (sent > 0) guest_pending.erase(0, static_cast<size_t>(sent));
      else if (sent < 0 && errno != EAGAIN && errno != EWOULDBLOCK &&
               errno != EINTR) {
        break;
      }
    }
    if ((cycle & 65535) == 0 &&
        std::chrono::steady_clock::now() - start > std::chrono::seconds(75)) {
      std::cerr << "Simulation stopped at the 75-second wall-time limit.\n";
      close(socket_fd);
      return 124;
    }
  }
  close(socket_fd);
  top.final();
  if (output_exceeded) {
    std::cerr << "Guest UART output exceeded the 64 KB limit.\n";
    return 1;
  }
  if (socket_closed) {
    std::cerr << "QEMU closed the UART TCP connection.\n";
    return 1;
  }
  std::cout << "Simulated " << cycles_run << " cycles at " << clock_hz
            << " Hz; UART " << baud << " baud, "
            << guest_output.size() << " guest serial bytes.\n";
  if (!guest_output.empty()) {
    std::cout << "\nGuest UART output:\n";
    for (unsigned char byte : guest_output) {
      if ((byte >= 0x20 && byte <= 0x7e) || byte == '\n' || byte == '\r' ||
          byte == '\t') {
        std::cout << static_cast<char>(byte);
      } else {
        std::cout << '.';
      }
    }
    if (guest_output.back() != '\n') std::cout << '\n';
  }
  return 0;
}
