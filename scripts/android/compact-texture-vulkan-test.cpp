// Local Vulkan compute regression. No Android device, WSI, or FPS claim.
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <Windows.h>
#include <algorithm>
#include <array>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <random>
#include <vector>
#include "references/rexglue-sdk/src/graphics/shaders/vulkan_spirv/texture_load_dxt1_rgba8_cs.h"
#include "references/rexglue-sdk/src/graphics/shaders/vulkan_spirv/texture_load_dxt1_rgb5a1_cs.h"

#define FUNCTIONS(X) \
 X(CreateInstance) X(EnumeratePhysicalDevices) X(GetPhysicalDeviceQueueFamilyProperties) \
 X(GetPhysicalDeviceMemoryProperties) X(GetPhysicalDeviceProperties) X(CreateDevice) \
 X(GetDeviceQueue) X(CreateBuffer) X(GetBufferMemoryRequirements) X(AllocateMemory) \
 X(BindBufferMemory) X(MapMemory) X(CreateDescriptorSetLayout) X(CreateDescriptorPool) \
 X(AllocateDescriptorSets) X(UpdateDescriptorSets) X(CreateShaderModule) X(CreatePipelineLayout) \
 X(CreateComputePipelines) X(CreateCommandPool) X(AllocateCommandBuffers) X(BeginCommandBuffer) \
 X(EndCommandBuffer) X(CmdPipelineBarrier) X(CmdBindPipeline) X(CmdBindDescriptorSets) \
 X(CmdPushConstants) X(CmdDispatch) X(QueueSubmit) X(CreateFence) X(WaitForFences) \
 X(ResetFences) X(ResetCommandBuffer) X(DeviceWaitIdle) X(DestroyDevice) X(DestroyInstance)
#define DECL(name) PFN_vk##name vk##name;
FUNCTIONS(DECL)
#undef DECL
void check(VkResult result) { if (result != VK_SUCCESS) { std::printf("Vulkan failure: %d\n", result); std::exit(1); } }

int main() {
  auto loader = LoadLibraryA("vulkan-1.dll");
  if (!loader) { std::puts("FAIL: local Vulkan loader unavailable"); return 1; }
  auto get = reinterpret_cast<PFN_vkGetInstanceProcAddr>(GetProcAddress(loader, "vkGetInstanceProcAddr"));
  vkCreateInstance = reinterpret_cast<PFN_vkCreateInstance>(get(nullptr, "vkCreateInstance"));
  VkInstance instance{};
  VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
  check(vkCreateInstance(&ici, nullptr, &instance));
#define LOAD(name) vk##name = reinterpret_cast<PFN_vk##name>(get(instance, "vk" #name)); if (!vk##name) return 2;
  FUNCTIONS(LOAD)
#undef LOAD
  uint32_t count = 0; check(vkEnumeratePhysicalDevices(instance, &count, nullptr));
  if (!count) { std::puts("FAIL: no local Vulkan physical device"); return 1; }
  std::vector<VkPhysicalDevice> devices(count); check(vkEnumeratePhysicalDevices(instance, &count, devices.data()));
  const auto physical = devices.front();
  VkPhysicalDeviceProperties props{}; vkGetPhysicalDeviceProperties(physical, &props);
  std::printf("Local compute device: %s (not a Mali validation)\n", props.deviceName);
  vkGetPhysicalDeviceQueueFamilyProperties(physical, &count, nullptr);
  std::vector<VkQueueFamilyProperties> families(count); vkGetPhysicalDeviceQueueFamilyProperties(physical, &count, families.data());
  uint32_t family = 0;
  while (family < count && !(families[family].queueFlags & VK_QUEUE_COMPUTE_BIT)) ++family;
  if (family == count) return 1;
  float priority = 1;
  VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO}; qci.queueFamilyIndex = family; qci.queueCount = 1; qci.pQueuePriorities = &priority;
  VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO}; dci.queueCreateInfoCount = 1; dci.pQueueCreateInfos = &qci;
  VkDevice device{}; check(vkCreateDevice(physical, &dci, nullptr, &device));
  VkQueue queue{}; vkGetDeviceQueue(device, family, 0, &queue);
  VkPhysicalDeviceMemoryProperties memory{}; vkGetPhysicalDeviceMemoryProperties(physical, &memory);
  struct Buffer { VkBuffer buffer; VkDeviceMemory memory; uint8_t* data; };
  auto buffer = [&](size_t size) {
    Buffer b{}; VkBufferCreateInfo ci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO}; ci.size = size; ci.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    check(vkCreateBuffer(device, &ci, nullptr, &b.buffer));
    VkMemoryRequirements req{}; vkGetBufferMemoryRequirements(device, b.buffer, &req);
    uint32_t type = 0; const auto flags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    while (type < memory.memoryTypeCount && (!(req.memoryTypeBits & (1u << type)) || (memory.memoryTypes[type].propertyFlags & flags) != flags)) ++type;
    if (type == memory.memoryTypeCount) { std::puts("FAIL: host-coherent fixture memory unavailable"); std::exit(1); }
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; ai.allocationSize = req.size; ai.memoryTypeIndex = type;
    check(vkAllocateMemory(device, &ai, nullptr, &b.memory)); check(vkBindBufferMemory(device, b.buffer, b.memory, 0));
    void* mapped = nullptr; check(vkMapMemory(device, b.memory, 0, VK_WHOLE_SIZE, 0, &mapped)); b.data = static_cast<uint8_t*>(mapped); return b;
  };
  auto input = buffer(131072); auto output = buffer(16384);
  std::mt19937 random(360);
  for (size_t offset = 0; offset < 131072; offset += 4) { const auto value = random(); std::memcpy(input.data + offset, &value, 4); }
  // Deterministic opaque/transparent endpoints in addition to random blocks.
  const uint32_t known[] = {0x07e0f800, 0xe4e4e4e4, 0xf80007e0, 0xffffffff, 0, 0xffffffff, 0xffffffff, 0};
  std::memcpy(input.data, known, sizeof(known));
  VkDescriptorSetLayoutBinding binding{}; binding.binding = 0; binding.descriptorCount = 1; binding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER; binding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
  VkDescriptorSetLayoutCreateInfo lci{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO}; lci.bindingCount = 1; lci.pBindings = &binding;
  VkDescriptorSetLayout layout{}; check(vkCreateDescriptorSetLayout(device, &lci, nullptr, &layout));
  VkDescriptorPoolSize pool_size{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 2}; VkDescriptorPoolCreateInfo pci{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO}; pci.maxSets = 2; pci.poolSizeCount = 1; pci.pPoolSizes = &pool_size;
  VkDescriptorPool pool{}; check(vkCreateDescriptorPool(device, &pci, nullptr, &pool));
  VkDescriptorSetLayout layouts[]{layout, layout}; VkDescriptorSet sets[2]{};
  VkDescriptorSetAllocateInfo sai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO}; sai.descriptorPool = pool; sai.descriptorSetCount = 2; sai.pSetLayouts = layouts;
  check(vkAllocateDescriptorSets(device, &sai, sets));
  VkDescriptorBufferInfo buffers[]{{output.buffer, 0, 16384}, {input.buffer, 0, 131072}};
  for (int i = 0; i < 2; ++i) {
    VkWriteDescriptorSet write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET}; write.dstSet = sets[i]; write.descriptorCount = 1; write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER; write.pBufferInfo = &buffers[i];
    vkUpdateDescriptorSets(device, 1, &write, 0, nullptr);
  }
  VkPushConstantRange range{VK_SHADER_STAGE_COMPUTE_BIT, 0, 40}; VkPipelineLayoutCreateInfo plci{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO}; plci.setLayoutCount = 2; plci.pSetLayouts = layouts; plci.pushConstantRangeCount = 1; plci.pPushConstantRanges = &range;
  VkPipelineLayout pipeline_layout{}; check(vkCreatePipelineLayout(device, &plci, nullptr, &pipeline_layout));
  auto pipeline = [&](const uint32_t* words, size_t bytes) {
    VkShaderModuleCreateInfo smci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO}; smci.codeSize = bytes; smci.pCode = words;
    VkShaderModule shader{}; check(vkCreateShaderModule(device, &smci, nullptr, &shader));
    VkComputePipelineCreateInfo ci{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO}; ci.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO; ci.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT; ci.stage.module = shader; ci.stage.pName = "main"; ci.layout = pipeline_layout;
    VkPipeline p{}; check(vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &ci, nullptr, &p)); return p;
  };
  const VkPipeline pipelines[]{pipeline(texture_load_dxt1_rgba8_cs, sizeof(texture_load_dxt1_rgba8_cs)), pipeline(texture_load_dxt1_rgb5a1_cs, sizeof(texture_load_dxt1_rgb5a1_cs))};
  VkCommandPoolCreateInfo cpci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO}; cpci.queueFamilyIndex = family; cpci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
  VkCommandPool command_pool{}; check(vkCreateCommandPool(device, &cpci, nullptr, &command_pool));
  VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO}; cai.commandPool = command_pool; cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; cai.commandBufferCount = 1;
  VkCommandBuffer command{}; check(vkAllocateCommandBuffers(device, &cai, &command));
  VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO}; VkFence fence{}; check(vkCreateFence(device, &fci, nullptr, &fence));
  uint32_t cases = 0; size_t compared = 0;
  for (uint32_t tiled = 0; tiled < 2; ++tiled) for (uint32_t endian = 0; endian < 4; ++endian)
  for (uint32_t width : {1u, 2u, 3u, 4u, 5u, 8u}) for (uint32_t height : {1u, 2u}) for (uint32_t depth : {1u, 2u})
  for (uint32_t offsets : {0u, 1u}) for (uint32_t partial : {0u, 1u}) {
    std::vector<uint32_t> reference;
    const uint32_t host_offset = offsets * 16;
    const uint32_t host_row_pixels = ((width + 3) & ~3u) * 4;
    const uint32_t height_texels = height * 4 - partial;
    const size_t pixels = host_row_pixels * height_texels * depth;
    for (uint32_t mode = 0; mode < 2; ++mode) {
      const uint32_t bytes = mode ? 2 : 4;
      std::memset(output.data, 0xcd, 16384);
      uint32_t constants[]{tiled | (endian << 2) | (depth > 1 ? 2u : 0u), offsets * 128, tiled ? 32u : 256u, 32, width, height, depth, host_offset, host_row_pixels * bytes, height_texels};
      check(vkResetCommandBuffer(command, 0)); VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO}; bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT; check(vkBeginCommandBuffer(command, &bi));
      VkMemoryBarrier host{VK_STRUCTURE_TYPE_MEMORY_BARRIER}; host.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT; host.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
      vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_HOST_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &host, 0, nullptr, 0, nullptr);
      vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipelines[mode]); vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_layout, 0, 2, sets, 0, nullptr);
      vkCmdPushConstants(command, pipeline_layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(constants), constants); vkCmdDispatch(command, 1, 1, depth);
      VkMemoryBarrier read{VK_STRUCTURE_TYPE_MEMORY_BARRIER}; read.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT; read.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
      vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &read, 0, nullptr, 0, nullptr);
      check(vkEndCommandBuffer(command)); VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO}; si.commandBufferCount = 1; si.pCommandBuffers = &command;
      check(vkQueueSubmit(queue, 1, &si, fence)); check(vkWaitForFences(device, 1, &fence, VK_TRUE, UINT64_MAX)); check(vkResetFences(device, 1, &fence));
      for (size_t i = 0; i < 16384; ++i) if ((i < host_offset || i >= host_offset + pixels * bytes) && output.data[i] != 0xcd) { std::printf("FAIL: write beyond output bounds (mode %u)\n", mode); return 1; }
      if (!mode) {
        reference.resize(pixels); std::memcpy(reference.data(), output.data + host_offset, pixels * 4);
      } else {
        for (size_t i = 0; i < pixels; ++i) {
          const auto rgba = reference[i]; const auto r = ((rgba & 255) * 31 + 127) / 255;
          const auto g = (((rgba >> 8) & 255) * 31 + 127) / 255, b = (((rgba >> 16) & 255) * 31 + 127) / 255;
          const uint16_t expected = uint16_t((r << 11) | (g << 6) | (b << 1) | (rgba >> 31));
          uint16_t actual; std::memcpy(&actual, output.data + host_offset + i * 2, 2);
          if (actual != expected) { std::printf("FAIL: tile=%u endian=%u w=%u h=%u d=%u pixel=%zu expected=%04x actual=%04x\n", tiled, endian, width, height, depth, i, expected, actual); return 1; }
          ++compared;
        }
      }
    }
    ++cases;
  }
  check(vkDeviceWaitIdle(device));
  // Test-only fixture exits with its private device allocations (no game resources).
  std::printf("PASS: %u Vulkan dispatch pairs, %zu texels match RGBA8 -> RGB5A1; 2D/3D, tiled/linear, endian 0..3, alpha and bounds\n", cases, compared);
  return 0;
}
