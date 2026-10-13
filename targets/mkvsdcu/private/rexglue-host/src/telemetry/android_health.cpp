#if defined(__ANDROID__)
#include <jni.h>
#include <android/log.h>
#include <chrono>
#include <atomic>
#include <thread>
#include <sstream>
#include <rex/diagnostics/health.h>
#include <rex/diagnostics/performance.h>
#include "frame_telemetry.h"
#include "guest_profiler.h"

namespace {
class HealthWatchdog {
 public:
  HealthWatchdog() : thread_([this] {
    while (running_.load(std::memory_order_relaxed)) {
      __android_log_print(ANDROID_LOG_INFO, "MKVDCU_HEALTH", "%s",
          rex::diagnostics::HealthSummary().c_str());
      __android_log_print(ANDROID_LOG_INFO, "MKVDCU_PERF", "%s",
          rex::diagnostics::PerformanceSummary().c_str());
      std::istringstream threads(rex::diagnostics::HealthThreads());
      std::string line;
      while (std::getline(threads, line))
        __android_log_print(ANDROID_LOG_INFO, "MKVDCU_HEALTH", "%s", line.c_str());
      for (int i = 0; i < 4 && running_.load(std::memory_order_relaxed); ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(250));
    }
  }) {}
  ~HealthWatchdog() {
    running_.store(false, std::memory_order_relaxed);
    thread_.join();
  }
 private:
  std::atomic<bool> running_{true};
  std::thread thread_;
};
}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_org_mkvdcu_android_GameActivity_nativeReadHealthSnapshot(JNIEnv* env, jclass) {
  rex::diagnostics::EnableHealth(true);
  // Independent of Java, SDL's UI thread, guest waits and the graphics queue.
  static HealthWatchdog watchdog;
  const auto snapshot = rex::diagnostics::HealthSummary();
  return env->NewStringUTF(snapshot.c_str());
}
extern "C" JNIEXPORT void JNICALL
Java_org_mkvdcu_android_GameActivity_nativeStartCpuProfile(JNIEnv*, jclass) {
  telemetry::RequestAndroidCpuProfile();
}
extern "C" JNIEXPORT jstring JNICALL
Java_org_mkvdcu_android_GameActivity_nativeReadFrameIntervals(JNIEnv* env, jclass, jlong after) {
  const auto rows = telemetry::FrameIntervalsCsv(after < 0 ? 0 : uint64_t(after));
  return env->NewStringUTF(rows.c_str());
}
extern "C" JNIEXPORT jstring JNICALL
Java_org_mkvdcu_android_GameActivity_nativeReadPerformanceSnapshot(JNIEnv* env, jclass) {
  const auto frames = telemetry::ComputeFrameStats(1.0);
  std::ostringstream text;
  text << frames.total_frames << ',' << frames.fps << ',' << frames.avg_ms << ',' << frames.median_ms
       << ',' << frames.p95_ms << ',' << frames.p99_ms
       << ',' << rex::diagnostics::PerformanceSummary();
  return env->NewStringUTF(text.str().c_str());
}
#endif
