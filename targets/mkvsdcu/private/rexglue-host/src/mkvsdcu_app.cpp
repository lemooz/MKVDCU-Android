#include "generated/default/mkvsdcu_init.h"
#include "mkvsdcu_app.h"

#include <imgui.h>
#include <algorithm>
#include <chrono>
#include <filesystem>
#include <thread>

#if defined(_WIN32)
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <Windows.h>
#endif

#include <rex/cvar.h>
#include <rex/filesystem.h>
#include <rex/filesystem/entry.h>
#include <rex/filesystem/file.h>
#include <rex/filesystem/vfs.h>
#include <rex/logging.h>
#include <rex/graphics/command_processor.h>
#include <rex/graphics/graphics_system.h>
#include <rex/input/input_system.h>
#include <rex/system/gpu_plugin.h>
#include <rex/system/xmemory.h>
#include <rex/system/xtypes.h>
#include <rex/ui/keybinds.h>

#if defined(__ANDROID__)
#include <jni.h>
#endif

using rex::X_STATUS;

#include "host_tweaks.h"
#include "input/controller_filter.h"
#include "mods/mod_overlay.h"
#include "port_menu/port_config.h"
#include "port_menu/port_menu.h"
#include "telemetry/perf_overlay.h"
#include "telemetry/system_telemetry.h"

REXCVAR_DEFINE_STRING(port_version, "dev", "Port", "Port release shown in the menu (set by the launcher)");

MkvsdcuApp::~MkvsdcuApp() = default;

void MkvsdcuApp::OnConfigurePaths(rex::PathConfig& paths) {
  paths.config_path = paths.user_data_root / "mkvsdcu.toml";
  RepairPortConfig(paths.config_path);
  host_.config_path = paths.config_path;
  host_.log_dir = paths.user_data_root / "logs";
}

// Developer aid: MKVDCU_DUMP_IMAGE=<file> writes the loaded XEX image so
// indirect-call targets can be found offline (scripts/find-function-seeds.py).
void MkvsdcuApp::OnPostLoadXexImage() {
#if defined(_WIN32)
  wchar_t path[MAX_PATH];
  if (!GetEnvironmentVariableW(L"MKVDCU_DUMP_IMAGE", path, MAX_PATH)) return;
  constexpr uint32_t kImageBase = 0x82000000, kImageSize = 0x01130000;
  const uint8_t* image = runtime()->memory()->virtual_membase() + kImageBase;
  if (FILE* file = _wfopen(path, L"wb")) {
    fwrite(image, 1, kImageSize, file);
    fclose(file);
  }
#endif
}

void MkvsdcuApp::OnPreSetup(rex::RuntimeConfig& config) {
  // Load the GPU plugin ourselves to pick its backend. If that backend is not
  // compiled in, ReXApp falls back to loading the plugin's default.
  const std::string plugin = rex::cvar::GetFlagByName("gpu_plugin");
  const std::string backend = host_tweaks::GpuBackend();
  REXLOG_INFO("[MKVDCU][GPU] OnPreSetup: gpu_plugin='{}' backend='{}' graphics_already_set={}",
              plugin, backend, config.graphics != nullptr);
  if (!config.graphics && !plugin.empty()) {
    config.graphics = rex::system::LoadGpuPlugin(plugin, backend);
    REXLOG_INFO("[MKVDCU][GPU] LoadGpuPlugin('{}','{}') -> {}", plugin, backend,
                config.graphics ? "SUCCESS" : "FAIL (nullptr)");
  }
  if (!config.graphics) {
    REXLOG_ERROR("[MKVDCU][GPU] No graphics system configured - game will run headless (audio/logic "
                 "only, black screen expected)");
  }
  // The host framebuffer path omits fighting surfaces in this title. Use
  // exact EDRAM depth/stencil emulation for Automatic Vulkan render targets.
  if (rex::cvar::GetFlagInfo("render_target_path_vulkan") &&
      rex::cvar::GetFlagByName("render_target_path_vulkan").empty()) {
    rex::cvar::SetFlagByName("render_target_path_vulkan", "fsi");
  }
  if (rex::cvar::GetFlagInfo("vulkan_dynamic_rendering") &&
      rex::cvar::GetFlagSource("vulkan_dynamic_rendering") == rex::cvar::Source::kDefault) {
    rex::cvar::SetFlagByName("vulkan_dynamic_rendering", "false");
  }
  if (rex::cvar::GetFlagInfo("gpu_frame_rate_limit") &&
      rex::cvar::GetFlagSource("gpu_frame_rate_limit") == rex::cvar::Source::kDefault) {
    rex::cvar::SetFlagByName("gpu_frame_rate_limit", "60");
  }
  // The plugin registers its scale cvars when loaded. Set both axes before
  // the graphics system allocates render targets and translates shaders.
  host_tweaks::ApplyRenderMode();
  rex::cvar::SetFlagByName("depth_float24_convert_in_pixel_shader", "true");
  {
    const auto scale = host_tweaks::RenderScale();
    REXLOG_INFO("[MKVDCU][GPU] render_mode='{}' scale={}x{} resolution_scale={} "
                "draw_scale_x={} draw_scale_y={} depth_float24_convert_in_pixel_shader={}",
                rex::cvar::GetFlagByName("port_render_mode"), scale.first, scale.second,
                rex::cvar::GetFlagByName("resolution_scale"),
                rex::cvar::GetFlagByName("draw_resolution_scale_x"),
                rex::cvar::GetFlagByName("draw_resolution_scale_y"),
                rex::cvar::GetFlagByName("depth_float24_convert_in_pixel_shader"));
  }

  config.input_factory = [this](bool tool_mode) -> std::unique_ptr<rex::system::IInputSystem> {
    return port_input::CreateInputSystem(tool_mode, [this] {
      app_context().CallInUIThreadDeferred([this] { OpenPortMenu(); });
    });
  };
}

void MkvsdcuApp::OnConfigureFonts(ImFontAtlas* atlas) {
#if defined(_WIN32)
  const char* font = "C:\\Windows\\Fonts\\bahnschrift.ttf";
  if (std::filesystem::exists(font)) port_font_ = atlas->AddFontFromFileTTF(font, 18.0f);
#else
  const char* font = "/system/fonts/Roboto-Regular.ttf";
  if (std::filesystem::exists(font)) port_font_ = atlas->AddFontFromFileTTF(font, 18.0f);
#endif
}

void MkvsdcuApp::OnConfigureStyle(ImGuiStyle& style, rex::ui::Style&) {
  ImGui::StyleColorsDark(&style);
  if (port_font_) ImGui::GetIO().FontDefault = port_font_;
  ImGui::GetIO().ConfigFlags |= ImGuiConfigFlags_NavEnableKeyboard;
  style.WindowRounding = 2.0f;
  style.FrameRounding = 2.0f;
  style.WindowPadding = ImVec2(24, 18);
  style.FramePadding = ImVec2(10, 8);
  style.ItemSpacing = ImVec2(12, 12);
  style.Colors[ImGuiCol_WindowBg] = ImVec4(0.07f, 0.10f, 0.14f, 0.98f);
  style.Colors[ImGuiCol_FrameBg] = ImVec4(0.13f, 0.19f, 0.24f, 1.0f);
  style.Colors[ImGuiCol_FrameBgHovered] = ImVec4(0.19f, 0.31f, 0.36f, 1.0f);
  style.Colors[ImGuiCol_Button] = ImVec4(0.21f, 0.52f, 0.57f, 1.0f);
  style.Colors[ImGuiCol_ButtonHovered] = ImVec4(0.36f, 0.85f, 0.90f, 1.0f);
  style.Colors[ImGuiCol_ButtonActive] = ImVec4(0.24f, 0.65f, 0.70f, 1.0f);
  style.Colors[ImGuiCol_Header] = ImVec4(0.17f, 0.31f, 0.37f, 1.0f);
  style.Colors[ImGuiCol_HeaderHovered] = ImVec4(0.25f, 0.51f, 0.58f, 1.0f);
  style.Colors[ImGuiCol_CheckMark] = ImVec4(0.37f, 0.86f, 0.91f, 1.0f);
  style.Colors[ImGuiCol_SliderGrab] = ImVec4(0.93f, 0.42f, 0.34f, 1.0f);
}

void MkvsdcuApp::OnCreateDialogs(rex::ui::ImGuiDrawer* drawer) {
  host_.window_info = [this] { return WindowInfo(); };
  host_.resize_window = [this](uint32_t w, uint32_t h) { ResizeWindow(w, h); };
  host_.show_perf_overlay = [this](bool visible) { ShowPerfOverlay(visible); };
  host_.set_csv_logging = [this](bool enabled) {
    return sampler_ ? sampler_->SetCsvLogging(enabled, host_.log_dir) : std::filesystem::path();
  };

  rex::ui::RegisterBind("bind_port_menu", "F1", "Toggle MKVDCU Port Menu", [this]() {
    if (port_menu_) {
      port_menu_->RequestClose();
    } else {
      OpenPortMenu();
    }
  });
  rex::ui::RegisterBind("bind_perf_overlay", "F2", "Toggle performance overlay", [this]() {
    const bool visible = perf_overlay_ == nullptr;
    rex::cvar::SetFlagByName("port_perf_overlay", visible ? "true" : "false");
    SavePortCvars(host_.config_path, {"port_perf_overlay"});
    ShowPerfOverlay(visible);
  });
}

void MkvsdcuApp::OnPostSetup() {
#ifdef __ANDROID__
  // TEMPORARY: dump the whole D:\ tree the ISO parser extracted. The game
  // boots but every D:\ content open fails - find out what D:\ contains.
  {
    auto* fs = runtime() ? runtime()->file_system() : nullptr;
    auto* root = fs ? fs->ResolvePath("d:\\") : nullptr;
    REXLOG_INFO("[VFS] d:\\ root -> {}", root ? "FOUND" : "MISSING");
    if (root) {
      struct Dumper {
        static void Run(const rex::filesystem::Entry* e, int depth, size_t& budget) {
          if (!e || !budget) return;
          std::string indent(size_t(depth) * 2, ' ');
          REXLOG_INFO("[VFS] {}{} ({} children)", indent, e->name(), e->child_count());
          if (!--budget) return;
          for (const auto& c : e->children()) {
            Run(c.get(), depth + 1, budget);
            if (!budget) return;
          }
        }
      };
      size_t budget = 3000;
      Dumper::Run(root, 0, budget);
      REXLOG_INFO("[VFS] dump done, budget left={}", budget);
      // TEMPORARY: also write the tree to pullable storage (logcat rotates).
      if (FILE* f = fopen("/data/local/tmp/mkvdcu_vfs_tree.txt", "w")) {
        struct FileDumper {
          static void Run(FILE* f, const rex::filesystem::Entry* e, int depth, size_t& budget) {
            if (!e || !budget) return;
            std::string indent(size_t(depth) * 2, ' ');
            fprintf(f, "%s%s (%zu children)\n", indent.c_str(), e->name().c_str(),
                    e->child_count());
            if (!--budget) return;
            for (const auto& c : e->children()) {
              Run(f, c.get(), depth + 1, budget);
              if (!budget) return;
            }
          }
        };
        size_t fbudget = 3000;
        FileDumper::Run(f, root, 0, fbudget);
        fclose(f);
      }
      // Bounded open-probe: prove whether D:\ content can actually be opened
      // and read (not just enumerated). Limited to the first 20 root children
      // plus a few well-known paths, so logcat stays usable.
      const size_t kMaxProbes = 20;
      size_t probed = 0;
      for (const auto& child : root->children()) {
        if (probed >= kMaxProbes) break;
        const bool is_dir =
            (child->attributes() & rex::filesystem::kFileAttributeDirectory) != 0;
        REXLOG_INFO("[MKVDCU][VFS] VFS_OPEN probe={} size={} attrs=0x{:X} dir={}", child->name(),
                    child->size(), child->attributes(), is_dir ? "yes" : "no");
        if (!is_dir) {
          rex::filesystem::File* file = nullptr;
          const rex::X_STATUS status =
              child->Open(rex::filesystem::FileAccess::kGenericRead, &file);
          REXLOG_INFO("[MKVDCU][VFS] VFS_OPEN result={:08X} path={} size={}",
                      static_cast<uint32_t>(status), child->name(), child->size());
          if (status == X_STATUS_SUCCESS && file) {
            uint8_t head[16] = {};
            size_t bytes_read = 0;
            const rex::X_STATUS rs =
                file->ReadSync(std::span<uint8_t>(head, sizeof(head)), 0, &bytes_read);
            uint32_t w = 0;
            if (bytes_read >= 4) std::memcpy(&w, head, 4);
            REXLOG_INFO("[MKVDCU][VFS] VFS_READ result={:08X} bytes={} head={:08X}",
                        static_cast<uint32_t>(rs), bytes_read, w);
            file->Destroy();
          }
        }
        ++probed;
      }
      for (const char* alias : {"d:\\", "d:/", "game:\\", "game:/", "\\Device\\Harddisk0\\Partition1"}) {
        auto* e = fs->ResolvePath(alias);
        REXLOG_INFO("[MKVDCU][VFS] VFS_EXISTS alias='{}' -> {}", alias, e ? "FOUND" : "MISSING");
      }
    } else {
      REXLOG_ERROR("[MKVDCU][VFS] d:\\ root MISSING - ISO not mounted or GDFX parse failed; "
                   "graphics assets unreachable while audio/logic may continue");
    }
  }
#endif
  sampler_ = std::make_unique<telemetry::SystemSampler>([this]() -> uint32_t {
    auto* graphics = static_cast<rex::graphics::GraphicsSystem*>(runtime()->graphics_system());
    auto* processor = graphics ? graphics->command_processor() : nullptr;
    return processor ? processor->counter() : 0;
  });
  host_.sampler = sampler_.get();
  host_.profiler = &profiler_;
  host_.mods_loaded = mod_overlay::Install(runtime()->file_system(), mod_overlay::EnabledMods());
  host_tweaks::Install();
  if (rex::cvar::Query<bool>("port_perf_csv")) sampler_->SetCsvLogging(true, host_.log_dir);
#if defined(__ANDROID__)
  auto_profile_ = std::thread([this] {
    const int delay = rex::cvar::Query<int32_t>("port_profile_after");
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(std::max(0, delay));
    bool scheduled = delay > 0;
    sampler_->SetWanted("auto-profile", true);
    while (!stopping_) {
      const bool requested = telemetry::ConsumeAndroidCpuProfileRequest();
      if (requested || (scheduled && std::chrono::steady_clock::now() >= deadline)) {
        scheduled = false;
        std::vector<std::pair<uint32_t, std::string>> threads;
        for (const auto& thread : sampler_->Latest().busiest_threads)
          if (threads.size() < 4 && thread.core_percent >= 5) threads.emplace_back(thread.id, thread.name);
        profiler_.Start(std::move(threads), 5.0, host_.log_dir);
      }
      std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
  });
#else
  if (const int delay = rex::cvar::Query<int32_t>("port_profile_after"); delay > 0) {
    sampler_->SetWanted("auto-profile", true);
    auto_profile_ = std::thread([this, delay] {
      for (int i = 0; i < delay * 10 && !stopping_; ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
      }
      if (stopping_) return;
      std::vector<std::pair<uint32_t, std::string>> threads;
      for (const auto& thread : sampler_->Latest().busiest_threads) {
        if (threads.size() < 4 && thread.core_percent >= 5) threads.emplace_back(thread.id, thread.name);
      }
      profiler_.Start(std::move(threads), 5.0, host_.log_dir);
    });
  }
#endif

  auto* input = static_cast<rex::input::InputSystem*>(runtime()->input_system());
  if (input) {
    input->SetActiveCallback([this]() {
      return port_menu_ == nullptr && !imgui_drawer()->GetIO().WantCaptureMouse;
    });
  }
  if (rex::cvar::Query<int32_t>("port_debug_menu_cycle") > 0) {
    app_context().CallInUIThreadDeferred([this] { OpenPortMenu(); });
  }
#ifdef __ANDROID__
  // Android performance overlay is now unified into Java MangoHUD/RivaTuner overlay.
  // Native ImGui overlay is kept disabled.
  rex::cvar::SetFlagByName("port_perf_overlay", "false");
#endif
  if (rex::cvar::Query<bool>("port_perf_overlay")) {
    app_context().CallInUIThreadDeferred([this] { ShowPerfOverlay(true); });
  }
}

void MkvsdcuApp::OnShutdown() {
  stopping_ = true;
  if (auto_profile_.joinable()) auto_profile_.join();
  rex::ui::UnregisterBind("bind_port_menu");
  rex::ui::UnregisterBind("bind_perf_overlay");
  if (port_menu_) port_menu_->SaveIfDirty();
  delete port_menu_;
  port_menu_ = nullptr;
  delete perf_overlay_;
  perf_overlay_ = nullptr;
  host_.sampler = nullptr;
  sampler_.reset();
  host_tweaks::Uninstall();
}

void MkvsdcuApp::OpenPortMenu() {
  if (port_menu_ || !imgui_drawer()) return;
  port_menu_ = new PortMenuDialog(imgui_drawer(), &host_, [this]() { port_menu_ = nullptr; });
}

void MkvsdcuApp::ShowPerfOverlay(bool visible) {
  if (visible && !perf_overlay_ && imgui_drawer()) {
    perf_overlay_ = new PerfOverlay(imgui_drawer(), &host_, [this]() { perf_overlay_ = nullptr; });
  } else if (!visible && perf_overlay_) {
    perf_overlay_->Hide();
  }
}

PortWindowInfo MkvsdcuApp::WindowInfo() const {
  PortWindowInfo info;
  if (auto* w = window()) {
    info.width = w->GetActualPhysicalWidth();
    info.height = w->GetActualPhysicalHeight();
    info.fullscreen = w->IsFullscreen();
  }
  return info;
}

void MkvsdcuApp::ResizeWindow(uint32_t width, uint32_t height) {
#if defined(_WIN32)
  // Resizing from inside the overlay's paint would re-enter the presenter.
  app_context().CallInUIThreadDeferred([this, width, height] {
    auto* w = window();
    if (!w || w->IsFullscreen()) return;
    auto hwnd = static_cast<HWND>(w->GetNativeWindowHandle());
    if (!hwnd) return;
    if (IsZoomed(hwnd)) ShowWindow(hwnd, SW_RESTORE);
    const DWORD style = DWORD(GetWindowLongPtrW(hwnd, GWL_STYLE));
    const DWORD ex_style = DWORD(GetWindowLongPtrW(hwnd, GWL_EXSTYLE));
    RECT rect = {0, 0, LONG(width), LONG(height)};
    AdjustWindowRectExForDpi(&rect, style, FALSE, ex_style, GetDpiForWindow(hwnd));
    const int outer_w = rect.right - rect.left, outer_h = rect.bottom - rect.top;
    MONITORINFO monitor = {sizeof(monitor)};
    GetMonitorInfoW(MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST), &monitor);
    const RECT& work = monitor.rcWork;
    const int x = work.left + std::max(0, int((work.right - work.left - outer_w) / 2));
    const int y = work.top + std::max(0, int((work.bottom - work.top - outer_h) / 2));
    SetWindowPos(hwnd, nullptr, x, y, outer_w, outer_h, SWP_NOZORDER | SWP_NOACTIVATE);
  });
#else
  (void)width;
  (void)height;
#endif
}

#if defined(__ANDROID__)
extern "C" JNIEXPORT void JNICALL
Java_org_mkvdcu_android_GameActivity_nativeSetVirtualGamepadState(JNIEnv*, jclass, jint buttons,
                                                                  jint thumb_lx, jint thumb_ly) {
  port_input::SetVirtualGamepadState(static_cast<uint16_t>(buttons),
                                     static_cast<int16_t>(thumb_lx),
                                     static_cast<int16_t>(thumb_ly));
}
extern "C" JNIEXPORT void JNICALL
Java_org_mkvdcu_android_GameActivity_nativeSetVirtualGamepadStateV2(JNIEnv*,jclass,jint buttons,
    jint lx,jint ly,jint rx,jint ry,jint lt,jint rt) {
  auto axis=[](jint v){return static_cast<int16_t>(std::clamp(v,jint(-32767),jint(32767)));};
  port_input::SetVirtualGamepadState(static_cast<uint16_t>(buttons),axis(lx),axis(ly),axis(rx),axis(ry),
      static_cast<uint8_t>(std::clamp(lt,jint(0),jint(255))),
      static_cast<uint8_t>(std::clamp(rt,jint(0),jint(255))));
}
#endif
