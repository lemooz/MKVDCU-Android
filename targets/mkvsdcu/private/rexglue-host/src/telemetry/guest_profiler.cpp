#include "guest_profiler.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <ctime>
#include <fstream>
#include <map>
#include <unordered_map>
#include <array>
#include <atomic>
#include <cstring>
#if defined(__ANDROID__) && defined(__aarch64__)
#include <csignal>
#include <dlfcn.h>
#include <ucontext.h>
#include <sys/syscall.h>
#include <unistd.h>
#endif

#if defined(_WIN32)
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <Windows.h>
#include <dbghelp.h>
#include <psapi.h>
#endif

#include <rex/ppc/func.h>

namespace telemetry {
namespace {

#if defined(_WIN32) || defined(__ANDROID__)
// Recompiled functions sorted by their host address, so an instruction
// pointer inside generated code can be traced back to the guest function.
struct HostFunction {
  uintptr_t host;
  uint32_t guest;
};

const std::vector<HostFunction>& HostFunctions() {
  static const std::vector<HostFunction> functions = [] {
    std::vector<HostFunction> result;
    for (const PPCFuncMapping* m = PPCFuncMappings; m->host; ++m) {
      result.push_back({reinterpret_cast<uintptr_t>(m->host), uint32_t(m->guest)});
    }
    std::sort(result.begin(), result.end(),
              [](const HostFunction& a, const HostFunction& b) { return a.host < b.host; });
    return result;
  }();
  return functions;
}
#endif

#if defined(_WIN32)

std::string Describe(uintptr_t address, uintptr_t exe_begin, uintptr_t exe_end) {
  char text[256];
  if (address >= exe_begin && address < exe_end) {
    const auto& functions = HostFunctions();
    auto it = std::upper_bound(functions.begin(), functions.end(), address,
                               [](uintptr_t a, const HostFunction& f) { return a < f.host; });
    if (it != functions.begin()) {
      --it;
      // Beyond the last generated function is host code in the exe.
      if (std::next(it) != functions.end() || address - it->host < 0x10000) {
        std::snprintf(text, sizeof(text), "guest sub_%08X", it->guest);
        return text;
      }
    }
  }
  HMODULE module = nullptr;
  char module_name[MAX_PATH] = "?";
  if (GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
                             GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                         reinterpret_cast<LPCSTR>(address), &module)) {
    char path[MAX_PATH];
    if (GetModuleFileNameA(module, path, MAX_PATH)) {
      const char* slash = std::strrchr(path, '\\');
      std::snprintf(module_name, sizeof(module_name), "%s", slash ? slash + 1 : path);
    }
  }
  alignas(SYMBOL_INFO) char buffer[sizeof(SYMBOL_INFO) + 256] = {};
  auto* symbol = reinterpret_cast<SYMBOL_INFO*>(buffer);
  symbol->SizeOfStruct = sizeof(SYMBOL_INFO);
  symbol->MaxNameLen = 255;
  DWORD64 displacement = 0;
  if (SymFromAddr(GetCurrentProcess(), address, &displacement, symbol)) {
    std::snprintf(text, sizeof(text), "%s!%s", module_name, symbol->Name);
  } else if (module) {
    std::snprintf(text, sizeof(text), "%s+0x%llX", module_name,
                  static_cast<unsigned long long>(address - reinterpret_cast<uintptr_t>(module)));
  } else {
    std::snprintf(text, sizeof(text), "0x%llX", static_cast<unsigned long long>(address));
  }
  return text;
}
#endif
#if defined(__ANDROID__) && defined(__aarch64__)
struct SampleSlot { std::atomic<uint32_t> tid{0}; std::atomic<uintptr_t> pc{0}; };
std::array<SampleSlot, 4> sample_slots;
std::mutex android_profile_mutex;
static_assert(std::atomic<uintptr_t>::is_always_lock_free);
void SampleSignal(int, siginfo_t*, void* context) {
  const uint32_t tid = uint32_t(syscall(SYS_gettid));
  for (auto& slot : sample_slots) if (slot.tid.load(std::memory_order_relaxed) == tid) {
    slot.pc.store(static_cast<ucontext_t*>(context)->uc_mcontext.pc, std::memory_order_release);
    break;
  }
}
bool InstallSampler(int signal) {
  struct sigaction previous{};
  if (sigaction(signal, nullptr, &previous)) return false;
  if ((previous.sa_flags & SA_SIGINFO) && previous.sa_sigaction == &SampleSignal) return true;
  if (previous.sa_handler != SIG_DFL && previous.sa_handler != SIG_IGN) return false;
  struct sigaction action{};
  sigemptyset(&action.sa_mask);
  action.sa_flags = SA_SIGINFO | SA_RESTART;
  action.sa_sigaction = &SampleSignal;
  // Leave an idle handler installed: a signal already queued must not acquire
  // a fatal default action when the capture finishes. Slots have static lifetime.
  return sigaction(signal, &action, nullptr) == 0;
}
std::string DescribeAndroid(uintptr_t pc) {
  Dl_info module{};
  if (!dladdr(reinterpret_cast<void*>(pc), &module) || !module.dli_fbase) return "unmapped PC";
  char text[320];
  const char* path = module.dli_fname ? module.dli_fname : "?";
  const char* slash = std::strrchr(path, '/');
  const auto& functions = HostFunctions();
  auto it = std::upper_bound(functions.begin(), functions.end(), pc,
      [](uintptr_t address, const HostFunction& f) { return address < f.host; });
  uint32_t guest = 0;
  if (it != functions.begin() && it != functions.end()) {
    --it; Dl_info generated{};
    if (dladdr(reinterpret_cast<void*>(it->host), &generated) && generated.dli_fbase == module.dli_fbase)
      guest = it->guest;
  }
  if (guest) std::snprintf(text, sizeof(text), "%s+0x%llX guest region near sub_%08X",
      slash ? slash+1 : path, (unsigned long long)(pc-reinterpret_cast<uintptr_t>(module.dli_fbase)), guest);
  else std::snprintf(text, sizeof(text), "%s+0x%llX %s", slash ? slash+1 : path,
      (unsigned long long)(pc-reinterpret_cast<uintptr_t>(module.dli_fbase)), module.dli_sname ? module.dli_sname : "");
  return text;
}
#endif
std::atomic<bool> android_profile_request{false};
}  // namespace

void RequestAndroidCpuProfile() { android_profile_request.store(true, std::memory_order_release); }
bool ConsumeAndroidCpuProfileRequest() { return android_profile_request.exchange(false, std::memory_order_acq_rel); }

GuestProfiler::~GuestProfiler() {
  if (worker_.joinable()) worker_.join();
}

void GuestProfiler::Start(std::vector<std::pair<uint32_t, std::string>> threads, double seconds,
                          std::filesystem::path directory) {
  {
    std::lock_guard lock(mutex_);
    if (report_.running) return;
    report_ = {};
    report_.running = true;
    report_.summary = "Profiling...";
  }
  if (worker_.joinable()) worker_.join();
  worker_ = std::thread([this, threads = std::move(threads), seconds, directory]() mutable {
    Run(std::move(threads), seconds, std::move(directory));
  });
}

ProfileReport GuestProfiler::Report() const {
  std::lock_guard lock(mutex_);
  return report_;
}

void GuestProfiler::Run(std::vector<std::pair<uint32_t, std::string>> threads, double seconds,
                        std::filesystem::path directory) {
#if defined(_WIN32)
  SetThreadDescription(GetCurrentThread(), L"Port profiler");
  struct Target {
    uint32_t id;
    std::string name;
    HANDLE handle;
    std::vector<uintptr_t> samples;
  };
  std::vector<Target> targets;
  for (auto& [id, name] : threads) {
    if (id == GetCurrentThreadId()) continue;
    HANDLE handle = OpenThread(THREAD_SUSPEND_RESUME | THREAD_GET_CONTEXT, FALSE, id);
    if (handle) targets.push_back({id, std::move(name), handle, {}});
  }
  if (targets.empty()) {
    std::lock_guard lock(mutex_);
    report_.running = false;
    report_.summary = "No threads to profile yet.";
    return;
  }

  // Only read the context while a thread is suspended: it may hold a heap or
  // loader lock, so allocating or symbolizing before resuming could deadlock.
  const auto end = std::chrono::steady_clock::now() + std::chrono::duration<double>(seconds);
  for (auto& target : targets) target.samples.reserve(size_t(seconds * 1000) + 16);
  while (std::chrono::steady_clock::now() < end) {
    for (auto& target : targets) {
      if (SuspendThread(target.handle) == DWORD(-1)) continue;
      CONTEXT context = {};
      context.ContextFlags = CONTEXT_CONTROL;
      const bool ok = GetThreadContext(target.handle, &context);
      ResumeThread(target.handle);
      if (ok && target.samples.size() < target.samples.capacity()) target.samples.push_back(context.Rip);
    }
    Sleep(1);
  }

  MODULEINFO exe = {};
  GetModuleInformation(GetCurrentProcess(), GetModuleHandleW(nullptr), &exe, sizeof(exe));
  const auto exe_begin = reinterpret_cast<uintptr_t>(exe.lpBaseOfDll);
  const auto exe_end = exe_begin + exe.SizeOfImage;
  SymSetOptions(SYMOPT_UNDNAME | SYMOPT_DEFERRED_LOADS);
  const bool symbols = SymInitialize(GetCurrentProcess(), nullptr, TRUE);

  ProfileReport report;
  uint32_t total = 0;
  for (auto& target : targets) {
    CloseHandle(target.handle);
    std::unordered_map<uintptr_t, uint32_t> by_address;
    for (uintptr_t rip : target.samples) ++by_address[rip];
    std::map<std::string, uint32_t> by_location;
    for (const auto& [rip, count] : by_address) by_location[Describe(rip, exe_begin, exe_end)] += count;

    ThreadProfile profile;
    profile.thread_id = target.id;
    profile.thread_name = target.name;
    profile.samples = uint32_t(target.samples.size());
    total += profile.samples;
    for (const auto& [location, count] : by_location) {
      profile.top.push_back({location, count, profile.samples ? 100.0 * count / profile.samples : 0});
    }
    std::sort(profile.top.begin(), profile.top.end(),
              [](const ProfileEntry& a, const ProfileEntry& b) { return a.samples > b.samples; });
    if (profile.top.size() > 25) profile.top.resize(25);
    report.threads.push_back(std::move(profile));
  }
  if (symbols) SymCleanup(GetCurrentProcess());

  std::error_code ec;
  std::filesystem::create_directories(directory, ec);
  const std::time_t now = std::time(nullptr);
  std::tm local = {};
  localtime_s(&local, &now);
  char name[64];
  std::strftime(name, sizeof(name), "profile-%Y%m%d-%H%M%S.txt", &local);
  report.file = directory / name;
  if (std::ofstream out(report.file); out) {
    out << "MKVDCU-Recomp CPU profile, " << seconds << " s, ~1 kHz sampling\n";
    for (const auto& thread : report.threads) {
      out << "\n== " << thread.thread_name << " (" << thread.thread_id << "), " << thread.samples
          << " samples\n";
      for (const auto& entry : thread.top) {
        char line[320];
        std::snprintf(line, sizeof(line), "%6.2f%%  %6u  %s\n", entry.percent, entry.samples,
                      entry.location.c_str());
        out << line;
      }
    }
  }
  report.summary = std::to_string(total) + " samples from " + std::to_string(report.threads.size()) +
                   " threads. Saved " + report.file.filename().string();

  std::lock_guard lock(mutex_);
  report_ = std::move(report);
  report_.running = false;
#elif defined(__ANDROID__) && defined(__aarch64__)
  std::unique_lock capture_lock(android_profile_mutex);
  const int signal = SIGRTMIN + 12; // Runtime owns only SIGRTMIN..SIGRTMIN+2.
  if (signal > SIGRTMAX || !InstallSampler(signal)) {
    std::lock_guard lock(mutex_); report_.running = false;
    report_.summary = "CPU sampler signal unavailable or owned by another component"; return;
  }
  if (threads.size() > sample_slots.size()) threads.resize(sample_slots.size());
  std::array<std::vector<uintptr_t>, 4> samples;
  for (size_t i = 0; i < threads.size(); ++i) {
    samples[i].reserve(size_t(seconds*100)+16);
    sample_slots[i].pc.store(0);
    sample_slots[i].tid.store(threads[i].first);
  }
  const auto end = std::chrono::steady_clock::now() + std::chrono::duration<double>(seconds);
  while (std::chrono::steady_clock::now() < end) {
    struct sigaction current{};
    if (sigaction(signal, nullptr, &current) || !(current.sa_flags & SA_SIGINFO) ||
        current.sa_sigaction != &SampleSignal) break;
    for (const auto& target : threads) syscall(SYS_tgkill, getpid(), target.first, signal);
    std::this_thread::sleep_for(std::chrono::milliseconds(10));
    for (size_t i = 0; i < threads.size(); ++i)
      if (auto pc = sample_slots[i].pc.exchange(0, std::memory_order_acq_rel)) samples[i].push_back(pc);
  }
  for (auto& slot : sample_slots) slot.tid.store(0);
  ProfileReport report;
  std::error_code ec; std::filesystem::create_directories(directory, ec);
  report.file = directory / ("profile-android-" + std::to_string(std::time(nullptr)) + ".txt");
  std::ofstream out(report.file);
  out << "Android ARM64 CPU PC samples, ~100 Hz per thread, " << seconds << " s\n"
      << "Wall-clock samples (including blocked threads), not CPU utilization. Guest mapping is nearest-region inference.\n";
  uint32_t total = 0;
  for (size_t i = 0; i < threads.size(); ++i) {
    ThreadProfile profile; profile.thread_id=threads[i].first; profile.thread_name=threads[i].second;
    profile.samples=uint32_t(samples[i].size()); total+=profile.samples;
    std::map<std::string,uint32_t> locations;
    for (auto pc : samples[i]) ++locations[DescribeAndroid(pc)];
    for (auto& [location,count] : locations)
      profile.top.push_back({location,count,profile.samples ? 100.0*count/profile.samples : 0});
    std::sort(profile.top.begin(),profile.top.end(),[](const auto& a,const auto& b){return a.samples>b.samples;});
    if (profile.top.size()>25) profile.top.resize(25);
    out << "\n== " << profile.thread_name << " tid=" << profile.thread_id << " samples=" << profile.samples << '\n';
    for (const auto& entry : profile.top) out << entry.samples << " " << entry.percent << "% " << entry.location << '\n';
    report.threads.push_back(std::move(profile));
  }
  report.summary=std::to_string(total)+" ARM64 PC samples; saved "+report.file.filename().string();
  std::lock_guard lock(mutex_); report_=std::move(report); report_.running=false;
#else
  (void)threads;
  (void)seconds;
  (void)directory;
  std::lock_guard lock(mutex_);
  report_.running = false;
  report_.summary = "Profiler not supported on Android";
#endif
}

}  // namespace telemetry
