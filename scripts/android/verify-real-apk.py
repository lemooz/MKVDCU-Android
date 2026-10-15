"""Reject probe JNI or stale native libraries before installing a game APK."""
import hashlib
import json
import struct
import sys
import zipfile
from pathlib import Path


def inspect_elf(data):
    if data[:6] != b'\x7fELF\x02\x01':
        raise ValueError('Expected little-endian ELF64')
    header = struct.unpack_from('<16sHHIQQQIHHHHHH', data)
    if header[2] != 183:
        raise ValueError('Expected AArch64')
    offset, stride, count, names_index = header[6], header[11], header[12], header[13]
    sections = [struct.unpack_from('<IIQQQQIIQQ', data, offset + i * stride)
                for i in range(count)]
    names_section = sections[names_index]
    names = data[names_section[4]:names_section[4] + names_section[5]]
    executable = hashlib.sha256()
    build_id = None
    exports = set()
    dynamic_flags_1 = 0
    for section in sections:
        name = names[section[0]:].split(b'\0', 1)[0].decode()
        content = data[section[4]:section[4] + section[5]]
        if section[2] & 4:  # SHF_EXECINSTR survives Gradle's debug stripping.
            executable.update(name.encode() + b'\0' + content)
        if name == '.note.gnu.build-id':
            names_size, desc_size, note_type = struct.unpack_from('<III', content)
            desc_start = 12 + ((names_size + 3) & ~3)
            if note_type == 3:
                build_id = content[desc_start:desc_start + desc_size].hex()
        if section[1] == 11:  # SHT_DYNSYM
            strings_section = sections[section[6]]
            strings = data[strings_section[4]:strings_section[4] + strings_section[5]]
            for pos in range(0, len(content), section[9]):
                sym_name, info, other, defined, value, size = struct.unpack_from('<IBBHQQ', content, pos)
                if defined:
                    end = strings.find(b'\0', sym_name)
                    exports.add(strings[sym_name:end].decode())
        if section[1] == 6:  # SHT_DYNAMIC
            for pos in range(0, len(content), 16):
                tag, value = struct.unpack_from('<QQ', content, pos)
                if tag == 0: break
                if tag == 0x6ffffffb: dynamic_flags_1 = value
    if not build_id:
        raise ValueError('ELF lacks Build ID')
    return {'sha256': hashlib.sha256(data).hexdigest(), 'build_id': build_id,
            'executable_sha256': executable.hexdigest(), 'dynamic_flags_1': dynamic_flags_1}, exports


def main():
    root, apk, manifest = map(Path, sys.argv[1:])
    native = root / 'android/app/src/main/jniLibs/arm64-v8a'
    report = {'apk': str(apk), 'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
              'libraries': {}}
    with zipfile.ZipFile(apk) as archive:
        for name in ('libmkvdcu.so', 'librexruntime.so', 'librexgpu-xenos.so',
                     'libmain_hook.so', 'libhook_impl.so', 'libfile_redirect_hook.so', 'libgsl_alloc_hook.so',
                     'libpanvk_loader_hook.so', 'libpanvk_mapper_hook.so'):
            staged, exports = inspect_elf((native / name).read_bytes())
            packaged, _ = inspect_elf(archive.read('lib/arm64-v8a/' + name))
            for key in ('build_id', 'executable_sha256'):
                if staged[key] != packaged[key]:
                    raise ValueError(f'{name}: packaged/staged {key} mismatch')
            if name == 'libmkvdcu.so':
                if 'GImGui' in exports or any('ImGui' in symbol and 'GetCurrentContext' in symbol
                                             for symbol in exports):
                    raise ValueError('Game duplicates runtime ImGui state')
                required = {'SDL_main',
                            'Java_org_mkvdcu_android_GameActivity_nativeSetVirtualGamepadState',
                            'Java_org_mkvdcu_android_GameActivity_nativeSetVirtualGamepadStateV2',
                            'Java_org_mkvdcu_android_GameActivity_nativeReadHealthSnapshot',
                            'Java_org_mkvdcu_android_GameActivity_nativeReadFrameIntervals',
                            'Java_org_mkvdcu_android_GameActivity_nativeReadPerformanceSnapshot'}
                if missing := required - exports:
                    raise ValueError(f'Game JNI missing: {missing}')
            if name == 'librexruntime.so' and not any('HealthSummary' in symbol for symbol in exports):
                raise ValueError('Runtime lacks shared health implementation')
            if name == 'librexruntime.so' and 'GImGui' not in exports:
                raise ValueError('Runtime must own the single ImGui context')
            if name == 'librexruntime.so':
                required = {'Java_org_mkvdcu_android_DriverNative_configure',
                            'Java_org_mkvdcu_android_DriverNative_probe',
                            'Java_org_mkvdcu_android_DriverNative_identity',
                            'Java_org_mkvdcu_android_DriverNative_configureCpuLimit',
                            'Java_org_mkvdcu_android_DriverNative_presentCount',
                            'Java_org_mkvdcu_android_DriverNative_setPresentPreWaitBenchmark',
                            'Java_org_mkvdcu_android_DriverNative_setPresentQueueBenchmark',
                            'Java_org_mkvdcu_android_DriverNative_setDiagnosticMode'}
                if missing := required - exports:
                    raise ValueError(f'Driver JNI missing: {missing}')
            if name == 'librexgpu-xenos.so' and not {'Java_org_mkvdcu_android_DriverNative_armCapture', 'Java_org_mkvdcu_android_DriverNative_armRenderExtentProbe'}.issubset(exports):
                raise ValueError('GPU library lacks the bounded frame capture JNI')
            if name == 'libpanvk_loader_hook.so':
                required = {'mkvdcu_panvk_initialize_loader_hook', 'android_dlopen_ext', 'android_load_sphal_library'}
                if missing := required - exports:
                    raise ValueError(f'PanVK HAL/WSI bridge missing: {missing}')
                if not packaged['dynamic_flags_1'] & 2:  # DF_1_GLOBAL
                    raise ValueError('PanVK bridge lacks global interposition flag')
                if 'HMI' not in exports or 'mkvdcu_panvk_set_hal_module' not in exports:
                    raise ValueError('PanVK native-buffer HAL proxy missing')
            if name == 'libpanvk_mapper_hook.so':
                required = {'mkvdcu_panvk_initialize_mapper_hook', 'dlopen', 'access', 'android_load_sphal_library'}
                if required - exports or 'HMI' in exports or not packaged['dynamic_flags_1'] & 2:
                    raise ValueError('PanVK mapper bridge exports/isolation invalid')
            report['libraries'][name] = {'staged': staged, 'packaged': packaged}
    manifest.write_text(json.dumps(report, indent=2), encoding='utf-8')
    print('REAL APK VERIFIED:', report['apk_sha256'])


if __name__ == '__main__':
    main()
