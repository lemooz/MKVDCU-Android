"""Symbolize LLDB address-only stacks using the run's /proc/PID/maps and exact ELF files."""
import pathlib, re, subprocess, argparse
p=argparse.ArgumentParser()
p.add_argument('stack',type=pathlib.Path);p.add_argument('maps',type=pathlib.Path)
p.add_argument('symbols',type=pathlib.Path);args=p.parse_args()
maps=[]
for line in args.maps.read_text().splitlines():
    fields=line.split()
    if len(fields)<6: continue
    lo,hi=(int(x,16) for x in fields[0].split('-'))
    maps.append((lo,hi,int(fields[2],16),pathlib.Path(fields[-1]).name))
tool=pathlib.Path.home()/'AppData/Local/Android/Sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-addr2line.exe'
memo={}
for line in args.stack.read_text().splitlines():
    match=re.search(r'frame #\d+: (0x[0-9a-f]+)',line)
    if match:
        address=int(match[1],16)
        for lo,hi,offset,name in maps:
            elf=args.symbols/name
            if lo<=address<hi and elf.is_file():
                relative=address-lo+offset; key=(name,relative)
                if key not in memo:
                    memo[key]=subprocess.check_output([str(tool),'-C','-f','-e',str(elf),hex(relative)],text=True).strip().replace('\n',' @ ')
                line+=f' {name}+{relative:x} {memo[key]}'
                break
    print(line)
