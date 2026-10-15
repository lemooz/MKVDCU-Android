#pragma once

#include <stddef.h>
#include <stdint.h>

namespace scene_resolution {

constexpr uint32_t kImageBase = 0x82000000;
constexpr uint32_t kWidthAddress = 0x82F4D17C;
constexpr uint32_t kHeightAddress = 0x82F4D180;
constexpr uint32_t Percent(uint32_t requested) {
  return requested == 50 || requested == 75 ? requested : 100;
}
constexpr uint32_t Dimension(uint32_t native, uint32_t requested) {
  return native * Percent(requested) / 100;
}

inline uint32_t ReadBE32(const uint8_t* p) {
  return (uint32_t(p[0]) << 24) | (uint32_t(p[1]) << 16) |
         (uint32_t(p[2]) << 8) | p[3];
}
inline void WriteBE32(uint8_t* p, uint32_t value) {
  p[0] = uint8_t(value >> 24); p[1] = uint8_t(value >> 16);
  p[2] = uint8_t(value >> 8); p[3] = uint8_t(value);
}

// These are guest scene buffer minima, not Vulkan viewport overrides. The UE3
// allocator (82389540), copy/composition paths (8276E938/8276FB10), and static
// initializer (82ED4E30) read the same pair. Patch before any guest initializer.
// Anchor the consumer instructions as well as the data for this XEX revision.
inline bool Apply(uint8_t* image, size_t size, uint32_t requested) {
  if (!image || size < size_t(kHeightAddress - kImageBase) + 4) return false;
  const auto read = [image](uint32_t address) {
    return ReadBE32(image + address - kImageBase);
  };
  if (read(0x82389560) != 0x816BD17C ||
      read(0x82389564) != 0x8129D180 ||
      read(0x82ED4E44) != 0x8129D17C ||
      read(0x82ED4E48) != 0x8108D180 ||
      read(kWidthAddress) != 1040 || read(kHeightAddress) != 624) return false;
  WriteBE32(image + kWidthAddress - kImageBase, Dimension(1040, requested));
  WriteBE32(image + kHeightAddress - kImageBase, Dimension(624, requested));
  return true;
}

}  // namespace scene_resolution
