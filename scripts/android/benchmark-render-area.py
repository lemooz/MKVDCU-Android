"""Developer A/B benchmark in one running scene; records thermal state and raw intervals."""
import argparse
import csv
import io
import json
import pathlib
import subprocess
import time
import numpy as np

p = argparse.ArgumentParser()
p.add_argument("--adb", required=True)
p.add_argument("--output", type=pathlib.Path, required=True)
p.add_argument("--seconds", type=int, default=25)
p.add_argument("--pairs", type=int, default=3)
p.add_argument("--scene", required=True)
args = p.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
def adb(*command):
    return subprocess.check_output([args.adb, *command])
def rows(blob):
    return list(csv.DictReader(io.StringIO('\n'.join(
        line for line in blob.decode().splitlines() if not line.startswith('#')))))
results = []
try:
    for segment, enabled in enumerate([False, True] * args.pairs):
        adb('shell', 'am', 'broadcast', '-a', 'org.mkvdcu.android.DEBUG_RENDER_AREA',
            '-p', 'org.mkvdcu.android', '--ez', 'enabled', str(enabled).lower())
        # Drain the small number of already recorded/submitted frames.
        time.sleep(3)
        first = rows(adb('exec-out', 'run-as', 'org.mkvdcu.android', 'cat', 'files/last-performance.csv'))[-1]
        time.sleep(args.seconds)
        performance = adb('exec-out', 'run-as', 'org.mkvdcu.android', 'cat', 'files/last-performance.csv')
        intervals = adb('exec-out', 'run-as', 'org.mkvdcu.android', 'cat', 'files/last-frame-times.csv')
        last = rows(performance)[-1]
        prefix = args.output / f'{segment:02d}-area-{int(enabled)}'
        prefix.with_suffix('.csv').write_bytes(performance)
        prefix.with_suffix('.frames.csv').write_bytes(intervals)
        prefix.with_suffix('.png').write_bytes(adb('exec-out', 'screencap', '-p'))
        prefix.with_suffix('.thermal.txt').write_bytes(adb('shell', 'dumpsys', 'thermalservice'))
        frame_begin, frame_end = int(first['guest_frames']), int(last['guest_frames'])
        frame_rows = [r for r in rows(intervals) if frame_begin < int(r['frame_id']) <= frame_end]
        values = np.array([float(r['interval_ms']) for r in frame_rows])
        seconds = (int(last['elapsed_ms']) - int(first['elapsed_ms'])) / 1000
        selected = [r for r in rows(performance) if int(first['elapsed_ms']) <= int(r['elapsed_ms']) <= int(last['elapsed_ms'])]
        modes = {int(r['render_area_benchmark']) for r in selected}
        if modes != {int(enabled)}:
            raise RuntimeError(f'Benchmark toggle did not apply: {modes}')
        result = dict(segment=segment, scissor_area=enabled, scene=args.scene,
            frames=frame_end-frame_begin, seconds=seconds, fps=(frame_end-frame_begin)/seconds,
            raw_intervals=len(values), thermal_statuses=sorted({int(r['thermal_status']) for r in selected}),
            median_ms=float(np.median(values)) if values.size else None,
            p95_ms=float(np.percentile(values,95)) if values.size else None)
        results.append(result)
        (args.output / 'results.json').write_text(json.dumps(results, indent=2))
        print(json.dumps(result), flush=True)
finally:
    # Leave the unvalidated optimization disabled after the experiment.
    adb('shell', 'am', 'broadcast', '-a', 'org.mkvdcu.android.DEBUG_RENDER_AREA',
        '-p', 'org.mkvdcu.android', '--ez', 'enabled', 'false')
