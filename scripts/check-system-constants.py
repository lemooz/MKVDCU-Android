"""Validate the current C++ uniform layout against captured shader bodies.

Compiles the actual SystemConstants definition for ARM64 and x86-64, then
replaces ONLY its OpMemberDecorate Offset operands in temporary copies of
captured SPIR-V modules. This validates layout independently of the device;
it does not execute shaders or modify the original captures.
"""
import argparse
from pathlib import Path
import re
import struct
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('ndk', type=Path)
parser.add_argument('captures', type=Path)
args = parser.parse_args()
sdk = Path(__file__).resolve().parents[1] / 'references/rexglue-sdk'
header = (sdk / 'include/rex/graphics/pipeline/shader/spirv_translator.h').read_text()
definition = header[header.index('  struct alignas(16) SystemConstants {'):]
definition = definition[:definition.index('\n  };') + len('\n  };')]
definition = definition.replace('xenos::Endian', 'uint32_t')
translator = (sdk / 'src/graphics/pipeline/shader/spirv_translator.cpp').read_text()
fields = list(dict.fromkeys(re.findall(r'offsetof\(SystemConstants, (\w+)\)', translator)))
source = 'typedef unsigned int uint32_t; typedef int int32_t;\n' + definition
source += '\nextern "C" const unsigned offsets[] = {\n'
source += ',\n'.join(f'__builtin_offsetof(SystemConstants, {field})' for field in fields)
source += '\n};\n'
tools = args.ndk / 'shader-tools/windows-x86_64'
clang = args.ndk / 'toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe'
modules = list(args.captures.glob('*.spirv.bin.*'))
modules = [p for p in modules if not p.name.endswith('.txt')]
assert modules, 'No captured SPIR-V modules'
with tempfile.TemporaryDirectory() as temporary:
    temp = Path(temporary)
    cpp = temp / 'layout.cpp'
    cpp.write_text(source)
    layouts = []
    for arch in ('aarch64', 'x86_64'):
        ir = temp / f'{arch}.ll'
        subprocess.run([str(clang), f'--target={arch}-linux-android28',
                        '-S', '-emit-llvm', '-nostdinc', str(cpp), '-o', str(ir)], check=True)
        line = next(line for line in ir.read_text().splitlines() if line.startswith('@offsets ='))
        layouts.append([int(x) for x in re.findall(r'i32 (\d+)', line)])
    assert layouts[0] == layouts[1] and len(layouts[0]) == len(fields)
    offsets = dict(zip(fields, layouts[0]))
    for path in modules:
        data = path.read_bytes()
        words = list(struct.unpack('<%dI' % (len(data) // 4), data))
        instructions = []
        i = 5
        while i < len(words):
            count, opcode = words[i] >> 16, words[i] & 65535
            assert count and i + count <= len(words)
            instructions.append((i, count, opcode))
            i += count
        def string(start, end):
            return struct.pack('<%dI' % (end - start), *words[start:end]).split(b'\0')[0].decode()
        system_id = next(words[i + 1] for i, n, op in instructions
                         if op == 5 and string(i + 2, i + n) == 'XeSystemConstants')
        names = {words[i + 2]: string(i + 3, i + n) for i, n, op in instructions
                 if op == 6 and words[i + 1] == system_id}
        for i, n, op in instructions:
            if op == 72 and words[i + 1] == system_id and words[i + 3] == 35:
                words[i + 4] = offsets[names[words[i + 2]]]
        patched = temp / path.name
        patched.write_bytes(struct.pack('<%dI' % len(words), *words))
        subprocess.run([str(tools / 'spirv-val.exe'), '--target-env', 'vulkan1.2',
                        str(patched)], check=True)
print(f'PASS: ARM64/x86-64 offsets match; {len(modules)} shader bodies validate without scalar layout')
print(offsets)
