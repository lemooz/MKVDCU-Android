"""Offline cadence/coalescing regression using production SDL method bodies."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
sdk = root/'references/rexglue-sdk'
compiler = sorted(Path('C:/Program Files/Microsoft Visual Studio/18/Community/VC/Tools/MSVC').glob('*/bin/Hostx64/x64/cl.exe'))[-1]
msvc = compiler.parents[3]
winsdk = Path('C:/Program Files (x86)/Windows Kits/10')
version = sorted((winsdk/'Include').glob('10.*'))[-1].name
env = dict(os.environ)
env['PATH'] = str(compiler.parent)+os.pathsep+env['PATH']
env['INCLUDE'] = os.pathsep.join(str(p) for p in [msvc/'include', winsdk/'Include'/version/'ucrt', winsdk/'Include'/version/'shared', winsdk/'Include'/version/'um'])
env['LIB'] = os.pathsep.join(str(p) for p in [msvc/'lib/x64', winsdk/'Lib'/version/'ucrt/x64', winsdk/'Lib'/version/'um/x64'])
window = (sdk/'src/ui/window_sdl.cpp').read_text(encoding='utf8')
methods = window[window.index('bool WindowSDL::RequestPaintImpl()'):window.index('void WindowSDL::HandleWindowEvent(')]
source = r'''
#include <cassert>
#include <atomic>
#include <functional>
#include <rex/ui/presentation_pacer.h>
uint64_t clock_ns = 0;
int test_limit = 0, pushes = 0;
bool accept_event = true;
uint64_t PaintTimeNanoseconds() { return clock_ns; }
#define REXCVAR_GET(x) test_limit
#define REXLOG_INFO(...) ((void)0)
#define REXLOG_ERROR(...) ((void)0)
namespace rex::diagnostics {
struct HealthScope { HealthScope(const char*,int) {} };
enum class HealthEvent { PaintQueued, PaintConsumed };
void HealthRecord(HealthEvent) {}
}
struct SDL_Event { int type; struct { int windowID; } user; };
int SDL_PushEvent(SDL_Event*) { ++pushes; return accept_event; }
struct Context { int paint_event_type() { return 1; } };
struct WindowSDL {
 std::atomic<bool> paint_pending_{false};
 bool paint_deferred_=false, force_next_paint_=false;
 rex::ui::PresentationPacer presentation_pacer_;
 int sdl_window_id_=1, paints=0;
 bool forced=false;
 std::function<void()> callback;
 Context sdl_app_context() { return {}; }
 void OnPaint(bool force) { ++paints; forced=force; if(callback) callback(); }
 bool RequestPaintImpl();
 void HandlePaintEvent();
 int GetPendingPaintDelayMs();
 void HandleDeferredPaintFromUIThread();
};
''' + methods + r'''
int main() {
 for (uint32_t cap : {24u,30u,45u,60u,90u,120u}) {
  rex::ui::PresentationPacer p; p.Configure(cap);
  const uint64_t period=(1000000000ull+cap-1)/cap;
  assert(!p.DelayNanoseconds(100));p.PaintStarted(100);
  assert(p.DelayNanoseconds(100)==period);
  assert(p.DelayNanoseconds(100+period-1)==1);
  assert(!p.DelayNanoseconds(100+period));
  // No catch-up bursts after a stall.
  p.PaintStarted(100+period*10);assert(p.DelayNanoseconds(100+period*10)==period);
  p.Reset();assert(!p.DelayNanoseconds(0));
  p.PaintStarted(0);p.Configure(0);assert(!p.DelayNanoseconds(0));
  for (int i=0;i<100;++i) {p.PaintStarted(0);assert(!p.DelayNanoseconds(0));}
 }
 test_limit=24;clock_ns=0;WindowSDL w;
 assert(w.RequestPaintImpl());w.HandlePaintEvent();assert(w.paints==1);
 clock_ns=1000000;assert(w.RequestPaintImpl());w.force_next_paint_=true;
 w.HandlePaintEvent();assert(w.paints==1&&w.paint_deferred_&&w.paint_pending_);
 assert(w.GetPendingPaintDelayMs()==41);
 int previous_pushes=pushes;
 for(int i=0;i<10000;++i) assert(w.RequestPaintImpl());
 assert(pushes==previous_pushes); // One pending event, never an event flood.
 clock_ns=41666666;w.HandleDeferredPaintFromUIThread();assert(w.paints==1);
 clock_ns=41666667;w.HandleDeferredPaintFromUIThread();
 assert(w.paints==2&&w.forced&&!w.paint_pending_&&!w.paint_deferred_);
 // A callback may request the next frame while painting.
 w.callback=[&]{assert(w.RequestPaintImpl());};
 clock_ns+=41666667;w.HandlePaintEvent();assert(w.paint_pending_);
 w.callback={};w.HandlePaintEvent();assert(w.paint_deferred_);
 // Disable/reconfigure releases a deferred paint, including a stopped producer.
 test_limit=0;assert(w.GetPendingPaintDelayMs()==0);
 w.HandleDeferredPaintFromUIThread();assert(!w.paint_pending_&&!w.paint_deferred_);
 // Failed event posting must remain retryable.
 accept_event=false;assert(!w.RequestPaintImpl());assert(!w.paint_pending_);
 accept_event=true;assert(w.RequestPaintImpl());
 // Simulated slow paint never introduces an extra wait after its own duration.
 test_limit=60;w.callback=[] {clock_ns+=100000000;};
 w.HandlePaintEvent();assert(w.RequestPaintImpl());w.callback={};
 int paints=w.paints;w.HandlePaintEvent();assert(w.paints==paints+1);
}
'''
with tempfile.TemporaryDirectory(prefix='mkvdcu-pacer-') as temporary:
    directory = Path(temporary)
    file = directory/'pacer.cpp'
    file.write_text(source, encoding='utf8')
    binary = directory/'pacer.exe'
    subprocess.run([str(compiler), '/nologo', '/EHsc', '/std:c++17', '/O2',
                    '/I'+str(sdk/'include'), str(file), '/Fe:'+str(binary)],
                   cwd=directory, env=env, check=True)
    subprocess.run([str(binary)], check=True)
print('PASS 24/30/45/60/90/120 cadence, coalescing, deferred recovery, force, reentrant request and slow paint')
