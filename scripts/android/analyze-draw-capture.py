"""Decode exact host-order Xenos register snapshots; never treat them as GPU readbacks."""
import argparse
import json
import pathlib
import re
import struct

root = pathlib.Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument("capture", type=pathlib.Path)
p.add_argument("--log", type=pathlib.Path)
p.add_argument("--output", type=pathlib.Path, required=True)
args = p.parse_args()
table = root / "MKVDCU-Recomp/references/rexglue-sdk/include/rex/graphics/register_table.inc"
indices = {name: int(index, 16) for index, name in re.findall(
    r"XE_GPU_REGISTER\(0x([0-9A-Fa-f]+),\s*\w+,\s*(\w+)\)", table.read_text())}
annotations = {}
if args.log:
    for line in args.log.read_text(errors="replace").splitlines():
        match = re.search(r"\[DRAW_INPUT\].*VS=(\w+) PS=(\w+).*registers=(\w+)", line)
        if match:
            annotations[match[3]] = {"vs": match[1], "ps": match[2], "log": line}

def signed(value, bits):
    return value - (1 << bits) if value & (1 << (bits - 1)) else value

draws = []
for path in sorted(args.capture.rglob("*.regs")):
    if args.log and path.stem not in annotations:
        continue  # Never mix stale device files with the selected process log.
    raw = path.read_bytes()
    if len(raw) != 0x5003 * 4:
        raise ValueError(f"Unexpected register file size: {path}: {len(raw)}")
    values = struct.unpack("<" + "I" * (len(raw) // 4), raw)
    registers = {name: f"0x{values[indices[name]]:08X}" for name in (
        "RB_COLOR_INFO", "RB_DEPTH_INFO", "RB_COLOR_MASK", "RB_DEPTHCONTROL",
        "RB_COLORCONTROL", "PA_SU_SC_MODE_CNTL", "SQ_PROGRAM_CNTL",
        "PA_SC_WINDOW_SCISSOR_TL", "PA_SC_WINDOW_SCISSOR_BR", "RB_SURFACE_INFO")}
    fetches = []
    for slot in range(32):
        start = indices["SHADER_CONSTANT_FETCH_00_0"] + slot * 6
        a, b, c, d, e, f = values[start:start + 6]
        if a & 3 != 2:  # Texture fetch; other slots can contain vertex constants.
            continue
        fetches.append({"slot": slot, "address": f"0x{b & 0xFFFFF000:08X}",
            "mip_address": f"0x{f & 0xFFFFF000:08X}", "format": b & 63,
            "endian": b >> 6 & 3, "dimension": f >> 9 & 3,
            "width_2d": (c & 8191) + 1, "height_2d": (c >> 13 & 8191) + 1,
            "pitch_pixels": (a >> 22 & 511) * 32, "tiled": bool(a >> 31),
            "swizzle": f"0x{d >> 1 & 4095:03X}",
            "exponent": signed(d >> 13 & 63, 6),
            "raw": [f"0x{v:08X}" for v in (a, b, c, d, e, f)]})
    color = values[indices["RB_COLOR_INFO"]]
    draws.append({"snapshot": path.name, **annotations.get(path.stem, {}),
        "registers": registers, "rt0": {"base_tiles": color & 2047,
            "format": color >> 16 & 15, "exponent": signed(color >> 20 & 63, 6)},
        "fetches": fetches})
args.output.write_text(json.dumps({"kind": "CPU draw inputs, not pixel evidence",
    "draw_count": len(draws), "draws": draws}, indent=2), encoding="utf-8")
print(f"Decoded {len(draws)} draws into {args.output}")
