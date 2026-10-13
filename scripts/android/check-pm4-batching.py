"""Compile production PM4 wait/flush methods with a deterministic host fixture."""
import os,pathlib,subprocess,tempfile
root=pathlib.Path(__file__).resolve().parents[1];sdk=root/'MKVDCU-Recomp/references/rexglue-sdk'
def method(path,signature):
 text=path.read_text();start=text.index(signature);end=text.index('\n}',start)+2;return text[start:end]
wait=method(sdk/'src/graphics/command_processor.cpp','bool CommandProcessor::ExecutePacketType3_WAIT_REG_MEM(')
prepare=method(sdk/'src/graphics/vulkan/command_processor.cpp','void VulkanCommandProcessor::PrepareForWait()')
canend=method(sdk/'src/graphics/vulkan/command_processor.cpp','bool VulkanCommandProcessor::CanEndSubmissionImmediately() const')
compiler=sorted(pathlib.Path('C:/Program Files/Microsoft Visual Studio/18/Community/VC/Tools/MSVC').glob('*/bin/Hostx64/x64/cl.exe'))[-1];msvc=compiler.parents[3]
winsdk=pathlib.Path('C:/Program Files (x86)/Windows Kits/10');version=sorted((winsdk/'Include').glob('10.*'))[-1].name
env=dict(os.environ);env['PATH']=str(compiler.parent)+os.pathsep+env['PATH']
env['INCLUDE']=os.pathsep.join(str(p) for p in [msvc/'include',winsdk/'Include'/version/'ucrt',winsdk/'Include'/version/'shared',winsdk/'Include'/version/'um'])
env['LIB']=os.pathsep.join(str(p) for p in [msvc/'lib/x64',winsdk/'Lib'/version/'ucrt/x64',winsdk/'Lib'/version/'um/x64'])
fixture=r'''#include <cassert>
#include <array>
#include <cstdint>
#include <chrono>
#define SCOPE_profile_cpu_f(x)
#define REXCVAR_GET(x) x
bool vulkan_submit_on_primary_buffer_end=true,vsync=true;
namespace rex::thread {void MaybeYield(){assert(false&&"unsubmitted poll");}template<class T>void Sleep(T){assert(false&&"unsubmitted poll");}void SyncMemory(){}}
namespace memory {struct RingBuffer {std::array<uint32_t,5> words;unsigned pos=0;template<class T>T ReadAndSwap(){return T(words[pos++]);}};}
namespace xenos {enum class Endian:uint32_t{None};uint32_t GpuSwap(uint32_t x,Endian){return x;}}
constexpr uint32_t XE_GPU_REG_COHER_STATUS_HOST=0x100;
struct Memory {uint32_t value=0;void* TranslatePhysical(uint32_t){return &value;}};
class CommandProcessor {public:Memory mem;Memory* memory_=&mem;bool worker_running_=true;
virtual void PrepareForWait(){}void ReturnFromWait(){}void MakeCoherent(){}uint32_t ReadRegisterValue(uint32_t){return mem.value;}
bool ExecutePacketType3_WAIT_REG_MEM(memory::RingBuffer*,uint32_t,uint32_t);};
struct PipelineCache {bool creating=false;bool IsCreatingPipelines()const{return creating;}};
class VulkanCommandProcessor:public CommandProcessor {public:bool submission_open_=true,scratch_buffer_used_=false;struct{bool valid=false;}active_occlusion_query_;PipelineCache cache;PipelineCache* pipeline_cache_=&cache;int submits=0;
bool EndSubmission(bool swap){assert(!swap);++submits;submission_open_=false;mem.value=1;return true;}
void PrepareForWait()override;bool CanEndSubmissionImmediately()const;};
'''
main=r'''
int main(){
 vulkan_submit_on_primary_buffer_end=false;
 for(auto delay:{0u,0xffu,0x100u}){VulkanCommandProcessor cp;memory::RingBuffer r{{0x13,0,1,UINT32_MAX,delay}};assert(cp.ExecutePacketType3_WAIT_REG_MEM(&r,0,5));assert(cp.submits==1&&r.pos==5);}
 {VulkanCommandProcessor cp;cp.cache.creating=true;assert(!cp.CanEndSubmissionImmediately());cp.PrepareForWait();assert(cp.submits==1);} // idle must not leave pending work on CPU
 {VulkanCommandProcessor cp;cp.scratch_buffer_used_=true;assert(!cp.CanEndSubmissionImmediately());cp.PrepareForWait();assert(cp.submits==0);}
 {VulkanCommandProcessor cp;cp.active_occlusion_query_.valid=true;assert(!cp.CanEndSubmissionImmediately());cp.PrepareForWait();assert(cp.submits==0);}
 {VulkanCommandProcessor cp;assert(cp.CanEndSubmissionImmediately());cp.PrepareForWait();assert(cp.submits==1);cp.PrepareForWait();assert(cp.submits==1);}
 vulkan_submit_on_primary_buffer_end=true;
 {VulkanCommandProcessor cp;cp.PrepareForWait();assert(cp.submits==0);cp.mem.value=1;memory::RingBuffer r{{0x13,0,1,UINT32_MAX,0}};assert(cp.ExecutePacketType3_WAIT_REG_MEM(&r,0,5));assert(cp.submits==0);}
}
'''
with tempfile.TemporaryDirectory(prefix='mkvdcu-pm4-batch-') as tmp:
 d=pathlib.Path(tmp);p=d/'test.cpp';p.write_text(fixture+'\n'+wait+'\n'+prepare+'\n'+canend+'\n'+main)
 subprocess.run([str(compiler),'/nologo','/EHsc','/std:c++17',str(p),'/Fe:'+str(d/'test.exe')],cwd=tmp,env=env,check=True)
 subprocess.run([str(d/'test.exe')],check=True,timeout=5)
print('PASS production-method fixture: short/long PM4 waits flush before polling, idle pipeline progress, scratch/query guards, default unchanged (no Vulkan GPU)')
