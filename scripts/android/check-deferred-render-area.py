"""Replay the production deferred buffer with fake Vulkan command sinks (no GPU)."""
import os, pathlib, re, subprocess, tempfile
root=pathlib.Path(__file__).resolve().parents[1]
sdkroot=root/'MKVDCU-Recomp/references/rexglue-sdk'
source=sdkroot/'src/graphics/vulkan/deferred_command_buffer.cpp'
compiler=sorted(pathlib.Path('C:/Program Files/Microsoft Visual Studio/18/Community/VC/Tools/MSVC').glob('*/bin/Hostx64/x64/cl.exe'))[-1]
msvc=compiler.parents[3]; winsdk=pathlib.Path('C:/Program Files (x86)/Windows Kits/10')
version=sorted((winsdk/'Include').glob('10.*'))[-1].name
env=dict(os.environ)
env['PATH']=str(compiler.parent)+os.pathsep+env['PATH']
env['INCLUDE']=os.pathsep.join(str(p) for p in [msvc/'include',winsdk/'Include'/version/'ucrt',winsdk/'Include'/version/'shared',winsdk/'Include'/version/'um'])
env['LIB']=os.pathsep.join(str(p) for p in [msvc/'lib/x64',winsdk/'Lib'/version/'ucrt/x64',winsdk/'Lib'/version/'um/x64'])
names=set(re.findall(r'dfn\.(vk\w+)',source.read_text()))
observed={'vkCmdBeginRenderPass','vkCmdBeginRendering','vkCmdDraw','vkCmdDrawIndexed'}
fields='\n'.join(('PFN_'+n+' '+n+' = nullptr;') if n in observed else ('Ignore '+n+';') for n in sorted(names))
with tempfile.TemporaryDirectory(prefix='mkvdcu-deferred-area-') as tmp:
 d=pathlib.Path(tmp)
 files={
 'rex/assert.h':'#pragma once\n#include <cassert>\n#define assert_true(x) assert(x)\n#define assert_false(x) assert(!(x))\n#define assert_null(x) assert(!(x))\n#define assert_not_null(x) assert(x)\n#define assert_unhandled_case(x) assert(false)\n',
 'rex/math.h':'#pragma once\n#include <cstddef>\nnamespace rex {template<class T,class U> constexpr T align(T v,U a){return (v+T(a)-1)&~(T(a)-1);}}\n',
 'rex/dbg.h':'#pragma once\n',
 'rex/graphics/flags.h':'#pragma once\n#define XE_GPU_FINE_GRAINED_DRAW_SCOPES 0\n',
 'rex/ui/vulkan/api.h':'#pragma once\n#define VK_NO_PROTOTYPES\n#include <vulkan/vulkan.h>\n',
 'rex/graphics/vulkan/command_processor.h': '#pragma once\n#include <rex/ui/vulkan/api.h>\nnamespace rex::ui::vulkan {struct VulkanDevice {struct Ignore {template<class... T>void operator()(T...)const{}}; struct Functions {'+fields+'}; Functions f; const Functions& functions()const{return f;}};} namespace rex::graphics::vulkan {struct VulkanCommandProcessor {rex::ui::vulkan::VulkanDevice device; const auto* GetVulkanDevice()const{return &device;}};}\n'
 }
 for name,txt in files.items():
  p=d/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(txt)
 (d/'test.cpp').write_text(r'''#include <cassert>
#include <climits>
#include <rex/graphics/vulkan/command_processor.h>
#include <rex/graphics/vulkan/deferred_command_buffer.h>
using namespace rex::graphics::vulkan;
VkRect2D last{}; unsigned begins=0,draws=0,indexed=0;
VKAPI_ATTR void VKAPI_CALL Begin(VkCommandBuffer,const VkRenderPassBeginInfo* i,VkSubpassContents){last=i->renderArea;++begins;}
VKAPI_ATTR void VKAPI_CALL Dynamic(VkCommandBuffer,const VkRenderingInfo* i){last=i->renderArea;++begins;assert(i->colorAttachmentCount==1);assert(i->pColorAttachments[0].imageView==(VkImageView)1);}
VKAPI_ATTR void VKAPI_CALL Draw(VkCommandBuffer,uint32_t,uint32_t,uint32_t,uint32_t){++draws;}
VKAPI_ATTR void VKAPI_CALL Indexed(VkCommandBuffer,uint32_t,uint32_t,uint32_t,int32_t,uint32_t){++indexed;}
int main(){
 VulkanCommandProcessor cp;cp.device.f.vkCmdBeginRenderPass=Begin;cp.device.f.vkCmdBeginRendering=Dynamic;cp.device.f.vkCmdDraw=Draw;cp.device.f.vkCmdDrawIndexed=Indexed;
 DeferredCommandBuffer b(cp,8);VkRect2D area{{0,0},{32,32}};
 assert(!b.ExpandOpenRenderArea(area));
 VkRenderPassBeginInfo rp{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};rp.renderArea={{32,32},{32,32}};
 b.CmdVkBeginRenderPass(&rp,VK_SUBPASS_CONTENTS_INLINE);
 for(int i=0;i<10000;++i)b.CmdVkDraw(3,1,0,0); // force vector reallocations
 assert(b.ExpandOpenRenderArea(area));assert(area.extent.width==64&&area.extent.height==64);
 area={{96,96},{32,32}};assert(b.ExpandOpenRenderArea(area));assert(area.extent.width==128&&area.extent.height==128);
 b.CmdVkEndRenderPass();assert(!b.ExpandOpenRenderArea(area));b.Execute(VK_NULL_HANDLE);
 assert(begins==1&&draws==10000&&last.extent.width==128);assert(!b.ExpandOpenRenderArea(area));b.Reset();
 VkRenderingAttachmentInfo att{VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO};att.imageView=(VkImageView)1;att.loadOp=VK_ATTACHMENT_LOAD_OP_LOAD;att.storeOp=VK_ATTACHMENT_STORE_OP_STORE;
 VkRenderingInfo ri{VK_STRUCTURE_TYPE_RENDERING_INFO};ri.renderArea={{32,32},{32,32}};ri.layerCount=1;ri.colorAttachmentCount=1;ri.pColorAttachments=&att;
 b.CmdVkBeginRendering(&ri);area={{0,0},{128,128}};assert(b.ExpandOpenRenderArea(area));b.CmdVkDraw(3,1,0,0);b.CmdVkEndRendering();b.Execute(VK_NULL_HANDLE);assert(begins==2&&draws==10001&&last.extent.width==128);b.Reset();
 att.loadOp=VK_ATTACHMENT_LOAD_OP_CLEAR;b.CmdVkBeginRendering(&ri);assert(!b.ExpandOpenRenderArea(area));b.Reset();
 att.loadOp=VK_ATTACHMENT_LOAD_OP_LOAD;ri.flags=VK_RENDERING_SUSPENDING_BIT;b.CmdVkBeginRendering(&ri);assert(!b.ExpandOpenRenderArea(area));b.Reset();
 VkClearValue cv{};rp.clearValueCount=1;rp.pClearValues=&cv;b.CmdVkBeginRenderPass(&rp,VK_SUBPASS_CONTENTS_INLINE);assert(!b.ExpandOpenRenderArea(area));b.Reset();
 rp.clearValueCount=0;rp.pClearValues=nullptr;b.CmdVkBeginRenderPass(&rp,VK_SUBPASS_CONTENTS_INLINE);area={{-1,0},{32,32}};assert(!b.ExpandOpenRenderArea(area));area={{INT_MAX,0},{UINT_MAX,32}};assert(!b.ExpandOpenRenderArea(area));b.Reset();assert(!b.ExpandOpenRenderArea(area));
 b.CmdVkDraw(0,1,0,0);b.CmdVkDraw(3,0,0,0);
 b.CmdVkDrawIndexed(0,1,0,0,0);b.CmdVkDrawIndexed(3,0,0,0,0);
 b.Execute(VK_NULL_HANDLE);assert(draws==10001&&indexed==0);b.Reset();
 b.CmdVkDrawIndexed(3,1,0,0,0);b.Execute(VK_NULL_HANDLE);assert(indexed==1);
}
''')
 vulkan=next(sdkroot.glob('thirdparty/**/vulkan/vulkan.h')).parents[1]
 subprocess.run([str(compiler),'/nologo','/EHsc','/std:c++17','/I'+tmp,'/I'+str(sdkroot/'include'),'/I'+str(vulkan),str(source),str(d/'test.cpp'),'/Fe:'+str(d/'test.exe')],cwd=tmp,env=env,check=True)
 subprocess.run([str(d/'test.exe')],check=True)
print('PASS production deferred replay: classic/dynamic unions, preserved draws, reallocations, closed/reset/replayed rejection, clears/flags/overflow guards (no GPU)')
