"""Exercise production scene patch and real guest size consumers without a GPU."""
from pathlib import Path
import argparse
import ctypes
import struct
import os
import re
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
host = root / 'targets/mkvsdcu/private/rexglue-host'
parser = argparse.ArgumentParser()
parser.add_argument('--image', type=Path, help='Optional loaded guest image (never distributed)')
parser.add_argument('--compiler', default=os.environ.get('CXX') or shutil.which('clang++') or
                    'C:/Program Files/LLVM/bin/clang++.exe')
args = parser.parse_args()
functions = []
for part, name in [('112', 'sub_82ED4E30'), ('10', 'sub_82389540')]:
    source = (host / f'generated/default/mkvsdcu_recomp.{part}.cpp').read_text()
    match = re.search(r'DEFINE_REX_FUNC\(' + name + r'\) \{', source)
    if not match:
        raise RuntimeError(f'Guest consumer not found: {name}; inspect regenerated code')
    end = source.find('\nDEFINE_REX_FUNC(', match.end())
    functions.append(source[match.start():end if end >= 0 else len(source)])

harness = r"""
#include "scene_resolution.h"
using namespace scene_resolution;
union R { uint64_t u64; int64_t s64; uint32_t u32; int32_t s32; };
using PPCRegister = R;
struct PPCXERRegister {};
struct PPCCRRegister {
  bool lt=false, eq=false, gt=false;
  template<class T> void compare(T a, T b, PPCXERRegister&) {
    lt=a<b; eq=a==b; gt=a>b;
  }
};
struct Context {
  R r1,r3,r4,r5,r6,r7,r8,r9,r10,r11,r12,r31;
  uint64_t lr;
};
static unsigned indirect_calls;
void Store64(uint8_t* p, uint64_t value) {
  WriteBE32(p,uint32_t(value>>32)); WriteBE32(p+4,uint32_t(value));
}
#define DEFINE_REX_FUNC(name) void name(Context& ctx, uint8_t* base)
#define REX_FUNC_PROLOGUE() ((void)0)
#define REX_LOAD_U32(a) ReadBE32(base + uint32_t(a) - kImageBase)
#define REX_LOAD_U64(a) ((uint64_t(REX_LOAD_U32(a))<<32)|REX_LOAD_U32(uint32_t(a)+4))
#define REX_STORE_U32(a,v) WriteBE32(base + uint32_t(a) - kImageBase,uint32_t(v))
#define REX_STORE_U64(a,v) Store64(base + uint32_t(a) - kImageBase,uint64_t(v))
// Replace only the allocator's two resource callbacks; guest size selection,
// comparisons, offsets and quarter-buffer calculations execute unchanged.
#define REX_CALL_INDIRECT_FUNC(a) (++indirect_calls)
GUEST_FUNCTIONS
#if defined(_WIN32)
#define API extern "C" __declspec(dllexport)
#else
#define API extern "C" __attribute__((visibility("default")))
#endif
API bool patch(uint8_t* image,size_t size,uint32_t percent) {
  return Apply(image,size,percent);
}
API unsigned dimension(unsigned native,unsigned percent) {
  return Dimension(native,percent);
}
API void initialize(uint8_t* base) {
  Context ctx;
  sub_82ED4E30(ctx,base);
}
API unsigned allocate(uint8_t* base) {
  Context ctx;
  ctx.r1.u64=0x83000000; ctx.r3.u64=0x83001000;
  ctx.lr=0;ctx.r31.u64=0;
  indirect_calls=0;
  sub_82389540(ctx,base);
  return indirect_calls;
}
""".replace('GUEST_FUNCTIONS', '\n'.join(functions))
with tempfile.TemporaryDirectory(prefix='mkvdcu-scene-') as temporary:
    directory = Path(temporary)
    source = directory / 'scene.cpp'
    source.write_text(harness)
    dll = directory / ('scene.dll' if os.name == 'nt' else 'scene.so')
    # No host SDK/CRT dependency: Python owns storage, checks and file I/O.
    command = [args.compiler, '-std=c++17', '-O1', '-ffreestanding', '-fno-exceptions',
               '-fno-rtti', '-shared', '-nostdlib', '-I', str(host / 'src'), str(source), '-o', str(dll)]
    if os.name == 'nt': command += ['-fuse-ld=lld', '-Wl,/noentry']
    else: command += ['-fPIC']
    subprocess.run(command, check=True)
    lib = ctypes.CDLL(str(dll))
    lib.patch.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_uint32]
    lib.patch.restype = ctypes.c_bool
    lib.dimension.argtypes = [ctypes.c_uint32, ctypes.c_uint32]
    lib.dimension.restype = ctypes.c_uint32
    lib.initialize.argtypes = [ctypes.c_void_p]
    lib.allocate.argtypes = [ctypes.c_void_p]
    lib.allocate.restype = ctypes.c_uint32
    base = 0x82000000
    original = bytearray(args.image.read_bytes() if args.image else bytes(0x1130000))
    if not args.image:
        for address, word in [(0x82389560,0x816BD17C),(0x82389564,0x8129D180),
                              (0x82ED4E44,0x8129D17C),(0x82ED4E48,0x8108D180),
                              (0x82F4D17C,1040),(0x82F4D180,624)]:
            struct.pack_into('>I',original,address-base,word)
    if len(original) != 0x1130000: raise ValueError('Expected this title image size 0x1130000')
    read = lambda image,address: struct.unpack_from('>I',image,address-base)[0]
    for percent in (50,75,100,0,60,255):
        image = bytearray(original)
        pointer = (ctypes.c_uint8*len(image)).from_buffer(image)
        assert lib.patch(pointer,len(image),percent)
        effective = percent if percent in (50,75) else 100
        w,h = 1040*effective//100,624*effective//100
        assert read(image,0x82F4D17C)==w and read(image,0x82F4D180)==h
        assert image[:0xF4D17C]==original[:0xF4D17C] and image[0xF4D184:]==original[0xF4D184:]
        lib.initialize(pointer)
        assert read(image,0x82F4D110)==w and read(image,0x82F4D114)==h
        for address,word in [(0x83001000,0x83002000),(0x83002014,1),(0x83002018,2)]:
            struct.pack_into('>I',image,address-base,word)
        assert lib.allocate(pointer)==2
        assert read(image,0x83001014)==w and read(image,0x83001018)==h
        assert read(image,0x83001020)==w//4 and read(image,0x83001024)==h//4
        assert lib.allocate(pointer)==0  # Size unchanged: no resource recreation.
        print(f'PASS scene {percent}% -> {w}x{h}, actual guest init/allocator, quarter buffers, callbacks')
    image=bytearray(original)
    image[0x82389560-base] ^= 1
    pointer=(ctypes.c_uint8*len(image)).from_buffer(image)
    before=bytes(image)
    assert not lib.patch(pointer,len(image),50) and image==before
    assert not lib.patch(None,len(image),50)
    assert not lib.patch(pointer,0xF4D183,50)
    for native in (1024,576):
        for percent in (50,75,100): assert lib.dimension(native,percent)==native*percent//100
    print('PASS alternate scene mode, native fallback, rejected anchors/bounds with no writes')
    # Windows cannot delete a loaded DLL. Release only the owned harness module.
    if os.name == 'nt':
        ctypes.windll.kernel32.FreeLibrary.argtypes=[ctypes.c_void_p]
        ctypes.windll.kernel32.FreeLibrary(ctypes.c_void_p(lib._handle))
