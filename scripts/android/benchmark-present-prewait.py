"""A/B of pre-present fence waiting; same running scene, no draw/readback override."""
import argparse,csv,io,json,pathlib,re,subprocess,time,statistics
p=argparse.ArgumentParser()
p.add_argument('--output',type=pathlib.Path,required=True)
p.add_argument('--seconds',type=int,default=20)
p.add_argument('--pairs',type=int,default=3)
p.add_argument('--scene',required=True)
p.add_argument('--mode',choices=['prewait','queue'],default='prewait')
p.add_argument('--wait-fight',action='store_true')
p.add_argument('--wait-draws-per-frame',type=int,default=0)
a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
def adb(*args):return subprocess.check_output(['adb',*args])
def rows(blob):return list(csv.DictReader(x for x in blob.decode().splitlines() if not x.startswith('#')))
def perf():return rows(adb('exec-out','run-as','org.mkvdcu.android','cat','files/last-performance.csv'))
def metric(row,key):
 m=re.search(r'\b'+key+r'=(\d+)/([\d.eE+-]+)',row['inclusive_stage_count_total_ms'])
 return (int(m[1]),float(m[2])) if m else (0,0.)
def fight_visible(blob):
 from PIL import Image
 import numpy as np
 image=np.asarray(Image.open(io.BytesIO(blob)).convert('RGB'))
 h,w=image.shape[:2];region=image[int(h*.075):int(h*.145),int(w*.16):int(w*.84)].astype('int32')
 return int(((region[:,:,1]>130)&(region[:,:,1]>region[:,:,0]*1.3)&(region[:,:,1]>region[:,:,2]*1.3)).sum())>1000
if a.wait_draws_per_frame or a.wait_fight:
 end=time.monotonic()+360
 while True:
  rs=perf()
  if len(rs)>4:
   first,last=rs[-4],rs[-1];n=int(last['guest_frames'])-int(first['guest_frames'])
   if n>0 and (metric(last,'draw_cpu')[0]-metric(first,'draw_cpu')[0])/n>=a.wait_draws_per_frame:
    if not a.wait_fight or fight_visible(adb('exec-out','screencap','-p')):break
  if time.monotonic()>end:raise RuntimeError('Target scene not observed in 360s')
  time.sleep(3)
results=[]
action='org.mkvdcu.android.DEBUG_PRESENT_QUEUE' if a.mode=='queue' else 'org.mkvdcu.android.DEBUG_PRESENT_PREWAIT'
try:
 for i,enabled in enumerate([False,True]*a.pairs):
  adb('shell','am','broadcast','-a',action,'-p','org.mkvdcu.android','--ez','enabled',str(enabled).lower())
  time.sleep(4);first=perf()[-1];time.sleep(a.seconds)
  data=adb('exec-out','run-as','org.mkvdcu.android','cat','files/last-performance.csv');rs=rows(data);last=rs[-1]
  frames=adb('exec-out','run-as','org.mkvdcu.android','cat','files/last-frame-times.csv')
  prefix=a.output/f'{i:02d}-{a.mode}-{int(enabled)}'
  prefix.with_suffix('.csv').write_bytes(data);prefix.with_suffix('.frames.csv').write_bytes(frames)
  screenshot=adb('exec-out','screencap','-p');prefix.with_suffix('.png').write_bytes(screenshot)
  if a.wait_fight and not fight_visible(screenshot):raise RuntimeError('Fight HUD disappeared; discard segment')
  prefix.with_suffix('.thermal.txt').write_bytes(adb('shell','dumpsys','thermalservice'))
  count=int(last['guest_frames'])-int(first['guest_frames']);seconds=(int(last['elapsed_ms'])-int(first['elapsed_ms']))/1000
  if count<=0 or seconds<=0:raise RuntimeError('No frame progress')
  wait_count=metric(last,'present_ready_wait_cpu')[0]-metric(first,'present_ready_wait_cpu')[0]
  if a.mode=='prewait' and bool(wait_count)!=enabled:raise RuntimeError('Pre-wait override did not execute as requested')
  column='present_queue_benchmark' if a.mode=='queue' else 'present_prewait_benchmark'
  if column in last and int(last[column])!=int(enabled):raise RuntimeError('CSV override mismatch')
  vals=sorted(float(r['interval_ms']) for r in rows(frames) if int(first['guest_frames'])<int(r['frame_id'])<=int(last['guest_frames']))
  stages={}
  for key in ['draw_cpu','fence_wait','queue_acquire_cpu','queue_submit_cpu','present_ready_wait_cpu','present_call_cpu','present_queue_acquire_cpu']:
   stages[key]=(metric(last,key)[1]-metric(first,key)[1])/count
  result=dict(segment=i,mode=a.mode,enabled=enabled,scene=a.scene,frames=count,seconds=seconds,fps=count/seconds,
    median_ms=statistics.median(vals) if vals else None,p95_ms=vals[min(len(vals)-1,__import__('math').ceil(len(vals)*.95)-1)] if vals else None,
    cpu_wall_ms_per_frame=stages,actual_driver=last['actual_driver'],
    thermal_statuses=sorted(set(int(r['thermal_status']) for r in rs if int(first['elapsed_ms'])<=int(r['elapsed_ms'])<=int(last['elapsed_ms']))))
  results.append(result);(a.output/'results.json').write_text(json.dumps(results,indent=2));print(json.dumps(result),flush=True)
finally:
 adb('shell','am','broadcast','-a',action,'-p','org.mkvdcu.android','--ez','enabled','false')
