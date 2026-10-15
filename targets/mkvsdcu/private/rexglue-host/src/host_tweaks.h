#pragma once

#include <cstdint>
#include <cstddef>
#include <string>
#include <utility>
#include <vector>

// Host-side settings that are not SDK cvars: Windows scheduling, audio mix
// and the graphics backend. Each applies itself when its cvar changes.
namespace host_tweaks {

// Applies every setting once and keeps them applied on change. Call after
// the config has been loaded and the runtime is set up.
void Install();
void Uninstall();

// "d3d12" or "vulkan", for loading the GPU plugin before the runtime starts.
std::string GpuBackend();

// Backends the installed GPU plugin was compiled with.
std::vector<std::string> AvailableGpuBackends();

// Resolve the port's render mode before the GPU plugin creates its caches.
// "legacy" leaves the SDK's existing resolution_scale configuration intact.
void ApplyRenderMode();

// Before guest static initialization or resource allocation. Refuses an
// unrecognized executable layout and leaves the native dimensions intact.
void ApplyGuestSceneResolution(uint8_t* image, size_t image_size);

// Effective X/Y factors, including an old SDK resolution_scale setting.
std::pair<uint32_t, uint32_t> RenderScale();

}  // namespace host_tweaks
