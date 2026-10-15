"""Exercise production settings/argument code with small Android data stubs (no GPU)."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
jdk = Path('C:/Program Files/Android/Android Studio/jbr/bin')
sources = {
'android/content/SharedPreferences.java': '''package android.content;
public interface SharedPreferences {
 int getInt(String key,int value); boolean getBoolean(String key,boolean value); Editor edit();
 interface Editor { Editor putInt(String key,int value); Editor putBoolean(String key,boolean value); Editor remove(String key); void apply(); }
}''',
'android/content/Context.java': '''package android.content;
public class Context {
 public static final int MODE_PRIVATE=0;
 private final Memory memory=new Memory();
 public SharedPreferences getSharedPreferences(String name,int mode) { return memory; }
 static class Memory implements SharedPreferences,SharedPreferences.Editor {
  java.util.Map<String,Object> values=new java.util.HashMap<>();
  public int getInt(String k,int d){ return values.containsKey(k)?(Integer)values.get(k):d; }
  public boolean getBoolean(String k,boolean d){ return values.containsKey(k)?(Boolean)values.get(k):d; }
  public Editor edit(){ return this; }
  public Editor putInt(String k,int v){values.put(k,v);return this;}
  public Editor putBoolean(String k,boolean v){values.put(k,v);return this;}
  public Editor remove(String k){values.remove(k);return this;}
  public void apply(){}
 }
}''',
'android/content/Intent.java': '''package android.content;
public class Intent {
 private final java.util.Map<String,Object> values=new java.util.HashMap<>();
 public Intent putExtra(String k,int v){values.put(k,v);return this;}
 public Intent putExtra(String k,boolean v){values.put(k,v);return this;}
 public int getIntExtra(String k,int d){return values.containsKey(k)?(Integer)values.get(k):d;}
 public boolean getBooleanExtra(String k,boolean d){return values.containsKey(k)?(Boolean)values.get(k):d;}
 public boolean hasExtra(String k){return values.containsKey(k);}
}''',
'org/mkvdcu/android/GraphicsHarness.java': '''package org.mkvdcu.android;
import android.content.*; import java.util.*;
public class GraphicsHarness {
 static List<String> args(Intent i){List<String> a=new ArrayList<>();GraphicsSettings.appendArguments(i,a);return a;}
 static int checks; static void check(boolean b){checks++;if(!b)throw new AssertionError("graphics settings check "+checks);}
 public static void main(String[] unused){
  GraphicsSettings g=new GraphicsSettings(new Context()); Intent i=new Intent();g.attach(i);
  check(args(i).contains("--anisotropic_override=-1"));check(args(i).contains("--texture_cache_memory_limit_hard=768"));
  check(args(i).contains("--vulkan_gpu_timing_interval=64"));
  check(args(i).contains("--vulkan_pipeline_creation_threads=2"));
  check(args(i).contains("--vulkan_gpu_stage_timing_interval=0"));
  check(args(i).contains("--port_gpu_ring_backoff=true"));
  check(args(i).contains("--vulkan_fragment_relaxed_precision=false"));
  check(args(i).contains("--vulkan_dxt1_compact=false"));
  g.setRingBackoff(false);g.setRelaxedFragment(true);g.setCompactDxt1(true);
  Intent experimental=new Intent();g.attach(experimental);
  check(args(experimental).contains("--port_gpu_ring_backoff=false"));
  check(args(experimental).contains("--vulkan_fragment_relaxed_precision=true"));
  check(args(experimental).contains("--vulkan_dxt1_compact=true"));
  check(args(i).contains("--vulkan_dxt1_compact=false")); // running session is immutable
  check(args(i).contains("--vulkan_submit_on_primary_buffer_end=true"));
  check(args(i).contains("--video_mode_width=1280")&&args(i).contains("--video_mode_height=720"));
  g.setVideoMode(1);g.setGpuTiming(0);g.setBatch(true);
  g.setPipelineThreads(2);g.setGpuStages(true);
  g.setFilter(0);g.setCacheMb(512);g.setSsa(true);g.setRenderArea(true);g.setSustained(true);g.setMipDrop(2);
  check(args(i).contains("--anisotropic_override=-1")); // immutable session snapshot
  Intent j=new Intent();g.attach(j);
  check(args(j).contains("--anisotropic_override=0"));check(args(j).contains("--texture_cache_memory_limit_hard=1024"));
  check(args(j).contains("--vulkan_texture_mip_drop=2"));
  check(args(j).contains("--vulkan_pipeline_creation_threads=2"));
  check(args(j).contains("--vulkan_gpu_stage_timing_interval=64"));
  check(args(j).contains("--video_mode_width=1280")&&args(j).contains("--video_mode_height=720"));
  check(args(j).contains("--vulkan_gpu_timing_interval=0")&&args(j).contains("--vulkan_submit_on_primary_buffer_end=false"));
  check(!args(j).stream().anyMatch(s->s.contains("refresh_rate"))); // never slow simulation to fake 30 FPS
  check(GraphicsSettings.ssa(j)&&GraphicsSettings.area(j)&&GraphicsSettings.sustained(j));
  j.putExtra("benchmark_spirv_optimize",false).putExtra("benchmark_scissor_area",false);
  check(GraphicsSettings.ssa(j)!=BuildConfig.DEBUG);check(GraphicsSettings.area(j)!=BuildConfig.DEBUG);
  j.putExtra("graphics_filter",255).putExtra("graphics_cache_mb",-1);
  check(args(j).contains("--anisotropic_override=-1"));check(args(j).contains("--texture_cache_memory_limit_hard=768"));
  g.setFilter(255);g.setCacheMb(0);check(g.filter()==-1&&g.cacheMb()==384);
  g.setMipDrop(255);check(g.mipDrop()==0);
  g.setVideoMode(2);g.setGpuTiming(1);Intent low=new Intent();g.attach(low);
  check(args(low).contains("--video_mode_width=1280")&&args(low).contains("--video_mode_height=720"));
  g.setVideoMode(255);g.setGpuTiming(-1);check(g.videoMode()==0&&g.gpuTiming()==64);
  g.setPipelineThreads(255);check(g.pipelineThreads()==-1);
  check(args(i).contains("--vulkan_transfer_render_area=true"));
  g.setTurnipMode(2);g.setPanvkLateStencil(true);
  g.applyPreset(GraphicsSettings.PRESET_LOW);
  check(g.activePreset()==GraphicsSettings.PRESET_LOW&&g.presetName().contains("Baixo"));
  Intent pLow=new Intent();g.attach(pLow);
  check(args(pLow).contains("--port_gpu_ring_backoff=true"));
  check(args(pLow).contains("--vulkan_fragment_relaxed_precision=false")&&args(pLow).contains("--vulkan_dxt1_compact=false"));
  check(args(pLow).contains("--anisotropic_override=0"));
  check(args(pLow).contains("--texture_cache_memory_limit_hard=512"));
  check(args(pLow).contains("--vulkan_submit_on_primary_buffer_end=false"));
  check(args(pLow).contains("--vulkan_gpu_timing_interval=64"));
  check(args(pLow).contains("--port_scene_resolution_percent=50"));
  check(args(pLow).contains("--port_internal_resolution_percent=100"));
  check(args(pLow).contains("--present_frame_rate_limit=30"));
  check(GraphicsSettings.scaleDescription(pLow).contains("520×312"));
  check(!args(pLow).stream().anyMatch(s->s.contains("disable_shadow")||s.contains("fast_postprocess")));
  g.applyPreset(GraphicsSettings.PRESET_HIGH);
  check(g.activePreset()==GraphicsSettings.PRESET_HIGH&&g.presetName().contains("Alto"));
  Intent pHigh=new Intent();g.attach(pHigh);
  check(args(pHigh).contains("--anisotropic_override=2"));
  check(args(pHigh).contains("--texture_cache_memory_limit_hard=1536"));
  check(args(pHigh).contains("--vulkan_spirv_optimize=true"));
  g.applyPreset(GraphicsSettings.PRESET_BALANCED);
  check(g.activePreset()==GraphicsSettings.PRESET_BALANCED);
  Intent pBalanced=new Intent();g.attach(pBalanced);
  check(args(pBalanced).contains("--port_scene_resolution_percent=75"));
  check(GraphicsSettings.scaleDescription(pBalanced).contains("780×468"));
  check(g.turnipMode()==2&&g.panvkLateStencil()); // Presets preserve driver choices.
  g.setScalePercent(100);check(g.activePreset()==-1&&g.presetName().equals("Personalizado"));
  check(args(pBalanced).contains("--port_scene_resolution_percent=75")); // immutable snapshot
  g.setScalePercent(255);check(g.scalePercent()==100);
  g.setCompactMemory(true);Intent compact=new Intent();g.attach(compact);
  check(args(compact).contains("--texture_cache_memory_limit_soft=128"));
  check(args(compact).contains("--texture_cache_memory_limit_hard=256"));
  g.setEconomy(true);g.setFpsLimit(0);Intent economy=new Intent();g.attach(economy);
  check(args(economy).contains("--present_frame_rate_limit=30"));
  g.setEconomy(false);
  check(java.util.Arrays.equals(GraphicsSettings.FPS_LIMITS,new int[]{0,24,30,45,60,90,120}));
  for(int cap:GraphicsSettings.FPS_LIMITS) {
   g.setFpsLimit(cap);g.setFrameGeneration(true);Intent paced=new Intent();g.attach(paced);
   check(g.fpsLimit()==cap&&GraphicsSettings.effectiveFpsLimit(paced)==cap);
   check(GraphicsSettings.displayRateRequestHz(paced)==cap); // FG cannot double the final cap.
   check(args(paced).contains("--present_frame_rate_limit="+cap));
   check(args(paced).contains("--gpu_frame_rate_limit=0")); // Preserve simulation speed.
   g.setFpsLimit(60);check(GraphicsSettings.effectiveFpsLimit(paced)==cap);
  }
  g.setEconomy(true);g.setFpsLimit(24);Intent pwr24=new Intent();g.attach(pwr24);
  check(GraphicsSettings.effectiveFpsLimit(pwr24)==24);
  g.setFpsLimit(45);Intent pwr45=new Intent();g.attach(pwr45);
  check(GraphicsSettings.effectiveFpsLimit(pwr45)==30);
  g.setEconomy(false);g.setFpsLimit(25);check(g.fpsLimit()==60);
  g.applyPreset(-1);check(g.activePreset()==-1); // Invalid preset leaves settings alone.
  g.reset();check(!g.ssa()&&g.renderArea()&&!g.sustained()&&g.mipDrop()==0);
  check(g.ringBackoff()&&!g.relaxedFragment()&&!g.compactDxt1());
  check(!g.batch()&&g.videoMode()==0&&g.gpuTiming()==64);
  check(g.pipelineThreads()==-1&&!g.gpuStages());
  check(GraphicsSettings.summary(i).contains("requested_internal="));
 }
}'''
}
with tempfile.TemporaryDirectory(prefix='mkvdcu-settings-') as temporary:
    directory = Path(temporary)
    for name, source in sources.items():
        path = directory/name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text(source, encoding="utf-8")
    for debug in (False, True):
        (directory/'org/mkvdcu/android/BuildConfig.java').write_text(
            'package org.mkvdcu.android; final class BuildConfig { static final boolean DEBUG='+str(debug).lower()+'; }')
        subprocess.run([str(jdk/'javac.exe'), '-encoding', 'UTF-8', '-d', temporary,
            str(root/'android/app/src/main/java/org/mkvdcu/android/GraphicsSettings.java'),
            *map(str,directory.rglob('*.java'))], check=True)
        subprocess.run([str(jdk/'java.exe'), '-cp', temporary, 'org.mkvdcu.android.GraphicsHarness'], check=True)
        print(f'PASS settings snapshot, validation, reset and arguments (debug={debug})')
