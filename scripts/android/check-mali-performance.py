"""Offline regressions for ring backoff and compact-texture Vulkan execution."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
sdk = root/'references/rexglue-sdk'
compiler = sorted(Path('C:/Program Files/Microsoft Visual Studio/18/Community/VC/Tools/MSVC').glob('*/bin/Hostx64/x64/cl.exe'))[-1]
msvc = compiler.parents[3]
winsdk = Path('C:/Program Files (x86)/Windows Kits/10')
version = sorted((winsdk/'Include').glob('10.*'))[-1].name
env = dict(os.environ)
env['PATH'] = str(compiler.parent)+os.pathsep+env['PATH']
env['INCLUDE'] = os.pathsep.join(str(p) for p in [msvc/'include', winsdk/'Include'/version/'ucrt', winsdk/'Include'/version/'shared', winsdk/'Include'/version/'um'])
env['LIB'] = os.pathsep.join(str(p) for p in [msvc/'lib/x64', winsdk/'Lib'/version/'ucrt/x64', winsdk/'Lib'/version/'um/x64'])
vulkan = next(sdk.glob('thirdparty/**/vulkan/vulkan.h')).parents[1]
with tempfile.TemporaryDirectory(prefix='mkvdcu-mali-regression-') as temporary:
    directory = Path(temporary)
    source = directory/'backoff.cpp'
    source.write_text(r'''#include <cassert>
#include "targets/mkvsdcu/private/rexglue-host/src/ring_wait_backoff.h"
int main() {
  ring_wait::Backoff b;
  for (int i=0;i<4;++i) assert(b.DelayMicroseconds(128, i)==0);
  assert(b.DelayMicroseconds(128,4)==20);
  assert(b.DelayMicroseconds(128,24)==50);
  assert(b.DelayMicroseconds(128,74)==100);
  assert(b.DelayMicroseconds(128,174)==200);
  for (int i=1;i<=100;++i) assert(b.DelayMicroseconds(128,174+i*200)==200);
  assert(b.DelayMicroseconds(132,20175)==0); // GPU read-pointer progress
  for (int i=1;i<4;++i) assert(b.DelayMicroseconds(132,20175+i)==0);
  assert(b.DelayMicroseconds(132,20179)==20);
  assert(b.DelayMicroseconds(132,23000)==0); // a new polling burst
}''')
    for name, file in [('backoff', source), ('compact-texture', root/'scripts/android/compact-texture-vulkan-test.cpp')]:
        binary = directory/(name+'.exe')
        subprocess.run([str(compiler), '/nologo', '/EHsc', '/std:c++17', '/O2',
                        '/I'+str(root), '/I'+str(vulkan), str(file), '/Fe:'+str(binary)],
                       cwd=directory, env=env, check=True)
        subprocess.run([str(binary)], check=True)
        print('PASS:', name)
