"""Same-session full FG versus direct presentation A/B; never changes guest clocks or game draws."""
import argparse,csv,io,json,math,pathlib,re,statistics,subprocess,time
p=argparse.ArgumentParser()
p.add_argument('--serial',required=True)
p.add_argument('--output',type=pathlib.Path,required=True)
p.add_argument('--seconds',type=int,default=20)
p.add_argument('--pairs',type=int,default=3)
p.add_argument('--scene',required=True)
p.add_argument('--pool-limits',help='Optional idle-pool A/B list, e.g. 8,16,32; disables FG for all segments')
a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
def adb(*args): return subprocess.check_output(['adb','-s',a.serial,*args])
def rows(blob):return list(csv.DictReader(x for x in blob.decode().splitlines() if not x.startswith('#')))
def perf():return rows(adb('exec-out','run-as','org.mkvdcu.android','cat','files/last-performance.csv'))
def metrics(row):
    return {m[0]:(int(m[1]),float(m[2])) for m in re.findall(r'(\w+)=(\d+)/([\d.eE+-]+)',row['inclusive_stage_count_total_ms'])}
def counts(row):return {m[0]:int(m[1]) for m in re.findall(r'(\w+)=(\d+)(?= |$)',row['inclusive_stage_count_total_ms'])}
limits=list(map(int,a.pool_limits.split(','))) if a.pool_limits else None
if limits and any(x<1 or x>64 for x in limits):raise ValueError('Pool limits must be 1..64')
segments=([ (0, limits[(i+j)%len(limits)]) for j in range(len(limits)) ] for i in range(a.pairs)) if limits else [(m,-1) for m in [1,0]*a.pairs]
segments=[item for group in segments for item in group] if limits else segments
results=[]
try:
 for i,(mode,pool_limit) in enumerate(segments):
    adb('shell','am','broadcast','-a','org.mkvdcu.android.DEBUG_COMMAND_POOL_LIMIT','-p','org.mkvdcu.android','--ei','limit',str(pool_limit))
    adb('shell','am','broadcast','-a','org.mkvdcu.android.DEBUG_FRAME_GENERATION','-p','org.mkvdcu.android','--ei','mode',str(mode))
    time.sleep(4);first=perf()[-1];time.sleep(a.seconds)
    blob=adb('exec-out','run-as','org.mkvdcu.android','cat','files/last-performance.csv');data=rows(blob);last=data[-1]
    frames=adb('exec-out','run-as','org.mkvdcu.android','cat','files/last-frame-times.csv')
    prefix=a.output/f'{i:02d}-fg-enabled-{mode}-pool-{pool_limit}'
    prefix.with_suffix('.csv').write_bytes(blob);prefix.with_suffix('.frames.csv').write_bytes(frames)
    prefix.with_suffix('.png').write_bytes(adb('exec-out','screencap','-p'))
    prefix.with_suffix('.memory.txt').write_bytes(adb('shell','dumpsys','meminfo','org.mkvdcu.android:game'))
    prefix.with_suffix('.thermal.txt').write_bytes(adb('shell','dumpsys','thermalservice'))
    n=int(last['guest_frames'])-int(first['guest_frames']);seconds=(int(last['elapsed_ms'])-int(first['elapsed_ms']))/1000
    if n<=0 or seconds<=0:raise RuntimeError('No game progress')
    selected=[r for r in data if int(first['elapsed_ms'])<=int(r['elapsed_ms'])<=int(last['elapsed_ms'])]
    if {int(r['fg_benchmark']) for r in selected}!={mode}:raise RuntimeError('Override not applied throughout segment')
    if {int(r['idle_pool_limit_benchmark']) for r in selected}!={pool_limit}:raise RuntimeError('Pool override mismatch')
    begin=list(map(int,first['fg_generated_bypassed'].split('/')));end=list(map(int,last['fg_generated_bypassed'].split('/')))
    generated,bypassed=[y-x for x,y in zip(begin,end)]
    if (mode==1 and generated==0) or (mode==0 and (generated!=0 or bypassed==0)):
        raise RuntimeError(f'Native FG path mismatch: {generated=}, {bypassed=}')
    before,after=metrics(first),metrics(last)
    cpu={k:(after[k][1]-before[k][1])/n for k in after if not k.startswith('gpu_')}
    gpu={k:{'samples':after[k][0]-before[k][0],'mean_ms':(after[k][1]-before[k][1])/(after[k][0]-before[k][0])}
         for k in after if k.startswith('gpu_') and after[k][0]>before[k][0]}
    c0,c1=counts(first),counts(last)
    counters={k:(c1[k]-c0.get(k,0))/n for k in c1}
    vals=sorted(float(r['interval_ms']) for r in rows(frames) if int(first['guest_frames'])<int(r['frame_id'])<=int(last['guest_frames']))
    result=dict(segment=i,fg_enabled=bool(mode),idle_pool_limit=pool_limit,scene=a.scene,frames=n,seconds=seconds,fps=n/seconds,
        median_ms=statistics.median(vals) if vals else None,p95_ms=vals[math.ceil(len(vals)*.95)-1] if vals else None,
        raw_intervals=len(vals),generated=generated,bypassed=bypassed,cpu_inclusive_wall_ms_per_frame=cpu,
        gpu_timestamp_samples=gpu or 'unavailable',counters_per_frame=counters,actual_driver=last['actual_driver'],
        thermal_statuses=sorted({int(r['thermal_status']) for r in selected}))
    results.append(result);(a.output/'results.json').write_text(json.dumps(results,indent=2));print(json.dumps(result),flush=True)
finally:
 adb('shell','am','broadcast','-a','org.mkvdcu.android.DEBUG_FRAME_GENERATION','-p','org.mkvdcu.android','--ei','mode','-1')

 adb('shell','am','broadcast','-a','org.mkvdcu.android.DEBUG_COMMAND_POOL_LIMIT','-p','org.mkvdcu.android','--ei','limit','-1')
