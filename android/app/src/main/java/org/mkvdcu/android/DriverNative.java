package org.mkvdcu.android;

import android.content.Context;
import android.view.Surface;

/** Loads only the runtime; no game or Vulkan instance is created here. */
final class DriverNative {
    static volatile String probePhase = "Inicialização";
    static volatile java.util.function.Consumer<String> probeProgress;
    static void onProbeProgress(String phase) {
        probePhase = phase;
        java.util.function.Consumer<String> listener = probeProgress;
        if (listener != null) listener.accept(phase);
    }
    static { System.loadLibrary("rexruntime"); }
    static native String configure(String kind, String directory, String library,
                                   String hooks, String temporary, String packageId,
                                   String cacheDirectory, String[] dependencies, boolean disableLossyAfrc, int turnipMode, boolean panvkLateStencil);
    static native String probe(Surface surface);
    static native String identity();
    static native String configureCpuLimit(int cores, boolean economy);
    static native void setDiagnosticMode(boolean enabled);
    static native void armCapture();
    static native void armRenderExtentProbe();
    static native void setRenderAreaBenchmark(boolean enabled);
    static native void setPresentPreWaitBenchmark(boolean enabled);
    static native void setPresentQueueBenchmark(boolean enabled);

    static String configure(Context context, DriverStore.Driver driver) {
        if (driver.minApi > android.os.Build.VERSION.SDK_INT || !driver.packageIssue.isEmpty()) return driver.reason();
        java.io.File temporary = new java.io.File(context.getCodeCacheDir(), "driver-loader");
        if (!temporary.isDirectory() && !temporary.mkdirs()) return "Falha ao criar diretório do loader";
        String cacheRevision = driver.kind.equals("turnip") ? "native-vulkan-3-mode" + driver.turnipMode : "native-vulkan-2";
        if (driver.kind.equals("panvk") && driver.panvkLateStencil) cacheRevision += "-late-stencil1";
        java.io.File cache = new java.io.File(context.getCodeCacheDir(), "mesa-drivers/" + driver.kind + "/" + driver.id + "/" + cacheRevision);
        if (!cache.isDirectory() && !cache.mkdirs()) return "Falha ao criar cache privado do driver";
        String[] dependencies;
        try { dependencies = driver.kind.equals("system") ? new String[0] : ElfDriver.loadOrder(driver.directory, driver.library); }
        catch (java.io.IOException error) { return error.getMessage(); }
        return configure(driver.kind, driver.directory.getAbsolutePath(), driver.library,
                context.getApplicationInfo().nativeLibraryDir, temporary.getAbsolutePath(), driver.id,
                cache.getAbsolutePath(), dependencies,
                driver.kind.equals("panvk") && driver.metadata != null &&
                    driver.metadata.optString("frontend").equalsIgnoreCase("CSF"), driver.turnipMode, driver.panvkLateStencil);
    }
}
