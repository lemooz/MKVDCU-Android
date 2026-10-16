"""Exercise the production library loader before and after HUD construction."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
jdk = Path('C:/Program Files/Android/Android Studio/jbr/bin')
activity = (root/'android/app/src/main/java/org/mkvdcu/android/GameActivity.java').read_text(encoding='utf8')
start = activity.index('    public void loadLibraries() {')
end = activity.index('\n    @Override', start)
loader = activity[start:end]
source = '''
class SDLActivity { int loads; public void loadLibraries(){++loads;} }
class Intent { String getStringExtra(String key){return "system";} }
class Log { static void i(String tag,String text){} }
class PerformanceOverlayView {
 int cap=-1; String policy;
 void setPresentationLimit(int v){cap=v;}
 void setCpuPolicyStatus(String v){policy=v;}
}
class GraphicsSettings {
 static int cores(Intent i){return 0;}
 static boolean economy(Intent i){return false;}
 static int effectiveFpsLimit(Intent i){return 30;}
 static int turnipMode(Intent i){return 0;}
 static boolean panvkLateStencil(Intent i){return false;}
}
class DriverStore {
 DriverStore(Object context){}
 Driver get(String id){return new Driver();}
 static class Driver {
  String kind="system";int turnipMode;boolean panvkLateStencil;
  boolean usable(){return true;}String reason(){return "";}
 }
}
class DriverNative {
 static String error="";
 static String configureCpuLimit(int n,boolean e){return "automatic";}
 static String configure(Object context,DriverStore.Driver d){return error;}
 static void setDiagnosticMode(boolean v){}
}
class GameActivity extends SDLActivity {
 String cpuPolicyStatus,TAG="MKVDCU";
 boolean diagnostics;
 PerformanceOverlayView performanceOverlay;
 Intent getIntent(){return new Intent();}
''' + loader + '''
 public static void main(String[] args){
  GameActivity a=new GameActivity();
  a.loadLibraries(); // SDL calls this before constructing the content/HUD.
  if(a.loads!=1)throw new AssertionError("Native loading did not run");
  a.performanceOverlay=new PerformanceOverlayView();a.loadLibraries();
  if(a.loads!=2||a.performanceOverlay.cap!=30||!a.performanceOverlay.policy.equals("automatic"))
   throw new AssertionError("HUD configuration after construction");
  DriverNative.error="controlled loader failure";
  try {a.loadLibraries();throw new AssertionError("Failure suppressed");}
  catch(UnsatisfiedLinkError expected){
   if(!expected.getMessage().equals(DriverNative.error)||a.loads!=2)
    throw new AssertionError("Original loader failure must remain visible");
  }
 }
}
'''
assert 'if (SDLActivity.mBrokenLibraries) return;' in activity
assert 'if (!SDLActivity.mBrokenLibraries) nativeSetVirtualGamepadStateV2' in activity
with tempfile.TemporaryDirectory(prefix='mkvdcu-startup-') as temporary:
    file=Path(temporary)/'GameActivity.java'
    file.write_text(source,encoding='utf8')
    subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-d',temporary,str(file)],check=True)
    subprocess.run([str(jdk/'java.exe'),'-cp',temporary,'GameActivity'],check=True)
print('PASS production loader: absent HUD, existing HUD and preserved loader failure')
