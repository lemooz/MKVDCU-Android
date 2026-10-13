"""Pull only files referenced by one process's completed frame probes."""
import argparse
import hashlib
import io
import json
import pathlib
import re
import subprocess
import tarfile

p = argparse.ArgumentParser()
p.add_argument("--adb", required=True)
p.add_argument("--pid", type=int, required=True)
p.add_argument("--log", type=pathlib.Path, required=True)
p.add_argument("--output", type=pathlib.Path, required=True)
p.add_argument("--lossless-png", action="store_true",
               help="Decode each RGB PPM directly from tar to verified PNG; avoid large raw extraction")
args = p.parse_args()
lines = [line for line in args.log.read_text(errors="replace").splitlines()
         if re.search(rf"\s{args.pid}\s+\d+\s", line)]
args.output.mkdir(parents=True, exist_ok=True)
(args.output / "capture.log").write_text("\n".join(lines), encoding="utf-8")
file_listing = subprocess.check_output([args.adb, "shell", "run-as",
    "org.mkvdcu.android", "ls", "files"], text=True).splitlines()
# Use paths actually saved by this process, not a matching ordinal from an old
# session. New native epochs also contain the PID to prevent device-file aliasing.
names = {"files/" + m[1] for line in lines for m in [re.search(
    r"\[RT_IMAGE\].*path=/data/user/0/org\.mkvdcu\.android/files/(frameprobe_rt(?:128|_\d+_\d+_[0-9A-Fa-f]+)\.(?:ppm|tga)) saved=true", line)] if m}
names.update("files/" + m[1] for line in lines for m in [re.search(
    r"\[DRAW_BUFFER\].*path=/data/user/0/org\.mkvdcu\.android/files/(frameprobe_draws/e\d+_f\d+_d\d+_(?:vertex|index_bound)_\d+_[0-9A-Fa-f]+\.gpu\.bin) saved=true", line)] if m)
# Texture files carry addresses. Restrict them to addresses reported by this run.
addresses = {m[1].upper() for line in lines
             for m in [re.search(r"\[GAME_TEXTURE_IMAGE_FILE\] address=0x([0-9A-Fa-f]+).*saved=true", line)] if m}
for name in file_listing:
    match = re.fullmatch(r"frameprobe_texture_([0-9A-Fa-f]+)\.(?:rgba|bin)", name.strip())
    if match and match[1].upper() in addresses:
        names.add("files/" + name.strip())
draw_names = {m[1] + ".regs" for line in lines
              for m in [re.search(r"\[DRAW_INPUT\].*registers=(\w+)", line)] if m}
shader_hashes = {h for line in lines
                for match in re.finditer(r"\[DRAW_INPUT\].*VS=(\w+) PS=(\w+)", line)
                for h in match.groups() if h != "0000000000000000"}
draw_listing = subprocess.check_output([args.adb, "shell", "run-as",
    "org.mkvdcu.android", "ls", "files/frameprobe_draws"], text=True).splitlines()
for name in draw_listing:
    if name in draw_names or any(name.startswith("shader_" + h) for h in shader_hashes):
        names.add("files/frameprobe_draws/" + name)
# Resume interrupted transfers without recopying the already extracted files.
# Android gzip also avoids a multi-hundred-megabyte uncompressed archive on PC.
png_manifest = args.output / "lossless-png-manifest.json"
compressed = json.loads(png_manifest.read_text()) if png_manifest.exists() else {}
def present(name):
    if (args.output / name).is_file():
        return True
    item = compressed.get(name)
    if not item:
        return False
    png = args.output / item["png"]
    return png.is_file() and hashlib.sha256(png.read_bytes()).hexdigest() == item["png_sha256"]
missing = sorted(name for name in names if not present(name))
if not missing:
    print(f"All {len(names)} paths already present in {args.output}")
    raise SystemExit(0)
archive = args.output / "capture.tar.gz"
file_list = args.output / "capture-files.txt"
file_list.write_bytes(("\n".join(sorted(names)) + "\n").encode("utf-8"))
transfer_list = args.output / "capture-transfer.txt"
transfer_list.write_bytes(("\n".join(missing) + "\n").encode("utf-8"))
remote_list = f"/data/local/tmp/mkvdcu-capture-{args.pid}.txt"
subprocess.run([args.adb, "push", str(transfer_list), remote_list], check=True)
try:
    # Windows limits command-line length; exec-out does not forward a large
    # stdin file list reliably. Give Android tar its own bounded names file.
    with archive.open("wb") as output:
        subprocess.run([args.adb, "exec-out", "run-as", "org.mkvdcu.android", "tar",
            "-czf", "-", "-T", remote_list], stdout=output, check=True)
finally:
    subprocess.run([args.adb, "shell", "rm", remote_list], check=False)
with tarfile.open(archive) as capture:
    members = capture.getmembers()
    actual = {m.name for m in members if m.isfile()}
    if actual != set(missing):
        raise RuntimeError(f"Incomplete archive: missing={set(missing) - actual}, unexpected={actual - set(missing)}")
    for member in members:
        if not member.isfile():
            raise RuntimeError(f"Unexpected non-file archive entry: {member.name}")
        if args.lossless_png and member.name.endswith(('.ppm', '.tga')):
            from PIL import Image
            payload = capture.extractfile(member).read()
            if len(payload) != member.size:
                raise RuntimeError(f"Incomplete image: {member.name}")
            image = Image.open(io.BytesIO(payload))
            if image.mode not in ('RGB', 'RGBA'):
                raise RuntimeError(f"Unexpected image mode: {member.name}: {image.mode}")
            target = args.output / pathlib.Path(member.name).with_suffix('.png')
            target.parent.mkdir(parents=True, exist_ok=True)
            image.save(target, format='PNG')
            with Image.open(target) as verified:
                if verified.size != image.size or verified.tobytes() != image.tobytes():
                    raise RuntimeError(f"PNG pixel mismatch: {member.name}")
            compressed[member.name] = dict(source_bytes=len(payload),
                source_sha256=hashlib.sha256(payload).hexdigest(),
                source_format=image.format, channels=image.mode,
                png=target.relative_to(args.output).as_posix(),
                png_sha256=hashlib.sha256(target.read_bytes()).hexdigest())
            png_manifest.write_text(json.dumps(compressed, indent=2))
            continue
        capture.extract(member, args.output, filter="data")
        if (args.output / member.name).stat().st_size != member.size:
            raise RuntimeError(f"Incomplete extraction: {member.name}")
print(f"Pulled and verified {len(missing)} paths, {archive.stat().st_size} compressed bytes into {args.output}")
