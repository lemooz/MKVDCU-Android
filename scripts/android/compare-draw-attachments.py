"""Compare packet RGB readbacks; never attribute preparation/transfers to one draw."""
import argparse
import json
import pathlib
import re
import numpy as np
from PIL import Image

p = argparse.ArgumentParser()
p.add_argument("capture", type=pathlib.Path)
p.add_argument("--log", type=pathlib.Path, required=True)
p.add_argument("--output", type=pathlib.Path, required=True)
args = p.parse_args()
points = {}
attachments = {}
for line in args.log.read_text(errors="replace").splitlines():
    metadata = re.search(r"\[RT\] id=(\d+) frame=(\d+).*base=(\d+).*format=(\d+).*image=(0x[0-9A-Fa-f]+) key=(0x[0-9A-Fa-f]+) msaa=(\d+)", line)
    if metadata:
        probe_id, frame, base, fmt, image, key, msaa = metadata.groups()
        attachments[(frame, probe_id)] = dict(base=base, format=fmt, image=image, key=key, msaa=msaa)
    match = re.search(r"\[DRAW_RT\] epoch=(\d+) frame=(\d+) draw=(\d+) .*point=(before|after) id=(\d+)", line)
    if match:
        epoch, frame, draw, point, probe_id = match.groups()
        entry = points.setdefault((epoch, frame, draw), {})
        entry[point] = probe_id
        if point == "before":
            entry["inputs"] = line
result = []
for (epoch, frame, draw), points_for_draw in points.items():
    row = {"epoch": int(epoch), "frame": int(frame), "draw": int(draw), **points_for_draw,
        "capture_scope": "IssueDraw packet includes preparation and ownership transfers; excludes depth/stencil"}
    before_meta = attachments.get((frame, row.get('before')))
    after_meta = attachments.get((frame, row.get('after')))
    row['attachment_identity_verified'] = bool(before_meta and after_meta and before_meta == after_meta)
    before = [p for p in args.capture.rglob(f"frameprobe_rt_{epoch}_{row.get('before')}_*.*") if p.suffix in ('.ppm', '.tga', '.png')]
    after = [p for p in args.capture.rglob(f"frameprobe_rt_{epoch}_{row.get('after')}_*.*") if p.suffix in ('.ppm', '.tga', '.png')]
    if len(before) != 1 or len(after) != 1:
        row["status"] = "unavailable: missing or ambiguous readback"
    elif (before_meta and after_meta and before_meta != after_meta) or before[0].stem.split('_')[-1] != after[0].stem.split('_')[-1]:
        row["status"] = "different attachments; do not compare"
    else:
        a = np.array(Image.open(before[0]))
        b = np.array(Image.open(after[0]))
        if a.shape != b.shape:
            row["status"] = "different image dimensions; do not compare"
        else:
            changed = np.any(a[:, :, :3] != b[:, :, :3], axis=2)
            ys, xs = np.nonzero(changed)
            row.update(status="packet RGB comparable" if row['attachment_identity_verified'] else
                "legacy RGB difference; image identity unavailable", base=before[0].stem.split('_')[-1],
                width=int(a.shape[1]), height=int(a.shape[0]),
                changed_pixels=int(changed.sum()),
                alpha_available=a.shape[2] == 4,
                alpha_changed_pixels=int(np.count_nonzero(a[:, :, 3] != b[:, :, 3])) if a.shape[2] == 4 else None,
                change_bounds=[int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max())] if xs.size else None,
                before_file=str(before[0]), after_file=str(after[0]))
    result.append(row)
args.output.write_text(json.dumps(result, indent=2), encoding="utf-8")
for row in result:
    print(row["frame"], row["draw"], row["status"], row.get("changed_pixels"), row.get("change_bounds"))
