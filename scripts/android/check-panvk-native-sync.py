"""Compile production native-sync adapter with fake Vulkan queues (no GPU)."""
import os
import pathlib
import subprocess
import tempfile

root = pathlib.Path(__file__).resolve().parents[1]
sdkroot = root/'MKVDCU-Recomp/references/rexglue-sdk'
source = sdkroot/'src/ui/vulkan/android_native_sync.cpp'
compiler = sorted(pathlib.Path('C:/Program Files/Microsoft Visual Studio/18/Community/VC/Tools/MSVC').glob('*/bin/Hostx64/x64/cl.exe'))[-1]
msvc = compiler.parents[3]
winsdk = pathlib.Path('C:/Program Files (x86)/Windows Kits/10')
version = sorted((winsdk/'Include').glob('10.*'))[-1].name
env = dict(os.environ)
env['PATH'] = str(compiler.parent)+os.pathsep+env['PATH']
env['INCLUDE'] = os.pathsep.join(map(str, [msvc/'include', winsdk/'Include'/version/'ucrt', winsdk/'Include'/version/'shared', winsdk/'Include'/version/'um']))
env['LIB'] = os.pathsep.join(map(str, [msvc/'lib/x64', winsdk/'Lib'/version/'ucrt/x64', winsdk/'Lib'/version/'um/x64']))
with tempfile.TemporaryDirectory(prefix='mkvdcu-native-sync-') as tmp:
    d = pathlib.Path(tmp)
    files = {
        'rex/ui/vulkan/api.h': '#pragma once\n#define VK_NO_PROTOTYPES\n#include <vulkan/vulkan.h>\n',
        'rex/logging.h': '#pragma once\n',
        'rex/diagnostics/performance.h': '#pragma once\nnamespace rex::diagnostics { enum class PerformanceStage {PresentNativeFenceCpu}; struct PerformanceScope {explicit PerformanceScope(PerformanceStage){}};}\n',
        'poll.h': '#pragma once\nstruct pollfd {int fd; short events,revents;}; enum {POLLIN=1,POLLERR=8,POLLNVAL=32}; inline int poll(pollfd*, unsigned long,int){return -1;}\n',
        'unistd.h': '#pragma once\ninline int close(int){return 0;}\n',
        'rex/ui/vulkan/device.h': r'''#pragma once
#include <rex/ui/vulkan/api.h>
#include <memory>
#include <mutex>
#include <vector>
namespace rex::ui::vulkan {
struct VulkanDevice {
 struct Queue {VkQueue queue=(VkQueue)1; mutable std::recursive_mutex mutex;};
 struct Family {std::vector<std::unique_ptr<Queue>> queues;};
 struct Acquisition {Queue& q; std::unique_lock<std::recursive_mutex> lock;
  explicit Acquisition(Queue& v):q(v),lock(v.mutex){} VkQueue queue()const{return q.queue;}};
 struct Functions {PFN_vkCreateFence vkCreateFence; PFN_vkDestroyFence vkDestroyFence;
  PFN_vkQueueSubmit vkQueueSubmit; PFN_vkWaitForFences vkWaitForFences; PFN_vkQueuePresentKHR vkQueuePresentKHR;} f;
 std::vector<Family> families;
 VulkanDevice(){families.resize(1);families[0].queues.emplace_back(new Queue);}
 VkDevice device()const{return (VkDevice)2;} const Functions& functions()const{return f;}
 const auto& queue_families()const{return families;} uint32_t queue_family_graphics_compute()const{return 0;}
 Acquisition AcquireQueue(uint32_t family,uint32_t index)const{return Acquisition(*families[family].queues[index]);}
};
}
''',
    }
    for name, content in files.items():
        p = d/name; p.parent.mkdir(parents=True, exist_ok=True); p.write_text(content)
    (d/'test.cpp').write_text('#include "'+source.as_posix()+'"\n'+r'''
#include <cassert>
#include <thread>
using namespace rex::ui::vulkan;
VulkanDevice ctx;
int submits,waits,presents,destroys;
bool native=false,expect_free=true;
VkResult submit_result=VK_SUCCESS,wait_result=VK_SUCCESS,present_result=VK_SUCCESS;
bool OtherThreadCanAcquire(){bool free=false;std::thread worker([&]{
 auto& m=ctx.families[0].queues[0]->mutex;free=m.try_lock();if(free)m.unlock();});worker.join();return free;}
VKAPI_ATTR VkResult VKAPI_CALL Create(VkDevice,const VkFenceCreateInfo*,const VkAllocationCallbacks*,VkFence* out){*out=(VkFence)3;return VK_SUCCESS;}
VKAPI_ATTR void VKAPI_CALL Destroy(VkDevice,VkFence,const VkAllocationCallbacks*){++destroys;}
VKAPI_ATTR VkResult VKAPI_CALL Submit(VkQueue,uint32_t n,const VkSubmitInfo* i,VkFence){
 assert(!OtherThreadCanAcquire());assert(n==1);assert(i->waitSemaphoreCount==1);assert(i->pWaitSemaphores[0]==(VkSemaphore)4);++submits;return submit_result;}
VKAPI_ATTR VkResult VKAPI_CALL Wait(VkDevice,uint32_t n,const VkFence*,VkBool32,uint64_t){
 assert(n==1);assert(OtherThreadCanAcquire()==expect_free);++waits;return wait_result;}
VKAPI_ATTR VkResult VKAPI_CALL Present(VkQueue q,const VkPresentInfoKHR* info){
 assert(!OtherThreadCanAcquire());++presents;
 if(native){assert(info->waitSemaphoreCount==1);return present_result;}
 assert(info->waitSemaphoreCount==0);int fd=99;
 assert(Release(q,0,nullptr,(VkImage)5,&fd)==VK_SUCCESS && fd==-1);
 return present_result;
}
void Reset(){submits=waits=presents=destroys=0;submit_result=wait_result=present_result=VK_SUCCESS;}
int main(){
 ctx.f={Create,Destroy,Submit,Wait,Present};PanvkNativeSyncRegister(&ctx);
 VkSemaphore sem=(VkSemaphore)4;VkPresentInfoKHR info{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
 info.waitSemaphoreCount=1;info.pWaitSemaphores=&sem;
 PanvkNativeSyncConfigure(true);
 assert(PanvkNativeSyncQueuePresent(ctx,0,0,info)==VK_SUCCESS);
 assert(submits==1&&waits==1&&presents==1&&destroys==1);
 assert(info.waitSemaphoreCount==1);assert(completed_present_queue==VK_NULL_HANDLE);
 Reset();present_result=VK_SUBOPTIMAL_KHR;
 assert(PanvkNativeSyncQueuePresent(ctx,0,0,info)==VK_SUBOPTIMAL_KHR);
 assert(completed_present_queue==VK_NULL_HANDLE);
 Reset();submit_result=VK_ERROR_OUT_OF_DEVICE_MEMORY;
 assert(PanvkNativeSyncQueuePresent(ctx,0,0,info)==submit_result);
 assert(waits==0&&presents==0&&destroys==1);
 Reset();wait_result=VK_TIMEOUT;
 assert(PanvkNativeSyncQueuePresent(ctx,0,0,info)==VK_ERROR_DEVICE_LOST);
 assert(presents==0&&destroys==0);
 // Conservative fallback must never unlock its caller's queue acquisition.
 Reset();expect_free=false;{auto outer=ctx.AcquireQueue(0,0);int fd=99;
 assert(Release(outer.queue(),1,&sem,(VkImage)5,&fd)==VK_SUCCESS && fd==-1);}
 assert(submits==1&&waits==1&&destroys==1);
 Reset();native=true;PanvkNativeSyncConfigure(false);
 assert(PanvkNativeSyncQueuePresent(ctx,0,0,info)==VK_SUCCESS);
 assert(submits==0&&waits==0&&presents==1);
 PanvkNativeSyncUnregister(&ctx);
}
''')
    vulkan = next(sdkroot.glob('thirdparty/**/vulkan/vulkan.h')).parents[1]
    subprocess.run([str(compiler), '/nologo', '/EHsc', '/std:c++17', '/I'+tmp,
                    '/I'+str(vulkan), str(d/'test.cpp'), '/Fe:'+str(d/'test.exe')],
                   cwd=tmp, env=env, check=True)
    subprocess.run([str(d/'test.exe')], check=True)
print('PASS production native-sync: queue free during prepared wait; locked for submit/present; semaphore consumed once; fallback ownership; failure/timeout; native path (no GPU)')
