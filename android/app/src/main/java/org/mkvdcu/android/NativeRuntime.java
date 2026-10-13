package org.mkvdcu.android;

/**
 * Bridge to the native MKVDCU game runtime (libmkvdcu.so).
 *
 * The library contains the precompiled ARM64 game code (produced offline by
 * rexglue codegen from a legally owned default.xex) plus the ReXGlue runtime,
 * the Vulkan Xenos backend, the Android platform layer, input and audio.
 */
public final class NativeRuntime {

    private static volatile Boolean available;

    static {
        try {
            System.loadLibrary("rexruntime");
            try {
                System.loadLibrary("rexgpu-xenos");
            } catch (Throwable ignored) {
            }
            System.loadLibrary("mkvdcu");
            available = Boolean.TRUE;
        } catch (Throwable t) {
            available = Boolean.FALSE;
        }
    }

    private NativeRuntime() {
    }

    /** True when libmkvdcu.so was packaged and loaded successfully. */
    public static boolean isAvailable() {
        return Boolean.TRUE.equals(available);
    }

    /** Human-readable reason the runtime is unavailable, or empty when available. */
    public static String unavailableReason() {
        return isAvailable() ? "" : "libmkvdcu.so não incluído nesta build";
    }

    /** Initializes the native Android system layer. Returns "" on success. */
    public static String init() {
        if (!isAvailable()) {
            return unavailableReason();
        }
        try {
            return nativeInit() ? "" : "Falha ao inicializar o sistema Android nativo";
        } catch (Throwable t) {
            return "Erro nativo: " + t.getMessage();
        }
    }

    /** Probes the native runtime (ABI, Android level, Vulkan device). */
    public static String probe() {
        if (!isAvailable()) {
            return unavailableReason();
        }
        try {
            nativeInit();
            return nativeProbe();
        } catch (Throwable t) {
            return "Erro na sonda nativa: " + t.getMessage();
        }
    }

    private static native boolean nativeInit();

    private static native String nativeProbe();
}
