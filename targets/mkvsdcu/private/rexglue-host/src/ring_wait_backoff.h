#pragma once

#include <cstdint>

namespace ring_wait {
// Called only after the guest's ring-space predicate reports full. A changing
// read pointer or a new polling burst resets the delay. No XE_SWAP pacing.
class Backoff {
 public:
  uint32_t DelayMicroseconds(uint32_t read_pointer, uint64_t now_us) {
    if (!initialized_ || read_pointer != read_pointer_ || now_us - last_us_ > 2000) {
      initialized_ = true;
      attempts_ = 0;
      read_pointer_ = read_pointer;
    }
    last_us_ = now_us;
    if (attempts_ < 20) ++attempts_;
    if (attempts_ <= 16) return 0;
    return attempts_ == 17 ? 50 : attempts_ == 18 ? 100 : attempts_ == 19 ? 200 : 400;
  }
 private:
  bool initialized_ = false;
  uint32_t read_pointer_ = 0, attempts_ = 0;
  uint64_t last_us_ = 0;
};
}  // namespace ring_wait
