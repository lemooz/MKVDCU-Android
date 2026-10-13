"""Compile the production POSIX multi-wait method with controllable conditions."""
import pathlib, tempfile, subprocess, os
root = pathlib.Path(__file__).resolve().parents[1]
source = (root/'MKVDCU-Recomp/references/rexglue-sdk/src/core/threading_posix.cpp').read_text()
begin = source.index('  static std::pair<WaitResult, size_t> WaitMultiple(')
brace = source.index('{', begin); depth = 1; end = brace + 1
while depth:
    depth += (source[end] == '{') - (source[end] == '}'); end += 1
method = source[begin:end]
compiler = sorted(pathlib.Path('C:/Program Files/Microsoft Visual Studio/18/Community/VC/Tools/MSVC').glob('*/bin/Hostx64/x64/cl.exe'))[-1]
msvc = compiler.parents[3]
sdk = pathlib.Path('C:/Program Files (x86)/Windows Kits/10')
version = sorted((sdk/'Include').glob('10.*'))[-1].name
env = dict(os.environ)
env['PATH'] = str(compiler.parent)+os.pathsep+env['PATH']
env['INCLUDE'] = os.pathsep.join(map(str,[msvc/'include',sdk/'Include'/version/'ucrt',sdk/'Include'/version/'shared',sdk/'Include'/version/'um']))
env['LIB'] = os.pathsep.join(map(str,[msvc/'lib/x64',sdk/'Lib'/version/'ucrt'/ 'x64',sdk/'Lib'/version/'um'/'x64']))
with tempfile.TemporaryDirectory(prefix='mkvdcu-multiwait-') as tmp:
    p = pathlib.Path(tmp)
    (p/'test.cpp').write_text('''#include <cassert>
#include <algorithm>
#include <chrono>
#include <mutex>
#include <limits>
#include <thread>
#include <vector>
#include <atomic>
#define assert_true(x) assert(x)
enum class WaitResult { kSuccess, kTimeout, kFailed };
class PosixConditionBase { public:
 std::mutex mutex_; bool ready=false; unsigned consumed=0;
 bool signaled(){return ready;}
 void post_execution(){ready=false;++consumed;}
 WaitResult Wait(std::chrono::milliseconds){return WaitResult::kTimeout;}
''' + method + '''
};
int main(){
 using C=PosixConditionBase; using namespace std::chrono_literals;
 C a,b,c; c.ready=true;
 auto r=C::WaitMultiple({&a,&b,&c},false,0ms);
 assert(r.first==WaitResult::kSuccess && r.second==2 && c.consumed==1);
 a.ready=b.ready=true;
 r=C::WaitMultiple({&a,&b},true,0ms);
 assert(r.first==WaitResult::kSuccess && a.consumed==1 && b.consumed==1);
 r=C::WaitMultiple({&a,&b},false,0ms);assert(r.first==WaitResult::kTimeout);
 std::atomic<bool> locked{false};
 std::thread owner([&]{std::lock_guard guard(a.mutex_);locked=true;std::this_thread::sleep_for(150ms);});
 while(!locked)std::this_thread::yield();
 auto start=std::chrono::steady_clock::now();
 r=C::WaitMultiple({&a,&b},false,2ms);
 auto elapsed=std::chrono::steady_clock::now()-start;
 assert(r.first==WaitResult::kTimeout && elapsed<100ms);
 owner.join();
 std::thread signal([&]{std::this_thread::sleep_for(10ms);std::lock_guard guard(b.mutex_);b.ready=true;});
 r=C::WaitMultiple({&a,&b},false,200ms);signal.join();
 assert(r.first==WaitResult::kSuccess && r.second==1);
}
''')
    subprocess.run([str(compiler),'/nologo','/EHsc','/std:c++17',str(p/'test.cpp'),'/Fe:'+str(p/'test.exe')],cwd=tmp,env=env,check=True)
    subprocess.run([str(p/'test.exe')],check=True)
print('PASS production POSIX multi-wait: timeout under contention, polls, wait-all, signal progress (host)')
