"""Exercise the production Java ELF validator without loading any driver."""
import pathlib, struct, subprocess, tempfile, argparse, zipfile, json, hashlib, builtins

root = pathlib.Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser()
parser.add_argument('--package', type=pathlib.Path)
args = parser.parse_args()
jdk = pathlib.Path('C:/Program Files/Android/Android Studio/jbr/bin')

def elf(dependency=b'libc.so', machine=183, phoff=64):
    data = bytearray(512)
    data[:7] = b'\x7fELF\x02\x01\x01'
    struct.pack_into('<HHIQQQIHHHHHH', data, 16, 3, machine, 1, 0, phoff, 0, 0, 64, 56, 2, 0, 0, 0)
    struct.pack_into('<IIQQQQQQ', data, 64, 1, 4, 0, 0x1000, 0, 512, 512, 4096)
    struct.pack_into('<IIQQQQQQ', data, 120, 2, 4, 192, 0x10c0, 0, 64, 64, 8)
    strings = b'\0' + dependency + b'\0'
    struct.pack_into('<QQQQQQQQ', data, 192, 5, 0x1120, 10, len(strings), 1, 1, 0, 0)
    data[288:288+len(strings)] = strings
    return data

with tempfile.TemporaryDirectory(prefix='mkvdcu-elf-') as tmp:
    path = pathlib.Path(tmp)
    source = root/'android/app/src/main/java/org/mkvdcu/android/ElfDriver.java'
    harness = path/'ElfHarness.java'
    harness.write_text('''package org.mkvdcu.android;
import java.io.*; import java.util.*;
public class ElfHarness { public static void main(String[] args) throws Exception {
  if (args[0].equals("--prepare")) {
    Set<String> names = new HashSet<>(Arrays.asList(args).subList(2,args.length));
    File file = new File(args[1]); boolean changed = ElfDriver.prepare(file,names);
    ElfDriver.validate(file,names); System.out.print(changed); return;
  }
  if (args[0].equals("--order")) {
    System.out.print(String.join(",", ElfDriver.loadOrder(new File(args[1]), args[2]))); return;
  }
  if (args[0].equals("--missing")) {
    System.out.print(String.join(",", ElfDriver.missingDependencies(new File(args[1]),
        new HashSet<>(Arrays.asList(args).subList(2,args.length))))); return;
  }
  Set<String> bundled = new HashSet<>(Arrays.asList(args).subList(1,args.length));
  ElfDriver.validate(new File(args[0]), bundled);
}}''')
    subprocess.run([str(jdk/'javac.exe'), '-d', tmp, str(source), str(harness)], check=True)
    cases = [('android',elf(),True,[]), ('x86',elf(machine=62),False,[]),
             ('glibc',elf(b'libc.so.6'),False,['libc.so.6']),
             ('path',elf(b'../libc.so'),False,['../libc.so']),
             ('ph-overflow',elf(phoff=0x7ffffffffffffff0),False,[]),
             ('truncated',elf()[:220],False,[]),
             ('missing',elf(b'libmissing.so'),False,[]),
             ('bundled',elf(b'libhelper.so'),True,['libhelper.so'])]
    invalid = elf(); struct.pack_into('<Q', invalid, 216, 4096)
    cases.append(('strtab-outside-load',invalid,False,[]))
    for name, data, expected, bundled in cases:
        file = path/(name+'.so'); file.write_bytes(data)
        result = subprocess.run([str(jdk/'java.exe'), '-cp',tmp,'org.mkvdcu.android.ElfHarness',str(file),*bundled],capture_output=True,text=True)
        assert (result.returncode == 0) == expected, (name,result.stderr)
        print(f'PASS {name}: {"accepted" if expected else "rejected"}')
    def linked_elf(deps=(), soname=None, runpath=None):
        data = bytearray(2048)
        data[:7] = b'\x7fELF\x02\x01\x01'
        struct.pack_into('<HHIQQQIHHHHHH', data, 16, 3, 183, 1, 0, 64, 0, 0, 64, 56, 2, 0, 0, 0)
        strings = bytearray(b'\0')
        tags = []
        for tag, value in [(1, value) for value in deps] + ([(14, soname)] if soname is not None else []) + ([(29, runpath)] if runpath is not None else []):
            tags.append((tag, len(strings)))
            strings.extend(value.encode() + b'\0')
        tags = [(5, 0x1400), (10, len(strings))] + tags + [(0, 0)]
        struct.pack_into('<IIQQQQQQ', data, 64, 1, 4, 0, 0x1000, 0, len(data), len(data), 4096)
        struct.pack_into('<IIQQQQQQ', data, 120, 2, 4, 192, 0x10c0, 0, len(tags)*16, len(tags)*16, 8)
        for index, (tag, value) in enumerate(tags): struct.pack_into('<QQ', data, 192+index*16, tag, value)
        data[1024:1024+len(strings)] = strings
        return data
    graph = path/'graph'; graph.mkdir()
    def check_graph(name, libraries, expected=None):
        folder = graph/name; folder.mkdir()
        for library, data in libraries.items(): (folder/library).write_bytes(data)
        result = subprocess.run([str(jdk/'java.exe'), '-cp',tmp,'org.mkvdcu.android.ElfHarness','--order',str(folder),'libdriver.so'],capture_output=True,text=True)
        if expected is None: assert result.returncode != 0, name
        else: assert result.returncode == 0 and result.stdout == expected, (name, result.stdout, result.stderr)
        print(f'PASS dependency graph {name}')
    check_graph('ordered', {'libdriver.so': linked_elf(['liba.so','libb.so'],runpath='$ORIGIN'),
        'liba.so':linked_elf(['libb.so'],soname='liba.so'), 'libb.so':linked_elf(['libc.so'],soname='libb.so')}, 'libb.so,liba.so')
    check_graph('origin-braces', {'libdriver.so':linked_elf(['libc.so'],runpath='${ORIGIN}')}, '')
    check_graph('external-path', {'libdriver.so':linked_elf(['libc.so'],runpath='/vendor/lib64')})
    check_graph('cycle', {'libdriver.so':linked_elf(['liba.so']), 'liba.so':linked_elf(['libdriver.so'],soname='liba.so')})
    check_graph('wrong-soname', {'libdriver.so':linked_elf(['liba.so']), 'liba.so':linked_elf(['libc.so'],soname='libc.so')})
    check_graph('system-replacement', {'libdriver.so':linked_elf(['libc.so']), 'libc.so':linked_elf(soname='libc.so')})
    check_graph('unreachable-not-executed', {'libdriver.so':linked_elf(['libc.so']), 'libunrelated.so':b'invalid'}, '')
    check_graph('bionic-versioned-soname', {'libdriver.so':linked_elf(['libzstd.so.1']),
        'libzstd.so.1':linked_elf(['libc.so'],soname='libzstd.so.1')}, 'libzstd.so.1')
    check_graph('bionic-libcxx-soname', {'libdriver.so':linked_elf(['libc++_shared.so']),
        'libc++_shared.so':linked_elf(['libc.so'],soname='libc++_shared.so')}, 'libc++_shared.so')
    check_graph('glibc-versioned-helper', {'libdriver.so':linked_elf(['libzstd.so.1']),
        'libzstd.so.1':linked_elf(['libc.so.6'],soname='libzstd.so.1')})
    requirement = linked_elf(['libc.so'],soname='libzstd.so.1')
    # Keep the same dependency names but require GLIBC_2.17 symbols. This must
    # be rejected independently of the helper's versioned SONAME.
    struct.pack_into('<QQQQQQ',requirement,256,0x6ffffffe,0x1500,0x6fffffff,1,0,0)
    struct.pack_into('<Q',requirement,120+32,112)
    version_index = 32
    struct.pack_into('<Q',requirement,216,64)
    requirement[1024+version_index:1024+version_index+11] = b'GLIBC_2.17\0'
    struct.pack_into('<HHIII',requirement,1280,1,1,1,16,0)
    struct.pack_into('<IHHII',requirement,1296,0,0,2,version_index,0)
    check_graph('glibc-symbol-version', {'libdriver.so':linked_elf(['libzstd.so.1']), 'libzstd.so.1':requirement})
    file = path/'incomplete.so'; file.write_bytes(linked_elf(['libdrm.so','libzstd.so.1','libc.so']))
    result = subprocess.run([str(jdk/'java.exe'),'-cp',tmp,'org.mkvdcu.android.ElfHarness',
        '--missing',str(file)],capture_output=True,text=True,check=True)
    assert result.stdout == 'libdrm.so,libzstd.so.1', result.stdout
    print('PASS incomplete package: full dependency list without executing ELF')
    for runpath in ('$ORIGIN/../../android_stub', '/vendor/lib64', '$ORIGIN:/system/lib64'):
        original = linked_elf(['libc.so'], soname='libdriver.so', runpath=runpath)
        file = path/'prepared.so'; file.write_bytes(original)
        result = subprocess.run([str(jdk/'java.exe'), '-cp',tmp,'org.mkvdcu.android.ElfHarness','--prepare',str(file)],capture_output=True,text=True,check=True)
        assert result.stdout == 'true'
        changed = file.read_bytes()
        # Preparation may change dynamic entries only, not instructions / strings.
        dynamic_size = struct.unpack_from('<Q', original, 120+32)[0]
        assert original[:192] == changed[:192] and original[192+dynamic_size:] == changed[192+dynamic_size:]
        result = subprocess.run([str(jdk/'java.exe'), '-cp',tmp,'org.mkvdcu.android.ElfHarness','--prepare',str(file)],capture_output=True,text=True,check=True)
        assert result.stdout == 'false' and file.read_bytes() == changed
        print(f'PASS private ELF preparation + idempotence: {runpath}')
    if args.package:
        with zipfile.ZipFile(args.package) as zip:
            names = zip.namelist()
            for name in names:
                assert '/' not in name and '\\' not in name
                if '.so' not in name: continue
                file = path/name; original = zip.read(name); file.write_bytes(original)
                result = subprocess.run([str(jdk/'java.exe'),'-cp',tmp,'org.mkvdcu.android.ElfHarness','--prepare',str(file),*names],capture_output=True,text=True)
                assert result.returncode == 0, (name, result.stderr)
                prepared = file.read_bytes()
                phoff, = struct.unpack_from('<Q',original,32)
                phsize, phcount = struct.unpack_from('<HH',original,54)
                dynamic_ranges = []
                for i in range(phcount):
                    header = struct.unpack_from('<IIQQQQQQ',original,phoff+i*phsize)
                    if header[0] == 2: dynamic_ranges.append((header[2],header[2]+header[5]))
                assert len(original) == len(prepared)
                for index,(old,new) in enumerate(builtins.zip(original, prepared)):
                    assert old == new or any(start <= index < end for start,end in dynamic_ranges)
                print(f'PASS real package: {name}, prepared SHA256={hashlib.sha256(prepared).hexdigest()} (ELF/dependencies only, not GPU compatibility)')
            library = json.loads(zip.read('meta.json'))['libraryName']
            subprocess.run([str(jdk/'java.exe'),'-cp',tmp,'org.mkvdcu.android.ElfHarness','--order',str(path),library],check=True,capture_output=True)
            print('PASS real package dependency load order')
