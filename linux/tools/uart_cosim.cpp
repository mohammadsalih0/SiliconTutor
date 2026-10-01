#include "uart_protocol.hpp"

#include <arpa/inet.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <unistd.h>

#include <array>
#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <iostream>
#include <string>
#include <vector>

namespace {

using Clock = std::chrono::steady_clock;
constexpr auto kCompileTimeout = std::chrono::seconds(45);
constexpr auto kSimTimeout = std::chrono::seconds(80);
constexpr size_t kMaxCommandOutput = 16 * 1024;
constexpr size_t kMaxSimOutput = silicontutor::uart::kMaxSerialBytes + 4096;
constexpr uint64_t kMaxWorkspaceBytes = 512ull * 1024 * 1024;

struct Options {
  std::string rtl;
  std::string firmware;
  uint64_t clock_hz = 10'000'000;
  uint64_t baud = 115'200;
  uint64_t cycles = 50'000'000;
};

class Workspace {
 public:
  explicit Workspace(const std::string& path) : path_(path) {}
  ~Workspace() {
    std::error_code ignored;
    std::filesystem::remove_all(path_, ignored);
  }
  const std::string& path() const { return path_; }

 private:
  std::string path_;
};

bool parse_u64(const char* text, uint64_t* output) {
  if (!text || !*text) return false;
  try {
    size_t used = 0;
    const auto value = std::stoull(text, &used);
    if (used != std::strlen(text)) return false;
    *output = value;
    return true;
  } catch (...) {
    return false;
  }
}

bool parse_options(int argc, char** argv, Options* options) {
  for (int i = 1; i < argc; ++i) {
    const std::string key(argv[i]);
    if (i + 1 >= argc) return false;
    const char* value = argv[++i];
    if (key == "--source") options->rtl = value;
    else if (key == "--firmware") options->firmware = value;
    else if (key == "--clock-hz") {
      if (!parse_u64(value, &options->clock_hz)) return false;
    } else if (key == "--baud") {
      if (!parse_u64(value, &options->baud)) return false;
    } else if (key == "--cycles") {
      if (!parse_u64(value, &options->cycles)) return false;
    } else {
      return false;
    }
  }
  return !options->rtl.empty() && !options->firmware.empty() &&
         silicontutor::uart::valid_timing(options->clock_hz, options->baud) &&
         options->cycles > 0 &&
         options->cycles <= silicontutor::uart::kMaxCycles;
}

std::string find_executable(const std::string& program) {
  const char* env_path = std::getenv("PATH");
  if (!env_path) return {};
  const std::string path(env_path);
  size_t begin = 0;
  while (begin <= path.size()) {
    const size_t end = path.find(':', begin);
    const std::string directory =
        path.substr(begin, end == std::string::npos ? end : end - begin);
    const auto candidate =
        std::filesystem::path(directory.empty() ? "." : directory) / program;
    if (access(candidate.c_str(), X_OK) == 0) return candidate.string();
    if (end == std::string::npos) break;
    begin = end + 1;
  }
  return {};
}

uint64_t workspace_size(const std::string& path);

bool run_command(const std::vector<std::string>& arguments,
                 const std::string& working_directory,
                 std::chrono::seconds timeout, size_t output_limit,
                 std::string* output,
                 int* exit_code) {
  int pipe_fds[2];
  if (pipe2(pipe_fds, O_CLOEXEC | O_NONBLOCK) != 0) {
    *output = std::string("Could not create command output pipe: ") +
              std::strerror(errno);
    return false;
  }
  const pid_t child = fork();
  if (child < 0) {
    close(pipe_fds[0]);
    close(pipe_fds[1]);
    *output = std::string("Could not start command: ") + std::strerror(errno);
    return false;
  }
  if (child == 0) {
    setpgid(0, 0);
    close(pipe_fds[0]);
    fcntl(pipe_fds[1], F_SETFL, fcntl(pipe_fds[1], F_GETFL) & ~O_NONBLOCK);
    dup2(pipe_fds[1], STDOUT_FILENO);
    dup2(pipe_fds[1], STDERR_FILENO);
    close(pipe_fds[1]);
    if (chdir(working_directory.c_str()) != 0) _exit(126);
    std::vector<char*> argv;
    argv.reserve(arguments.size() + 1);
    for (const auto& argument : arguments)
      argv.push_back(const_cast<char*>(argument.c_str()));
    argv.push_back(nullptr);
    execvp(argv.front(), argv.data());
    const std::string message =
        std::string("Could not execute ") + arguments.front() + ": " +
        std::strerror(errno) + "\n";
    write(STDERR_FILENO, message.data(), message.size());
    _exit(127);
  }
  setpgid(child, child);
  close(pipe_fds[1]);
  const auto deadline = Clock::now() + timeout;
  bool exited = false;
  bool timed_out = false;
  bool workspace_exceeded = false;
  int status = 0;
  auto next_workspace_check = Clock::now();
  std::array<char, 4096> buffer{};
  while (!exited) {
    while (true) {
      const ssize_t count =
          read(pipe_fds[0], buffer.data(), buffer.size());
      if (count <= 0) break;
      const size_t room =
          output->size() < output_limit ? output_limit - output->size() : 0;
      output->append(buffer.data(), std::min(static_cast<size_t>(count), room));
    }
    const pid_t result = waitpid(child, &status, WNOHANG);
    exited = result == child;
    if (exited) break;
    if (Clock::now() >= next_workspace_check) {
      next_workspace_check = Clock::now() + std::chrono::seconds(1);
      if (workspace_size(working_directory) > kMaxWorkspaceBytes) {
        workspace_exceeded = true;
        kill(-child, SIGKILL);
        waitpid(child, &status, 0);
        exited = true;
        break;
      }
    }
    if (Clock::now() >= deadline) {
      timed_out = true;
      kill(-child, SIGKILL);
      waitpid(child, &status, 0);
      exited = true;
      break;
    }
    pollfd fd{pipe_fds[0], POLLIN, 0};
    poll(&fd, 1, 25);
  }
  while (true) {
    const ssize_t count = read(pipe_fds[0], buffer.data(), buffer.size());
    if (count <= 0) break;
    const size_t room =
        output->size() < output_limit ? output_limit - output->size() : 0;
    output->append(buffer.data(), std::min(static_cast<size_t>(count), room));
  }
  close(pipe_fds[0]);
  if (workspace_exceeded) {
    *output += "\nTemporary workspace exceeded the 512 MiB limit.";
    *exit_code = 1;
  } else if (timed_out) {
    *output += "\nCommand exceeded its " + std::to_string(timeout.count()) +
               "-second timeout.";
    *exit_code = 124;
  } else {
    *exit_code = WIFEXITED(status) ? WEXITSTATUS(status) : 128;
  }
  return *exit_code == 0;
}

bool file_size_in_range(const std::string& path, uint64_t limit,
                        uint64_t* size_out) {
  std::error_code error;
  const auto status = std::filesystem::status(path, error);
  if (error || !std::filesystem::is_regular_file(status)) return false;
  const uint64_t size = std::filesystem::file_size(path, error);
  if (error || size == 0 || size > limit) return false;
  *size_out = size;
  return true;
}

uint64_t workspace_size(const std::string& path) {
  uint64_t total = 0;
  std::error_code error;
  for (std::filesystem::recursive_directory_iterator iterator(path, error), end;
       iterator != end && !error; iterator.increment(error)) {
    if (!iterator->is_regular_file(error) || error) continue;
    const uint64_t size = iterator->file_size(error);
    if (error || size > kMaxWorkspaceBytes - total) return kMaxWorkspaceBytes + 1;
    total += size;
  }
  return error ? kMaxWorkspaceBytes + 1 : total;
}

bool is_elf_file(const std::string& path) {
  std::array<unsigned char, 4> magic{};
  const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
  if (fd < 0) return false;
  const ssize_t count = read(fd, magic.data(), magic.size());
  close(fd);
  return count == static_cast<ssize_t>(magic.size()) && magic[0] == 0x7f &&
         magic[1] == 'E' && magic[2] == 'L' && magic[3] == 'F';
}

int listen_port() {
  const int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
  if (fd < 0) return -1;
  sockaddr_in address{};
  address.sin_family = AF_INET;
  address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  address.sin_port = 0;
  if (bind(fd, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0) {
    close(fd);
    return -1;
  }
  socklen_t length = sizeof(address);
  if (getsockname(fd, reinterpret_cast<sockaddr*>(&address), &length) != 0) {
    close(fd);
    return -1;
  }
  const int port = ntohs(address.sin_port);
  close(fd);
  return port;
}

pid_t start_qemu(const std::vector<std::string>& arguments,
                 const std::string& working_directory) {
  const pid_t child = fork();
  if (child != 0) return child;
  setpgid(0, 0);
  const int null_fd = open("/dev/null", O_WRONLY | O_CLOEXEC);
  if (null_fd >= 0) {
    dup2(null_fd, STDOUT_FILENO);
    dup2(null_fd, STDERR_FILENO);
    close(null_fd);
  }
  if (chdir(working_directory.c_str()) != 0) _exit(126);
  std::vector<char*> argv;
  argv.reserve(arguments.size() + 1);
  for (const auto& argument : arguments)
    argv.push_back(const_cast<char*>(argument.c_str()));
  argv.push_back(nullptr);
  execvp(argv.front(), argv.data());
  _exit(127);
}

void stop_process(pid_t process) {
  if (process <= 0) return;
  if (waitpid(process, nullptr, WNOHANG) == process) return;
  kill(process, SIGTERM);
  const auto deadline = Clock::now() + std::chrono::seconds(2);
  while (Clock::now() < deadline) {
    if (waitpid(process, nullptr, WNOHANG) == process) return;
    usleep(20'000);
  }
  kill(-process, SIGKILL);
  waitpid(process, nullptr, 0);
}

std::string harness_path(const char* executable_path) {
  std::array<char, 4096> resolved{};
  const ssize_t length =
      readlink("/proc/self/exe", resolved.data(), resolved.size() - 1);
  const auto executable_directory =
      (length > 0 ? std::filesystem::path(
                        std::string(resolved.data(), length))
                  : std::filesystem::canonical(executable_path))
          .parent_path();
  const auto installed =
      (executable_directory / "../share/silicontutor/uart_sim_harness.cpp")
          .lexically_normal();
  if (std::filesystem::is_regular_file(installed)) return installed.string();
  const char* build_path = SILICONTUTOR_UART_HARNESS_PATH;
  return build_path;
}

}  // namespace

int main(int argc, char** argv) {
  Options options;
  if (!parse_options(argc, argv, &options)) {
    std::cerr << "Invalid UART co-simulation options. Clock must be "
                 "1 kHz–50 MHz, baud 1,200–1,000,000, and clock at least "
                 "8× baud.\n";
    return 2;
  }
  uint64_t size = 0;
  if (!file_size_in_range(options.firmware,
                          silicontutor::uart::kMaxFirmwareBytes, &size)) {
    std::cerr << "Firmware must be a regular file between 1 byte and 32 MiB.\n";
    return 2;
  }
  if (!is_elf_file(options.firmware)) {
    std::cerr << "Selected firmware does not have an ELF header.\n";
    return 2;
  }
  if (!file_size_in_range(options.rtl, silicontutor::uart::kMaxRtlBytes,
                          &size)) {
    std::cerr << "SystemVerilog source must be a regular file between 1 byte "
                 "and 128 KiB.\n";
    return 2;
  }
  const std::string verilator = find_executable("verilator");
  if (verilator.empty()) {
    std::cerr << "Verilator is not installed or not on PATH.\n";
    return 127;
  }
  const std::string qemu = find_executable("qemu-system-riscv64");
  if (qemu.empty()) {
    std::cerr << "QEMU RISC-V system emulator is not installed or not on PATH.\n";
    return 127;
  }
  const char* runtime = std::getenv("XDG_RUNTIME_DIR");
  if (!runtime || !*runtime || access(runtime, W_OK | X_OK) != 0) {
    std::cerr << "A writable XDG_RUNTIME_DIR is required for private "
                 "co-simulation work files.\n";
    return 1;
  }
  std::string workspace_template =
      std::string(runtime) + "/silicontutor-uart-XXXXXX";
  std::vector<char> writable(workspace_template.begin(),
                             workspace_template.end());
  writable.push_back('\0');
  char* workspace_path = mkdtemp(writable.data());
  if (!workspace_path) {
    std::cerr << "Could not create private co-simulation workspace: "
              << std::strerror(errno) << '\n';
    return 1;
  }
  chmod(workspace_path, 0700);
  Workspace workspace(workspace_path);
  const auto source_copy = std::filesystem::path(workspace.path()) / "top.sv";
  std::error_code copy_error;
  std::filesystem::copy_file(options.rtl, source_copy, copy_error);
  if (copy_error) {
    std::cerr << "Could not stage SystemVerilog source: "
              << copy_error.message() << '\n';
    return 1;
  }
  const std::string harness = harness_path(argv[0]);
  if (!std::filesystem::is_regular_file(harness)) {
    std::cerr << "Installed UART simulation harness is missing.\n";
    return 1;
  }
  std::string output;
  int exit_code = 0;
  const auto build_args = silicontutor::uart::verilator_arguments(
      verilator, (std::filesystem::path(workspace.path()) / "obj_dir").string(),
      source_copy.string(), harness);
  if (!run_command(build_args, workspace.path(), kCompileTimeout,
                   kMaxCommandOutput, &output, &exit_code)) {
    std::cerr << "Verilator C++ build failed (exit " << exit_code << "):\n"
              << output;
    return exit_code;
  }
  const int port = listen_port();
  if (port < 0) {
    std::cerr << "Could not reserve a local TCP port for QEMU serial.\n";
    return 1;
  }
  const auto qemu_args = silicontutor::uart::qemu_arguments(
      static_cast<unsigned>(port), options.firmware);
  const pid_t qemu_process = start_qemu(qemu_args, workspace.path());
  if (qemu_process < 0) {
    std::cerr << "Could not start QEMU.\n";
    return 1;
  }
  const auto sim_binary =
      (std::filesystem::path(workspace.path()) / "obj_dir/Vtop").string();
  const std::vector<std::string> sim_args = {
      sim_binary, std::to_string(port), std::to_string(options.clock_hz),
      std::to_string(options.baud), std::to_string(options.cycles)};
  output.clear();
  const bool simulated =
      run_command(sim_args, workspace.path(), kSimTimeout, kMaxSimOutput,
                  &output, &exit_code);
  stop_process(qemu_process);
  if (!simulated) {
    std::cerr << (output.empty() ? "UART simulation failed.\n" : output);
    return exit_code;
  }
  std::cout << output;
  return 0;
}
