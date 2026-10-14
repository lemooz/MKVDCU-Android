#include "frame_telemetry.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <sstream>

#if defined(_WIN32)
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <Windows.h>
#else
#include <dlfcn.h>
#include <cstdio>
#endif

#include <rex/types.h>
#include <rex/graphics/xenos.h>
#include <rex/ppc/context.h>
#include <rex/ppc/func.h>
#include <rex/diagnostics/health.h>
#include <rex/cvar.h>
#include <rex/logging.h>

namespace {
constexpr size_t kHistory = 2048;  // ~34 s at 60 FPS
std::array<std::atomic<int64_t>, kHistory> g_swap_ticks{};
std::atomic<uint64_t> g_swap_count{0};
std::atomic<uint32_t> g_output_width{0};
std::atomic<uint32_t> g_output_height{0};

#if defined(_WIN32)
int64_t Now() {
  LARGE_INTEGER counter;
  QueryPerformanceCounter(&counter);
  return counter.QuadPart;
}

double TicksPerMs() {
  static const double value = [] {
    LARGE_INTEGER frequency;
    QueryPerformanceFrequency(&frequency);
    return double(frequency.QuadPart) / 1000.0;
  }();
  return value;
}
#else
int64_t Now() {
  return std::chrono::duration_cast<std::chrono::nanoseconds>(
             std::chrono::steady_clock::now().time_since_epoch())
      .count();
}

double TicksPerMs() {
  return 1000000.0;
}
#endif

void RecordSwap(PPCContext& ctx, uint8_t* base) {
  // Only the game's render thread calls VdSwap, so there is a single writer.
  const uint64_t index = g_swap_count.load(std::memory_order_relaxed);
  g_swap_ticks[index % kHistory].store(Now(), std::memory_order_relaxed);
  g_swap_count.store(index + 1, std::memory_order_release);

  // r4 points at the frontbuffer's texture fetch constant (big-endian dwords).
  const uint32_t fetch_address = ctx.r4.u32;
  if (!fetch_address) return;
  rex::graphics::xenos::xe_gpu_texture_fetch_t fetch;
  const auto* words = reinterpret_cast<const uint32_t*>(base + fetch_address);
  uint32_t* out = reinterpret_cast<uint32_t*>(&fetch);
  for (int i = 0; i < 6; ++i) out[i] = rex::byte_swap(words[i]);
  const uint32_t width = fetch.size_2d.width + 1, height = fetch.size_2d.height + 1;
  if (g_output_width.load(std::memory_order_relaxed) != width ||
      g_output_height.load(std::memory_order_relaxed) != height) {
    REXLOG_INFO("[OUTPUT_RESOLUTION] observed_swap={}x{} requested_internal_percent={}",
        width, height, rex::cvar::Query<int32_t>("port_internal_resolution_percent"));
  }
  g_output_width.store(width, std::memory_order_relaxed);
  g_output_height.store(height, std::memory_order_relaxed);
}

PPCFunc* RuntimeVdSwap() {
#if defined(_WIN32)
  HMODULE runtime = GetModuleHandleW(L"rexruntime.dll");
  auto* function = runtime ? reinterpret_cast<PPCFunc*>(GetProcAddress(runtime, "__imp__VdSwap"))
                           : nullptr;
  if (!function) {
    MessageBoxW(nullptr, L"rexruntime.dll does not export VdSwap. Rebuild the game.",
                L"MKVDCU-Recomp", MB_ICONERROR);
    std::abort();
  }
  return function;
#else
  void* runtime = dlopen("librexruntime.so", RTLD_NOLOAD | RTLD_LAZY);
  if (!runtime) {
    runtime = dlopen("librexruntime.so", RTLD_LAZY);
  }
  auto* function = runtime ? reinterpret_cast<PPCFunc*>(dlsym(runtime, "__imp__VdSwap"))
                           : reinterpret_cast<PPCFunc*>(dlsym(RTLD_DEFAULT, "__imp__VdSwap"));
  if (!function) {
    fprintf(stderr, "librexruntime.so does not export __imp__VdSwap. Rebuild the game.\n");
    std::abort();
  }
  return function;
#endif
}
}  // namespace

// The recompiled game calls this import once per presented frame. Defining it
// here takes precedence over the import library, so every frame passes
// through the telemetry before reaching the runtime's implementation.
extern "C" REX_FUNC(__imp__VdSwap) {
  rex::diagnostics::HealthScope scope("VdSwap", ctx.lr);
  rex::diagnostics::HealthRecord(rex::diagnostics::HealthEvent::SwapEnter);
  static PPCFunc* const runtime_vd_swap = RuntimeVdSwap();
  RecordSwap(ctx, base);
  runtime_vd_swap(ctx, base);
  rex::diagnostics::HealthRecord(rex::diagnostics::HealthEvent::SwapReturn);
}

namespace telemetry {

uint64_t GuestFrameCount() {
  return g_swap_count.load(std::memory_order_acquire);
}

std::string FrameIntervalsCsv(uint64_t after_frame) {
  const uint64_t count = GuestFrameCount();
  std::ostringstream out;
  out << count << '\n';
  const uint64_t oldest = count >= kHistory ? count - kHistory + 1 : 1;
  for (uint64_t i = std::max(oldest, after_frame); i < count; ++i) {
    const auto a = g_swap_ticks[(i - 1) % kHistory].load(std::memory_order_relaxed);
    const auto b = g_swap_ticks[i % kHistory].load(std::memory_order_relaxed);
    if (b >= a) out << i + 1 << ',' << double(b - a) / TicksPerMs() << '\n';
  }
  return out.str();
}

GuestOutputSize LastGuestOutputSize() {
  return {g_output_width.load(std::memory_order_relaxed),
          g_output_height.load(std::memory_order_relaxed)};
}

void RecentFrameTimes(std::vector<float>& out, size_t max_count) {
  out.clear();
  const uint64_t count = GuestFrameCount();
  const uint64_t available = std::min<uint64_t>(count, kHistory - 1);
  const uint64_t intervals = std::min<uint64_t>(available ? available - 1 : 0, max_count);
  const double per_ms = TicksPerMs();
  for (uint64_t i = count - intervals; i < count; ++i) {
    const int64_t a = g_swap_ticks[(i - 1) % kHistory].load(std::memory_order_relaxed);
    const int64_t b = g_swap_ticks[i % kHistory].load(std::memory_order_relaxed);
    out.push_back(float(double(b - a) / per_ms));
  }
}

FrameStats ComputeFrameStats(double window_seconds) {
  FrameStats stats;
  const uint64_t count = GuestFrameCount();
  stats.total_frames = count;
  if (count < 2) return stats;

  const double per_ms = TicksPerMs();
  const int64_t now = Now();
  const int64_t last = g_swap_ticks[(count - 1) % kHistory].load(std::memory_order_relaxed);
  stats.seconds_since_last_frame = double(now - last) / per_ms / 1000.0;

  const int64_t window_start = now - int64_t(window_seconds * 1000.0 * per_ms);
  std::vector<double> intervals;
  const uint64_t oldest = count > kHistory - 1 ? count - (kHistory - 1) : 1;
  for (uint64_t i = count - 1; i >= oldest; --i) {
    const int64_t b = g_swap_ticks[i % kHistory].load(std::memory_order_relaxed);
    if (b < window_start) break;
    const int64_t a = g_swap_ticks[(i - 1) % kHistory].load(std::memory_order_relaxed);
    intervals.push_back(double(b - a) / per_ms);
    if (i == oldest) break;
  }
  if (intervals.empty()) return stats;

  double sum = 0;
  for (double v : intervals) sum += v;
  stats.samples = uint32_t(intervals.size());
  stats.avg_ms = sum / intervals.size();
  // Frames per wall-clock second inside the window, not 1000 / average,
  // so a long stall at the edge of the window still lowers the figure.
  stats.fps = intervals.size() / std::min(window_seconds, sum / 1000.0 + stats.seconds_since_last_frame);
  double variance = 0;
  for (double v : intervals) variance += (v - stats.avg_ms) * (v - stats.avg_ms);
  stats.stddev_ms = std::sqrt(variance / intervals.size());

  std::sort(intervals.begin(), intervals.end());
  stats.min_ms = intervals.front();
  stats.max_ms = intervals.back();
  const auto percentile = [&](double p) {
    const size_t index = std::min(intervals.size() - 1, size_t(std::ceil(p * intervals.size())) - 1);
    return intervals[index];
  };
  stats.median_ms = percentile(0.5);
  stats.p95_ms = percentile(0.95);
  stats.p99_ms = percentile(0.99);
  stats.p999_ms = percentile(0.999);
  const double median = intervals[intervals.size() / 2];
  for (double v : intervals) stats.hitches += v > median * 1.5 ? 1 : 0;
  return stats;
}

}  // namespace telemetry
