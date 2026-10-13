"""Replace PPM probes with pixel-identical PNGs, keeping source hashes in a manifest."""
import argparse
import hashlib
import json
import pathlib
import numpy as np
from PIL import Image

p = argparse.ArgumentParser()
p.add_argument("capture", type=pathlib.Path)
args = p.parse_args()
root = args.capture.resolve(strict=True)
manifest_path = root / "lossless-png-manifest.json"
manifest = json.loads(manifest_path.read_text()) if manifest_path.exists() else {}
saved = 0
for path in root.rglob("frameprobe_rt_*.ppm"):
    path = path.resolve(strict=True)
    if not path.is_relative_to(root):
        raise ValueError(f"Probe escaped capture directory: {path}")
    with Image.open(path) as source:
        pixels = np.array(source)
        destination = path.with_suffix(".png")
        source.save(destination, format="PNG")
    with Image.open(destination) as result:
        if not np.array_equal(pixels, np.array(result)):
            raise ValueError(f"Nonidentical pixels: {path}")
    size = path.stat().st_size
    manifest[str(path.relative_to(root)).replace('\\', '/')] = {
        "source_bytes": size, "source_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "png": str(destination.relative_to(root)).replace('\\', '/'),
        "png_sha256": hashlib.sha256(destination.read_bytes()).hexdigest(),
    }
    # Save provenance before removing only this verified, redundant probe file.
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    path.unlink()
    saved += size - destination.stat().st_size
print(f"Pixel-identical PNG probes saved {saved} bytes in {root}")
