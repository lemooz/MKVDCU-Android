"""Assemble an experimental Android/Bionic package; does not validate a GPU.

Pinned upstream driver + Termux ARM64 runtime dependencies. No binary code is
patched; the app's importer removes build-machine RUNPATH in its private copy.
This is a host packaging tool, not an in-app download/catalog feature.
"""
import argparse
import hashlib
import io
import json
import pathlib
import tarfile
import urllib.request
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--cache', type=pathlib.Path, default=pathlib.Path('C:/Temp/mkvdcu-frist-research'))
parser.add_argument('--output', type=pathlib.Path, required=True)
args = parser.parse_args()
args.cache.mkdir(parents=True, exist_ok=True)

def fetch(name, url, sha):
    file = args.cache / name
    if not file.exists():
        with urllib.request.urlopen(url, timeout=60) as response:
            file.write_bytes(response.read())
    data = file.read_bytes()
    if hashlib.sha256(data).hexdigest() != sha:
        raise ValueError('SHA256 mismatch: ' + name)
    return data

driver_url = ('https://github.com/FristOneRR/FristOneRR-Panvk-Driver/releases/download/'
              'v1.2.0-beta/Panvk-Mali-G57-beta_1.2.0.zip')
driver_sha = '29b3dcd55eb38e07e90d9ad5256b67169c7460572606e7b92c960615a868ff2c'
driver = fetch('Panvk-Mali-G57-beta_1.2.0.zip', driver_url, driver_sha)
with zipfile.ZipFile(io.BytesIO(driver)) as archive:
    contents = {name: archive.read(name) for name in archive.namelist()}

dependencies = [
    ('pool/main/libc/libc++/libc++_30_aarch64.deb', 'libc++_shared.so',
     '53d0b84a7ba7459024257cb94d5b136fe13ef858567f65a8064b35950799f2ca'),
    ('pool/main/libd/libdrm/libdrm_2.4.134_aarch64.deb', 'libdrm.so',
     '0cbe82140cf325c15473fbf53a3711fc7a2fb88e36a3eea58a3267c461ab4a17'),
    ('pool/main/z/zlib/zlib_1.3.2_aarch64.deb', 'libz.so.1',
     '75e7d0af17fcc3b40004309fdc00a1ddb9ae08346dce5e269902c34ac3966ac9'),
    ('pool/main/z/zstd/zstd_1.5.7-1_aarch64.deb', 'libzstd.so.1',
     'e1b4a5113648da8de189620ba1fce74c48b2d0833d9043391b9a1c91fb606fd3'),
]
provenance = {'experimental': True, 'gpuValidated': False,
              'upstreamDriver': {'url': driver_url, 'sha256': driver_sha}, 'dependencies': []}

for relative, library, sha in dependencies:
    url = 'https://packages.termux.dev/apt/termux-main/' + relative
    deb = fetch(pathlib.PurePosixPath(relative).name, url, sha)
    if deb[:8] != b'!<arch>\n':
        raise ValueError('Not a Debian ar package')
    pos = 8
    data_tar = None
    while pos + 60 <= len(deb):
        name = deb[pos:pos+16].decode().strip().rstrip('/')
        size = int(deb[pos+48:pos+58])
        if name == 'data.tar.xz':
            data_tar = deb[pos+60:pos+60+size]
        pos += 60 + size + size % 2
    if data_tar is None:
        raise ValueError('Missing data.tar.xz')
    with tarfile.open(fileobj=io.BytesIO(data_tar), mode='r:xz') as tar:
        prefix = './data/data/com.termux/files/usr/'
        member = tar.getmember(prefix + 'lib/' + library)
        for _ in range(4):
            if not member.issym():
                break
            if '/' in member.linkname or '\\' in member.linkname:
                raise ValueError('Unexpected dependency symlink')
            member = tar.getmember(prefix + 'lib/' + member.linkname)
        if not member.isfile():
            raise ValueError('Expected a regular ELF file')
        contents[library] = tar.extractfile(member).read()
        if library == 'libdrm.so':
            contents['LICENSE-libdrm.txt'] = tar.extractfile(prefix+'share/doc/libdrm/LICENSE').read()
    provenance['dependencies'].append({'url': url, 'packageSha256': sha, 'library': library,
        'librarySha256': hashlib.sha256(contents[library]).hexdigest()})

for name, url, sha in [
    ('LICENSE-zlib.txt', 'https://raw.githubusercontent.com/madler/zlib/v1.3.2/LICENSE',
     'e32ff4e00d9d94930537635291da39e7e612703334bf6fde8c7f1686fe8a45a2'),
    ('LICENSE-zstd.txt', 'https://raw.githubusercontent.com/facebook/zstd/v1.5.7/LICENSE',
     '7055266497633c9025b777c78eb7235af13922117480ed5c674677adc381c9d8'),
    ('LICENSE-libcxx.txt', 'https://raw.githubusercontent.com/llvm/llvm-project/llvmorg-22.1.0/libcxx/LICENSE.TXT',
     '539dd7aed86e8a4f12cbdd0e6c50c189c7d74847e4fecc64ce2c6ee3a01da38b'),
]:
    contents[name] = fetch(name, url, sha)

metadata = json.loads(contents['meta.json'])
metadata.update(name='FristOneRR PanVK 1.2.0 + Bionic dependencies (experimental)',
    packageVersion='1.2.0-mkvdcu-package1', backend='panvk-kbase',
    abi='android-aarch64-bionic', frontend='JM', kernelInterface='mali_kbase', panArch=9,
    description='Valhall v9/JM experimental. Original driver code unchanged; Bionic helpers added. '
                'Requires API 36 and real tessellation/readback/Surface verification. GPU not validated.')
contents['meta.json'] = json.dumps(metadata, indent=2).encode()
contents['package-provenance.json'] = json.dumps(provenance, indent=2).encode()
contents['README-MKVDCU.txt'] = (
    'EXPERIMENTAL: no physical GPU validation. This package is NOT an upstream release.\n'
    'Original FristOneRR 1.2.0 ICD plus four unmodified Termux/Bionic ARM64 helpers.\n'
    'No Winlator/DXVK wrapper, fake features, system libhardware replacement or kernel changes.\n'
    'Import with the updated MKVDCU driver manager, then run verification.\n'
    'Mali-G68 recognition does not guarantee tessellation, presentation or correct game graphics.\n'
    'Sources/hashes: package-provenance.json. Sources for the Termux builds: '\
    'https://github.com/termux/termux-packages/tree/master/packages\n'
).encode()
args.output.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(args.output, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for name, data in sorted(contents.items()):
        archive.writestr(zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0)), data,
                         compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
print('Experimental package:', args.output)
print('SHA256:', hashlib.sha256(args.output.read_bytes()).hexdigest())
