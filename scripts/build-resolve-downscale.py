"""Regenerate the embedded kernel using the NDK shader-tools directory."""
import argparse
from pathlib import Path
import struct
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('shader_tools', type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
base = root / 'references/rexglue-sdk/src/graphics/shaders'
suffix = '.exe' if (args.shader_tools / 'glslc.exe').exists() else ''
with tempfile.TemporaryDirectory() as temporary:
    binary = Path(temporary) / 'resolve_downscale.spv'
    subprocess.run([str(args.shader_tools / ('glslc' + suffix)),
                    '-fshader-stage=compute', '--target-env=vulkan1.0', '-O',
                    str(base / 'resolve_downscale.cs.glsl'), '-o', str(binary)], check=True)
    subprocess.run([str(args.shader_tools / ('spirv-val' + suffix)),
                    '--target-env', 'vulkan1.0', str(binary)], check=True)
    data = binary.read_bytes()
words = struct.unpack('<%dI' % (len(data) // 4), data)
output = ('// Generated from resolve_downscale.cs.glsl with glslc -fshader-stage=compute\n'
          '// --target-env=vulkan1.0 -O. One 128-lane group per guest tile.\n'
          'const uint32_t resolve_downscale_cs[] = {\n')
output += ''.join('    ' + ', '.join(f'0x{w:08X}' for w in words[i:i + 8]) + ',\n'
                  for i in range(0, len(words), 8)) + '};\n'
(base / 'vulkan_spirv/resolve_downscale_cs.h').write_text(output)
print(f'PASS: compiled and validated {len(data)} SPIR-V bytes; header regenerated')
