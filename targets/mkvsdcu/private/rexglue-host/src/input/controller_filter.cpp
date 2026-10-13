#include "controller_filter.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <limits>
#include <map>
#include <mutex>

#include <rex/cvar.h>
#include <rex/platform.h>
#include <rex/ui/virtual_key.h>
#include <rex/input/mnk/mnk_input_driver.h>
#include <rex/input/nop/nop_input_driver.h>
#include <rex/input/sdl/sdl_input_driver.h>
#if REX_PLATFORM_WIN32
#include <rex/input/xinput/xinput_input_driver.h>
#endif

REXCVAR_DEFINE_INT32(pad_left_deadzone, 0, "Port/Controller",
                     "Left stick deadzone in percent (0 leaves it to the game)")
    .range(0, 50);
REXCVAR_DEFINE_INT32(pad_right_deadzone, 0, "Port/Controller",
                     "Right stick deadzone in percent (0 leaves it to the game)")
    .range(0, 50);
REXCVAR_DEFINE_INT32(pad_trigger_deadzone, 0, "Port/Controller", "Trigger deadzone in percent")
    .range(0, 50);
REXCVAR_DEFINE_INT32(pad_vibration, 100, "Port/Controller", "Rumble strength in percent")
    .range(0, 100);
REXCVAR_DEFINE_BOOL(pad_stick_to_dpad, false, "Port/Controller",
                    "Left stick also presses the D-pad");
REXCVAR_DEFINE_BOOL(pad_menu_chord, true, "Port/Controller",
                    "Hold Back + Start to open the port menu");
REXCVAR_DEFINE_STRING(pad_map_a, "A", "Port/Controller", "Game input sent by the A button")
    .allowed({"A", "B", "X", "Y", "LB", "RB", "LT", "RT"});
REXCVAR_DEFINE_STRING(pad_map_b, "B", "Port/Controller", "Game input sent by the B button")
    .allowed({"A", "B", "X", "Y", "LB", "RB", "LT", "RT"});
REXCVAR_DEFINE_STRING(pad_map_x, "X", "Port/Controller", "Game input sent by the X button")
    .allowed({"A", "B", "X", "Y", "LB", "RB", "LT", "RT"});
REXCVAR_DEFINE_STRING(pad_map_y, "Y", "Port/Controller", "Game input sent by the Y button")
    .allowed({"A", "B", "X", "Y", "LB", "RB", "LT", "RT"});
REXCVAR_DEFINE_STRING(pad_map_lb, "LB", "Port/Controller", "Game input sent by the left bumper")
    .allowed({"A", "B", "X", "Y", "LB", "RB", "LT", "RT"});
REXCVAR_DEFINE_STRING(pad_map_rb, "RB", "Port/Controller", "Game input sent by the right bumper")
    .allowed({"A", "B", "X", "Y", "LB", "RB", "LT", "RT"});
REXCVAR_DEFINE_STRING(pad_map_lt, "LT", "Port/Controller", "Game input sent by the left trigger")
    .allowed({"A", "B", "X", "Y", "LB", "RB", "LT", "RT"});
REXCVAR_DEFINE_STRING(pad_map_rt, "RT", "Port/Controller", "Game input sent by the right trigger")
    .allowed({"A", "B", "X", "Y", "LB", "RB", "LT", "RT"});

namespace port_input {
using rex::X_RESULT;
using rex::X_STATUS;
namespace {
using namespace rex::input;
using Clock = std::chrono::steady_clock;

constexpr int kControlCount = 8;
constexpr int kLT = 6, kRT = 7;
constexpr uint16_t kControlBits[kControlCount] = {
    X_INPUT_GAMEPAD_A,           X_INPUT_GAMEPAD_B,
    X_INPUT_GAMEPAD_X,           X_INPUT_GAMEPAD_Y,
    X_INPUT_GAMEPAD_LEFT_SHOULDER, X_INPUT_GAMEPAD_RIGHT_SHOULDER, 0, 0};
// VK_PAD_* codes for the same controls, as seen by XInputGetKeystroke.
constexpr uint16_t kControlKeys[kControlCount] = {0x5800, 0x5801, 0x5802, 0x5803,
                                                  0x5805, 0x5804, 0x5806, 0x5807};
constexpr uint8_t kTriggerPressThreshold = 30;  // XINPUT_GAMEPAD_TRIGGER_THRESHOLD

struct PadRecord {
  PadSnapshot snapshot;
  Clock::time_point polled;
};
std::mutex g_pads_mutex;
std::map<uint64_t, PadRecord> g_pads;
// Publish all seven channels as one coherent input sample. Independent atomics
// can otherwise mix the previous button sample with new triggers/stick axes.
std::mutex g_virtual_mutex;
X_INPUT_GAMEPAD g_virtual_state{};

uint64_t VirtualKeystrokeBits(const X_INPUT_GAMEPAD& state) {
  uint64_t bits=static_cast<uint16_t>(state.buttons);
  if(state.left_trigger>kTriggerPressThreshold)bits|=UINT64_C(1)<<16;
  if(state.right_trigger>kTriggerPressThreshold)bits|=UINT64_C(1)<<17;
  auto direction=[&](int16_t x,int16_t y,unsigned shift) {
    constexpr int threshold=16384;
    int d=-1;
    if(y>threshold)d=x < -threshold ? 4 : x > threshold ? 5 : 0;
    else if(y < -threshold)d=x > threshold ? 6 : x < -threshold ? 7 : 1;
    else if(x>threshold)d=2;
    else if(x < -threshold)d=3;
    if(d>=0)bits|=UINT64_C(1)<<(shift+unsigned(d));
  };
  direction(state.thumb_lx,state.thumb_ly,18);
  direction(state.thumb_rx,state.thumb_ry,26);
  return bits;
}

class VirtualTouchGamepadDriver final : public InputDriver {
 public:
  VirtualTouchGamepadDriver() : InputDriver(nullptr, 0) {}

  X_STATUS Setup() override { return X_STATUS_SUCCESS; }

  void EnumerateDevices(std::vector<DeviceInfo>& out) override {
    DeviceInfo info;
    info.id = static_cast<DeviceId>(0x4D4B564443555041ull);  // Stable synthetic device id.
    info.name = "MKVDCU Virtual Gamepad (Touch)";
    info.guid = "4d4b5644435550410000000000000000";
    info.synthetic = true;
    out.push_back(std::move(info));
  }

  X_RESULT GetDeviceState(DeviceId, X_INPUT_STATE* out_state) override {
    if (!out_state) return X_ERROR_BAD_ARGUMENTS;
    const bool enabled = is_active();
    X_INPUT_GAMEPAD state{};
    if (enabled) {
      std::lock_guard lock(g_virtual_mutex);
      state=g_virtual_state;
    }
    std::lock_guard state_lock(state_mutex_);
    if (std::memcmp(&last_state_, &state, sizeof(state)) != 0) {
      last_state_ = state;
      ++packet_;
    }
    out_state->packet_number = packet_;
    out_state->gamepad = state;
    return X_ERROR_SUCCESS;
  }

  X_RESULT GetDeviceCapabilities(DeviceId, uint32_t, X_INPUT_CAPABILITIES* out_caps) override {
    if (!out_caps) return X_ERROR_BAD_ARGUMENTS;
    std::memset(out_caps, 0, sizeof(*out_caps));
    out_caps->type = 0x01;
    out_caps->sub_type = 0x01;
    out_caps->gamepad.buttons = 0xF7FF;
    out_caps->gamepad.left_trigger = 0xFF;
    out_caps->gamepad.right_trigger = 0xFF;
    out_caps->gamepad.thumb_lx = out_caps->gamepad.thumb_ly = 0x7FFF;
    out_caps->gamepad.thumb_rx = out_caps->gamepad.thumb_ry = 0x7FFF;
    return X_ERROR_SUCCESS;
  }

  X_RESULT SetDeviceVibration(DeviceId, X_INPUT_VIBRATION*) override { return X_ERROR_SUCCESS; }
  X_RESULT GetDeviceKeystroke(DeviceId, uint32_t, X_INPUT_KEYSTROKE* out) override {
    if (!out) return X_ERROR_BAD_ARGUMENTS;

    static constexpr std::pair<uint64_t, rex::ui::VirtualKey> kKeys[] = {
        {X_INPUT_GAMEPAD_DPAD_UP, rex::ui::VirtualKey::kXInputPadDpadUp},
        {X_INPUT_GAMEPAD_DPAD_DOWN, rex::ui::VirtualKey::kXInputPadDpadDown},
        {X_INPUT_GAMEPAD_DPAD_LEFT, rex::ui::VirtualKey::kXInputPadDpadLeft},
        {X_INPUT_GAMEPAD_DPAD_RIGHT, rex::ui::VirtualKey::kXInputPadDpadRight},
        {X_INPUT_GAMEPAD_START, rex::ui::VirtualKey::kXInputPadStart},
        {X_INPUT_GAMEPAD_BACK, rex::ui::VirtualKey::kXInputPadBack},
        {X_INPUT_GAMEPAD_LEFT_THUMB, rex::ui::VirtualKey::kXInputPadLThumbPress},
        {X_INPUT_GAMEPAD_RIGHT_THUMB, rex::ui::VirtualKey::kXInputPadRThumbPress},
        {UINT64_C(1)<<16, rex::ui::VirtualKey::kXInputPadLTrigger},
        {UINT64_C(1)<<17, rex::ui::VirtualKey::kXInputPadRTrigger},
        {UINT64_C(1)<<18, rex::ui::VirtualKey::kXInputPadLThumbUp},
        {UINT64_C(1)<<19, rex::ui::VirtualKey::kXInputPadLThumbDown},
        {UINT64_C(1)<<20, rex::ui::VirtualKey::kXInputPadLThumbRight},
        {UINT64_C(1)<<21, rex::ui::VirtualKey::kXInputPadLThumbLeft},
        {UINT64_C(1)<<22, rex::ui::VirtualKey::kXInputPadLThumbUpLeft},
        {UINT64_C(1)<<23, rex::ui::VirtualKey::kXInputPadLThumbUpRight},
        {UINT64_C(1)<<24, rex::ui::VirtualKey::kXInputPadLThumbDownRight},
        {UINT64_C(1)<<25, rex::ui::VirtualKey::kXInputPadLThumbDownLeft},
        {UINT64_C(1)<<26, rex::ui::VirtualKey::kXInputPadRThumbUp},
        {UINT64_C(1)<<27, rex::ui::VirtualKey::kXInputPadRThumbDown},
        {UINT64_C(1)<<28, rex::ui::VirtualKey::kXInputPadRThumbRight},
        {UINT64_C(1)<<29, rex::ui::VirtualKey::kXInputPadRThumbLeft},
        {UINT64_C(1)<<30, rex::ui::VirtualKey::kXInputPadRThumbUpLeft},
        {UINT64_C(1)<<31, rex::ui::VirtualKey::kXInputPadRThumbUpRight},
        {UINT64_C(1)<<32, rex::ui::VirtualKey::kXInputPadRThumbDownRight},
        {UINT64_C(1)<<33, rex::ui::VirtualKey::kXInputPadRThumbDownLeft},
        {X_INPUT_GAMEPAD_LEFT_SHOULDER, rex::ui::VirtualKey::kXInputPadLShoulder},
        {X_INPUT_GAMEPAD_RIGHT_SHOULDER, rex::ui::VirtualKey::kXInputPadRShoulder},
        {X_INPUT_GAMEPAD_A, rex::ui::VirtualKey::kXInputPadA},
        {X_INPUT_GAMEPAD_B, rex::ui::VirtualKey::kXInputPadB},
        {X_INPUT_GAMEPAD_X, rex::ui::VirtualKey::kXInputPadX},
        {X_INPUT_GAMEPAD_Y, rex::ui::VirtualKey::kXInputPadY},
    };

    std::lock_guard lock(keystroke_mutex_);
    X_INPUT_GAMEPAD sample{};
    if(is_active()){std::lock_guard state_lock(g_virtual_mutex);sample=g_virtual_state;}
    const uint64_t current=VirtualKeystrokeBits(sample);
    for (const auto& [button, key] : kKeys) {
      const bool was_down = (keystroke_buttons_ & button) != 0;
      const bool is_down = (current & button) != 0;
      if (was_down == is_down) continue;

      out->virtual_key = static_cast<uint16_t>(key);
      out->unicode = 0;
      out->flags = is_down ? X_INPUT_KEYSTROKE_KEYDOWN : X_INPUT_KEYSTROKE_KEYUP;
      out->user_index = 0;  // InputSystem overwrites this with the assigned user.
      out->hid_code = 0;
      if (is_down) {
        keystroke_buttons_ |= button;
      } else {
        keystroke_buttons_ &= ~button;
      }
      REXLOG_INFO("[VIRTUAL_PAD][KEYSTROKE] vk=0x{:04X} flags=0x{:04X} keys=0x{:X}",
                  static_cast<uint16_t>(key), static_cast<uint16_t>(out->flags), current);
      return X_ERROR_SUCCESS;
    }
    return X_ERROR_EMPTY;
  }

 private:
  X_INPUT_GAMEPAD last_state_{};
  std::mutex state_mutex_;
  uint32_t packet_ = 0;
  std::mutex keystroke_mutex_;
  uint64_t keystroke_buttons_ = 0;
};

int ControlIndex(const std::string& name) {
  for (int i = 0; i < kControlCount; ++i) {
    if (name == kControlNames[i]) return i;
  }
  return -1;
}

// Where each physical control goes, read fresh so menu changes apply live.
std::array<int, kControlCount> CurrentMap() {
  std::array<int, kControlCount> map;
  for (int i = 0; i < kControlCount; ++i) {
    const int target = ControlIndex(rex::cvar::GetFlagByName(kMapCvars[i]));
    map[i] = target < 0 ? i : target;
  }
  return map;
}

void ApplyStickDeadzone(int16_t& x, int16_t& y, int percent) {
  if (percent <= 0) return;
  const double fx = x / 32767.0, fy = y / 32767.0;
  const double magnitude = std::min(1.0, std::sqrt(fx * fx + fy * fy));
  const double deadzone = percent / 100.0;
  if (magnitude <= deadzone) {
    x = y = 0;
    return;
  }
  // Rescale so movement starts at zero just outside the deadzone.
  const double scale = (magnitude - deadzone) / (1.0 - deadzone) / magnitude;
  x = int16_t(std::clamp(fx * scale, -1.0, 1.0) * 32767.0);
  y = int16_t(std::clamp(fy * scale, -1.0, 1.0) * 32767.0);
}

uint8_t ApplyTriggerDeadzone(uint8_t value, int percent) {
  const int deadzone = percent * 255 / 100;
  if (value <= deadzone) return 0;
  return uint8_t((value - deadzone) * 255 / (255 - deadzone));
}

void Transform(X_INPUT_GAMEPAD& pad) {
  uint16_t buttons = pad.buttons;
  uint8_t lt = pad.left_trigger, rt = pad.right_trigger;
  int16_t lx = pad.thumb_lx, ly = pad.thumb_ly, rx = pad.thumb_rx, ry = pad.thumb_ry;

  ApplyStickDeadzone(lx, ly, REXCVAR_GET(pad_left_deadzone));
  ApplyStickDeadzone(rx, ry, REXCVAR_GET(pad_right_deadzone));
  lt = ApplyTriggerDeadzone(lt, REXCVAR_GET(pad_trigger_deadzone));
  rt = ApplyTriggerDeadzone(rt, REXCVAR_GET(pad_trigger_deadzone));

  const auto map = CurrentMap();
  uint8_t source[kControlCount];
  for (int i = 0; i < kControlCount; ++i) source[i] = (buttons & kControlBits[i]) ? 255 : 0;
  source[kLT] = lt;
  source[kRT] = rt;

  uint16_t out_buttons = buttons;
  for (int i = 0; i < kControlCount; ++i) out_buttons &= ~kControlBits[i];
  uint8_t out_trigger[2] = {0, 0};
  for (int i = 0; i < kControlCount; ++i) {
    const uint8_t value = source[i];
    if (!value) continue;
    const int target = map[i];
    if (target == kLT || target == kRT) {
      out_trigger[target - kLT] = std::max(out_trigger[target - kLT], value);
    } else if (value > kTriggerPressThreshold) {
      out_buttons |= kControlBits[target];
    }
  }

  if (REXCVAR_GET(pad_stick_to_dpad)) {
    constexpr int16_t kThreshold = 16384;
    if (lx < -kThreshold) out_buttons |= X_INPUT_GAMEPAD_DPAD_LEFT;
    if (lx > kThreshold) out_buttons |= X_INPUT_GAMEPAD_DPAD_RIGHT;
    if (ly > kThreshold) out_buttons |= X_INPUT_GAMEPAD_DPAD_UP;
    if (ly < -kThreshold) out_buttons |= X_INPUT_GAMEPAD_DPAD_DOWN;
  }

  pad.buttons = out_buttons;
  pad.left_trigger = out_trigger[0];
  pad.right_trigger = out_trigger[1];
  pad.thumb_lx = lx;
  pad.thumb_ly = ly;
  pad.thumb_rx = rx;
  pad.thumb_ry = ry;
}

class FilterDriver final : public InputDriver {
 public:
  FilterDriver(std::unique_ptr<InputDriver> inner, std::function<void()> on_menu_chord)
      : InputDriver(nullptr, 0), inner_(std::move(inner)), on_menu_chord_(std::move(on_menu_chord)) {
    // The inner driver only refreshes a pad while it thinks input is active.
    // Keep it fresh during our own polls so the menu can read the controller,
    // and hide the result from the game here instead.
    inner_->set_is_active_callback([this] { return polling_raw_ || is_active(); });
  }

  X_STATUS Setup() override { return inner_->Setup(); }

  void EnumerateDevices(std::vector<DeviceInfo>& out) override {
    const size_t first = out.size();
    inner_->EnumerateDevices(out);
    std::lock_guard lock(mutex_);
    for (size_t i = first; i < out.size(); ++i) names_[uint64_t(out[i].id)] = out[i].name;
  }

  X_RESULT GetDeviceState(DeviceId id, X_INPUT_STATE* out_state) override {
    X_RESULT result;
    {
      std::lock_guard lock(mutex_);
      polling_raw_ = true;
      result = inner_->GetDeviceState(id, out_state);
      polling_raw_ = false;
    }
    if (result != X_ERROR_SUCCESS) return result;

    X_INPUT_GAMEPAD& pad = out_state->gamepad;
    Record(id, pad);
    CheckMenuChord(id, pad.buttons);

    std::lock_guard lock(mutex_);
    auto& device = devices_[uint64_t(id)];
    if (!is_active()) {
      std::memset(&pad, 0, sizeof(pad));
    } else {
      // Holding Back and Start together is the menu chord; keep the game from
      // pausing on it.
      const uint16_t chord = X_INPUT_GAMEPAD_BACK | X_INPUT_GAMEPAD_START;
      if (REXCVAR_GET(pad_menu_chord) && (pad.buttons & chord) == chord) {
        pad.buttons = uint16_t(pad.buttons & ~chord);
      }
      Transform(pad);
    }
    // The packet number must change whenever what the game sees changes.
    if (std::memcmp(&device.last, &pad, sizeof(pad)) != 0) {
      ++device.packet;
      device.last = pad;
    }
    out_state->packet_number = device.packet;
    return X_ERROR_SUCCESS;
  }

  X_RESULT GetDeviceCapabilities(DeviceId id, uint32_t flags,
                                 X_INPUT_CAPABILITIES* out_caps) override {
    return inner_->GetDeviceCapabilities(id, flags, out_caps);
  }

  X_RESULT SetDeviceVibration(DeviceId id, X_INPUT_VIBRATION* vibration) override {
    X_INPUT_VIBRATION scaled = *vibration;
    const uint32_t strength = uint32_t(std::clamp(REXCVAR_GET(pad_vibration), 0, 100));
    scaled.left_motor_speed = uint16_t(uint32_t(vibration->left_motor_speed) * strength / 100);
    scaled.right_motor_speed = uint16_t(uint32_t(vibration->right_motor_speed) * strength / 100);
    return inner_->SetDeviceVibration(id, &scaled);
  }

  X_RESULT GetDeviceKeystroke(DeviceId id, uint32_t flags, X_INPUT_KEYSTROKE* out) override {
    const X_RESULT result = inner_->GetDeviceKeystroke(id, flags, out);
    if (result != X_ERROR_SUCCESS) return result;
    const auto map = CurrentMap();
    const uint16_t key = out->virtual_key;
    for (int i = 0; i < kControlCount; ++i) {
      if (key == kControlKeys[i]) {
        out->virtual_key = kControlKeys[map[i]];
        break;
      }
    }
    return result;
  }

  void OnWindowAvailable(rex::ui::Window* window) override { inner_->OnWindowAvailable(window); }

 private:
  struct DeviceOutput {
    X_INPUT_GAMEPAD last = {};
    uint32_t packet = 0;
  };

  void Record(DeviceId id, const X_INPUT_GAMEPAD& pad) {
    PadSnapshot snapshot;
    {
      std::lock_guard lock(mutex_);
      snapshot.name = names_[uint64_t(id)];
    }
    snapshot.buttons = pad.buttons;
    snapshot.left_trigger = pad.left_trigger;
    snapshot.right_trigger = pad.right_trigger;
    snapshot.thumb_lx = pad.thumb_lx;
    snapshot.thumb_ly = pad.thumb_ly;
    snapshot.thumb_rx = pad.thumb_rx;
    snapshot.thumb_ry = pad.thumb_ry;
    std::lock_guard lock(g_pads_mutex);
    g_pads[uint64_t(id)] = {snapshot, Clock::now()};
  }

  void CheckMenuChord(DeviceId id, uint16_t buttons) {
    const uint16_t chord = X_INPUT_GAMEPAD_BACK | X_INPUT_GAMEPAD_START;
    std::lock_guard lock(mutex_);
    auto& state = chords_[uint64_t(id)];
    if (!REXCVAR_GET(pad_menu_chord) || (buttons & chord) != chord) {
      state.held = false;
      state.fired = false;
      return;
    }
    const auto now = Clock::now();
    if (!state.held) {
      state.held = true;
      state.since = now;
    } else if (!state.fired && now - state.since >= std::chrono::milliseconds(600)) {
      state.fired = true;
      if (on_menu_chord_) on_menu_chord_();
    }
  }

  struct ChordState {
    bool held = false;
    bool fired = false;
    Clock::time_point since;
  };

  std::unique_ptr<InputDriver> inner_;
  std::function<void()> on_menu_chord_;
  std::mutex mutex_;
  bool polling_raw_ = false;
  std::map<uint64_t, std::string> names_;
  std::map<uint64_t, DeviceOutput> devices_;
  std::map<uint64_t, ChordState> chords_;
};

void AddFiltered(InputSystem& input, std::unique_ptr<InputDriver> driver,
                 const std::function<void()>& on_menu_chord) {
  if (driver->Setup() != X_STATUS_SUCCESS) return;
  input.AddDriver(std::make_unique<FilterDriver>(std::move(driver), on_menu_chord));
}
}  // namespace

std::unique_ptr<InputSystem> CreateInputSystem(bool tool_mode, std::function<void()> on_menu_chord) {
  auto input = std::make_unique<InputSystem>(nullptr);
  if (!tool_mode) {
    const std::string backend = rex::cvar::GetFlagByName("input_backend");
#if REX_PLATFORM_WIN32
    if (backend == "xinput") {
      AddFiltered(*input, std::make_unique<xinput::XinputInputDriver>(nullptr, 0), on_menu_chord);
    }
    if (backend == "sdl") {
      AddFiltered(*input, std::make_unique<sdl::SDLInputDriver>(nullptr, 0), on_menu_chord);
    }
#else
    AddFiltered(*input, std::make_unique<sdl::SDLInputDriver>(nullptr, 0), on_menu_chord);
#endif
    auto virtual_pad = std::make_unique<VirtualTouchGamepadDriver>();
    if (virtual_pad->Setup() == X_STATUS_SUCCESS) input->AddDriver(std::move(virtual_pad));
    auto mnk_driver = std::make_unique<mnk::MnkInputDriver>(nullptr, 0);
    if (mnk_driver->Setup() == X_STATUS_SUCCESS) input->AddDriver(std::move(mnk_driver));
  }
  const uint8_t nop_index = tool_mode ? 0 : 1;
  input->AddDriver(std::make_unique<nop::NopInputDriver>(nullptr, nop_index));
  input->SetDeviceAssignment(std::make_unique<SlotAssignment>());
  return input;
}

std::vector<PadSnapshot> ConnectedPads() {
  std::vector<PadSnapshot> pads;
  const auto now = Clock::now();
  std::lock_guard lock(g_pads_mutex);
  for (auto it = g_pads.begin(); it != g_pads.end();) {
    const auto age = std::chrono::duration_cast<std::chrono::milliseconds>(now - it->second.polled);
    if (age > std::chrono::seconds(3)) {
      it = g_pads.erase(it);
      continue;
    }
    PadSnapshot snapshot = it->second.snapshot;
    snapshot.age_ms = uint32_t(age.count());
    pads.push_back(std::move(snapshot));
    ++it;
  }
  return pads;
}

void SetVirtualGamepadState(uint16_t buttons,int16_t thumb_lx,int16_t thumb_ly,
                           int16_t thumb_rx,int16_t thumb_ry,
                           uint8_t left_trigger,uint8_t right_trigger) {
  static std::atomic<uint16_t> logged_buttons{0xFFFF};
  static std::atomic<uint32_t> logged_axes{std::numeric_limits<uint32_t>::max()};
  {
    std::lock_guard lock(g_virtual_mutex);
    g_virtual_state.buttons=buttons;g_virtual_state.thumb_lx=thumb_lx;g_virtual_state.thumb_ly=thumb_ly;
    g_virtual_state.thumb_rx=thumb_rx;g_virtual_state.thumb_ry=thumb_ry;
    g_virtual_state.left_trigger=left_trigger;g_virtual_state.right_trigger=right_trigger;
  }
  // Quantize stick diagnostics to avoid per-motion log spam. Pack unsigned
  // halves so negative axes never undergo an undefined signed left shift.
  const uint32_t qx = static_cast<uint16_t>(static_cast<int32_t>(thumb_lx) / 4096);
  const uint32_t qy = static_cast<uint16_t>(static_cast<int32_t>(thumb_ly) / 4096);
  const uint32_t axes = (qx << 16) | qy;
  const bool buttons_changed = logged_buttons.exchange(buttons, std::memory_order_relaxed) != buttons;
  const bool axes_changed = logged_axes.exchange(axes, std::memory_order_relaxed) != axes;
  static std::atomic<uint32_t> logged_extra{0};
  const uint32_t extra=(uint32_t(left_trigger)<<24)|(uint32_t(right_trigger)<<16)|
      (uint32_t(uint8_t(int32_t(thumb_rx)/4096))<<8)|uint8_t(int32_t(thumb_ry)/4096);
  const bool extra_changed=logged_extra.exchange(extra,std::memory_order_relaxed)!=extra;
  if (buttons_changed || axes_changed || extra_changed) {
    REXLOG_INFO("[VIRTUAL_PAD] buttons=0x{:04X} lx={} ly={} rx={} ry={} lt={} rt={}",
                buttons,thumb_lx,thumb_ly,thumb_rx,thumb_ry,left_trigger,right_trigger);
  }
}

}  // namespace port_input
