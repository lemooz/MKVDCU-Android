"""Host test of the production HAL selector/forwarding, not Android WSI."""
import os
import pathlib
import subprocess
import tempfile

root = pathlib.Path(__file__).resolve().parents[1]
vs = pathlib.Path('C:/Program Files/Microsoft Visual Studio/18/Community/VC/Tools/MSVC')
compiler = sorted(vs.glob('*/bin/Hostx64/x64/cl.exe'))[-1]
msvc = compiler.parents[3]
sdk = pathlib.Path('C:/Program Files (x86)/Windows Kits/10')
version = sorted((sdk/'Include').glob('10.*'))[-1].name
env = dict(os.environ)
env['PATH'] = str(compiler.parent) + os.pathsep + env['PATH']
env['INCLUDE'] = os.pathsep.join(str(p) for p in
    [msvc/'include', sdk/'Include'/version/'ucrt', sdk/'Include'/version/'shared', sdk/'Include'/version/'um'])
env['LIB'] = os.pathsep.join(str(p) for p in
    [msvc/'lib/x64', sdk/'Lib'/version/'ucrt/x64', sdk/'Lib'/version/'um/x64'])
source = root/'MKVDCU-Recomp/references/rexglue-sdk/src/ui/android_driver/panvk_loader_hook.cpp'
with tempfile.TemporaryDirectory(prefix='mkvdcu-panvk-hook-') as tmp:
    path = pathlib.Path(tmp)
    (path/'android').mkdir()
    (path/'android/dlext.h').write_text('#pragma once\nstruct android_dlextinfo { int diagnostic; };\n')
    (path/'test.cpp').write_text('''#include <cassert>
#include <cstdint>
#include <cstring>
#include <initializer_list>
#include <cerrno>
// Windows has no Android loader: only provide the type and visibility macro.
#define __attribute__(x)
#include "''' + source.as_posix() + '''"
int calls = 0, passed_flags = 0;
bool named_mapper_exists = false;
const char* passed_path = nullptr;
const android_dlextinfo* passed_info = nullptr;
void* OriginalExt(const char*, int flags, const android_dlextinfo* info) {
  ++calls; passed_flags = flags; passed_info = info; return (void*)uintptr_t(0x222);
}
void* OriginalSphal(const char* path, int flags) { ++calls; passed_path = path; passed_flags = flags; return (void*)uintptr_t(0x333); }
int OriginalAccess(const char* path, int) {
  if (!std::strcmp(path, "/vendor/lib64/hw/android.hardware.graphics.mapper@4.0-impl.so")) return 0;
  if (named_mapper_exists && !std::strcmp(path, "/vendor/lib64/hw/android.hardware.graphics.mapper@4.0-impl-default.so")) return 0;
  errno = ENOENT; return -1;
}
int main() {
  assert(android_dlopen_ext("vulkan.mali.so", 1, nullptr) == nullptr);
  mkvdcu_panvk_initialize_loader_hook((void*)uintptr_t(0x111), OriginalExt, OriginalSphal);
  for (auto name : {"vulkan.mali.so", "/vendor/lib64/hw/vulkan.exynos.so", "vulkan.default.so"}) {
    assert(android_dlopen_ext(name, 1, nullptr) == (void*)uintptr_t(0x111));
    assert(android_load_sphal_library(name, 1) == (void*)uintptr_t(0x111));
  }
  assert(calls == 0);
  android_dlextinfo info{42};
  for (auto name : {"libvulkan.so", "libhardware.so", "vulkan.mali.so.backup", "", "libvulkan_panfrost.so"}) {
    assert(android_dlopen_ext(name, 123, &info) == (void*)uintptr_t(0x222));
    assert(passed_flags == 123 && passed_info == &info);
    assert(android_load_sphal_library(name, 456) == (void*)uintptr_t(0x333));
    assert(passed_flags == 456);
  }
  assert(android_dlopen_ext(nullptr, 789, &info) == (void*)uintptr_t(0x222));
  mkvdcu_panvk_initialize_loader_hook((void*)uintptr_t(0x111), OriginalExt, nullptr);
  assert(android_load_sphal_library("libhardware.so", 999) == (void*)uintptr_t(0x222));
  assert(passed_flags == 999 && passed_info == nullptr);
  mkvdcu_panvk_initialize_mapper_hook((void*)uintptr_t(0x444), OriginalExt, OriginalSphal, OriginalAccess);
  assert(dlopen("libvndksupport.so", 1) == (void*)uintptr_t(0x444));
  const char* alias = "/vendor/lib64/hw/android.hardware.graphics.mapper@4.0-impl-default.so";
  assert(access(alias, 0) == 0);
  assert(android_load_sphal_library(alias, 123) == (void*)uintptr_t(0x333));
  assert(!std::strcmp(passed_path, "/vendor/lib64/hw/android.hardware.graphics.mapper@4.0-impl.so"));
  assert(passed_flags == 123);
  named_mapper_exists = true;
  android_load_sphal_library(alias, 456);
  assert(!std::strcmp(passed_path, alias));
  assert(access("/odm/lib64/hw/android.hardware.graphics.mapper@4.0-impl-default.so", 0) == -1);
  assert(access("/vendor/lib64/hw/unrelated.so", 0) == -1);
  android_load_sphal_library("mapper.mediatek.so", 789);
  assert(!std::strcmp(passed_path, "mapper.mediatek.so"));
}
''', encoding='utf-8')
    subprocess.run([str(compiler),'/nologo','/EHsc','/std:c++17','/I'+tmp,
                    '/Fe:'+str(path/'test.exe'), str(path/'test.cpp')], cwd=tmp, env=env, check=True)
    subprocess.run([str(path/'test.exe')], check=True)
print('PASS production PanVK HAL selector and original-loader forwarding (host only)')
