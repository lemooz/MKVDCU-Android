/**
 * MKVDCU Android - JNI surface for the native ReXGlue runtime.
 *
 * This file is the boundary between the Kotlin/Java launcher and the native
 * game runtime. The runtime lives in libmkvdcu.so (this target), which links
 * the ReXGlue runtime, the Vulkan Xenos backend, input and audio. The
 * precompiled game code is compiled into the same library by the build
 * pipeline once generated from a legally owned default.xex.
 */

#include <jni.h>

#include <sstream>
#include <string>

#include <rex/platform.h>
#include <rex/system.h>

#if REX_PLATFORM_ANDROID
#include <rex/ui/vulkan/provider.h>
#endif

extern "C" JNIEXPORT jboolean JNICALL
Java_org_mkvdcu_android_NativeRuntime_nativeInit(JNIEnv*, jclass) {
#if REX_PLATFORM_ANDROID
  return rex::InitializeAndroidSystemForApplicationContext() ? JNI_TRUE : JNI_FALSE;
#else
  return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_mkvdcu_android_NativeRuntime_nativeProbe(JNIEnv* env, jclass) {
  std::ostringstream os;
  os << "MKVDCU native runtime (arm64-v8a)";
#if REX_PLATFORM_ANDROID
  os << "\nAndroid API: " << rex::GetAndroidApiLevel();

  auto provider = rex::ui::vulkan::VulkanProvider::Create(false, false);
  if (!provider) {
    os << "\nVulkan: indisponível";
  } else {
    os << "\nVulkan: OK";
    if (auto* device = provider->vulkan_device()) {
      os << "\nGPU: " << device->properties().deviceName;
    }
  }
#else
  os << "\n(compilado sem Android)";
#endif
  return env->NewStringUTF(os.str().c_str());
}
