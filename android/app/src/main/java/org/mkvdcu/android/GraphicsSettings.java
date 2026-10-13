package org.mkvdcu.android;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.util.List;

/** Snapshot settings in the launcher: SharedPreferences is not multiprocess. */
final class GraphicsSettings {
    static final int[] FILTERS = {-1, 0, 2, 3, 4, 5};
    static final String[] FILTER_NAMES = {"Do jogo", "Desativada (menor custo)", "2×", "4×", "8×", "16×"};
    static final int[] CACHE_MB = {128, 256, 384, 512};
    static final int[] VIDEO_WIDTHS = {1280, 960, 854};
    static final int[] VIDEO_HEIGHTS = {720, 540, 480};
    static final String[] VIDEO_NAMES = {"1280×720 (padrão)", "960×540 (experimental)", "854×480 (experimental)"};
    static final int[] GPU_TIMING = {64, 0, 1};
    static final int[] PIPELINE_THREADS = {-1, 1, 2, 4};
    private static final String PREFIX = "graphics_";
    static final String[] TURNIP_MODES = {"Automático (padrão do driver)", "Sysmem (experimental)", "GMEM (experimental)"};
    private final SharedPreferences prefs;
    GraphicsSettings(Context context) { prefs = context.getSharedPreferences("mkvdcu", Context.MODE_PRIVATE); }
    int filter() { return allowed(prefs.getInt(PREFIX + "filter", -1), FILTERS, -1); }
    int cacheMb() { return allowed(prefs.getInt(PREFIX + "cache_mb", 384), CACHE_MB, 384); }
    boolean ssa() { return prefs.getBoolean(PREFIX + "ssa", false); }
    boolean renderArea() { return prefs.getBoolean(PREFIX + "render_area", false); }
    boolean sustained() { return prefs.getBoolean(PREFIX + "sustained", false); }
    boolean batch() { return prefs.getBoolean(PREFIX + "batch", false); }
    boolean gpuStages() { return prefs.getBoolean(PREFIX + "gpu_stages", false); }
    int pipelineThreads() { return allowed(prefs.getInt(PREFIX + "pipeline_threads", -1), PIPELINE_THREADS, -1); }
    int turnipMode() { return allowed(prefs.getInt(PREFIX + "turnip_mode", 0), new int[]{0, 1, 2}, 0); }
    void setTurnipMode(int value) { prefs.edit().putInt(PREFIX + "turnip_mode", allowed(value, new int[]{0, 1, 2}, 0)).apply(); }
    static int turnipMode(Intent intent) { return allowed(intent.getIntExtra(PREFIX + "turnip_mode", 0), new int[]{0, 1, 2}, 0); }
    void setPipelineThreads(int value) { prefs.edit().putInt(PREFIX + "pipeline_threads", allowed(value, PIPELINE_THREADS, -1)).apply(); }
    void setGpuStages(boolean value) { prefs.edit().putBoolean(PREFIX + "gpu_stages", value).apply(); }
    int videoMode() { return allowed(prefs.getInt(PREFIX + "video_mode", 0), new int[]{0, 1, 2}, 0); }
    int gpuTiming() { return allowed(prefs.getInt(PREFIX + "gpu_timing", 64), GPU_TIMING, 64); }
    void setBatch(boolean value) { prefs.edit().putBoolean(PREFIX + "batch", value).apply(); }
    void setVideoMode(int value) { prefs.edit().putInt(PREFIX + "video_mode", allowed(value, new int[]{0, 1, 2}, 0)).apply(); }
    void setGpuTiming(int value) { prefs.edit().putInt(PREFIX + "gpu_timing", allowed(value, GPU_TIMING, 64)).apply(); }
    int mipDrop() { return allowed(prefs.getInt(PREFIX + "mip_drop", 0), new int[]{0, 1, 2}, 0); }
    void setFilter(int value) { prefs.edit().putInt(PREFIX + "filter", allowed(value, FILTERS, -1)).apply(); }
    void setCacheMb(int value) { prefs.edit().putInt(PREFIX + "cache_mb", allowed(value, CACHE_MB, 384)).apply(); }
    void setSsa(boolean value) { prefs.edit().putBoolean(PREFIX + "ssa", value).apply(); }
    void setRenderArea(boolean value) { prefs.edit().putBoolean(PREFIX + "render_area", value).apply(); }
    void setSustained(boolean value) { prefs.edit().putBoolean(PREFIX + "sustained", value).apply(); }
    void setMipDrop(int value) { prefs.edit().putInt(PREFIX + "mip_drop", allowed(value, new int[]{0, 1, 2}, 0)).apply(); }
    void reset() {
        prefs.edit().remove(PREFIX + "filter").remove(PREFIX + "cache_mb")
            .remove(PREFIX + "ssa").remove(PREFIX + "render_area").remove(PREFIX + "sustained").remove(PREFIX + "mip_drop")
            .remove(PREFIX + "batch").remove(PREFIX + "video_mode").remove(PREFIX + "gpu_timing").remove(PREFIX + "pipeline_threads")
            .remove(PREFIX + "gpu_stages").remove(PREFIX + "turnip_mode").apply();
    }
    void attach(Intent intent) {
        intent.putExtra(PREFIX + "filter", filter()).putExtra(PREFIX + "cache_mb", cacheMb())
            .putExtra(PREFIX + "ssa", ssa()).putExtra(PREFIX + "render_area", renderArea())
            .putExtra(PREFIX + "sustained", sustained()).putExtra(PREFIX + "mip_drop", mipDrop())
            .putExtra(PREFIX + "batch", batch()).putExtra(PREFIX + "video_mode", videoMode())
            .putExtra(PREFIX + "gpu_timing", gpuTiming()).putExtra(PREFIX + "gpu_stages", gpuStages())
            .putExtra(PREFIX + "pipeline_threads", pipelineThreads()).putExtra(PREFIX + "turnip_mode", turnipMode());
    }
    static boolean sustained(Intent intent) { return intent.getBooleanExtra(PREFIX + "sustained", false); }
    static void appendArguments(Intent intent, List<String> args) {
        int filter = allowed(intent.getIntExtra(PREFIX + "filter", -1), FILTERS, -1);
        int cache = allowed(intent.getIntExtra(PREFIX + "cache_mb", 384), CACHE_MB, 384);
        args.add("--anisotropic_override=" + filter);
        args.add("--texture_cache_memory_limit_soft=" + cache);
        args.add("--texture_cache_memory_limit_hard=" + (cache * 2));
        args.add("--vulkan_texture_mip_drop=" + allowed(intent.getIntExtra(PREFIX + "mip_drop", 0), new int[]{0, 1, 2}, 0));
        args.add("--vulkan_spirv_optimize=" + ssa(intent));
        args.add("--vulkan_scissor_render_area=" + area(intent));
        args.add("--vulkan_submit_on_primary_buffer_end=" + !intent.getBooleanExtra(PREFIX + "batch", false));
        args.add("--vulkan_gpu_timing_interval=" + allowed(intent.getIntExtra(PREFIX + "gpu_timing", 64), GPU_TIMING, 64));
        args.add("--vulkan_gpu_stage_timing_interval=" + (intent.getBooleanExtra(PREFIX + "gpu_stages", false) ? 64 : 0));
        args.add("--vulkan_pipeline_creation_threads=" + allowed(intent.getIntExtra(PREFIX + "pipeline_threads", -1), PIPELINE_THREADS, -1));
        int mode = allowed(intent.getIntExtra(PREFIX + "video_mode", 0), new int[]{0, 1, 2}, 0);
        args.add("--video_mode_width=" + VIDEO_WIDTHS[mode]);
        args.add("--video_mode_height=" + VIDEO_HEIGHTS[mode]);
    }
    static boolean ssa(Intent intent) {
        return BuildConfig.DEBUG && intent.hasExtra("benchmark_spirv_optimize")
            ? intent.getBooleanExtra("benchmark_spirv_optimize", false)
            : intent.getBooleanExtra(PREFIX + "ssa", false);
    }
    static boolean area(Intent intent) {
        return BuildConfig.DEBUG && intent.hasExtra("benchmark_scissor_area")
            ? intent.getBooleanExtra("benchmark_scissor_area", false)
            : intent.getBooleanExtra(PREFIX + "render_area", false);
    }
    static String summary(Intent intent) {
        return "scale=1x; anisotropic=" + allowed(intent.getIntExtra(PREFIX + "filter", -1), FILTERS, -1) +
            "; texture_cache_soft_mb=" + allowed(intent.getIntExtra(PREFIX + "cache_mb", 384), CACHE_MB, 384) +
            "; spirv_ssa=" + ssa(intent) + "; scissor_area=" + area(intent) +
            "; sustained_requested=" + sustained(intent) + "; texture_mip_drop=" +
            allowed(intent.getIntExtra(PREFIX + "mip_drop", 0), new int[]{0, 1, 2}, 0) +
            "; guest_video_mode=" + allowed(intent.getIntExtra(PREFIX + "video_mode", 0), new int[]{0, 1, 2}, 0) +
            "; batch_submissions=" + intent.getBooleanExtra(PREFIX + "batch", false) +
            "; gpu_timing_interval=" + allowed(intent.getIntExtra(PREFIX + "gpu_timing", 64), GPU_TIMING, 64) +
            "; gpu_stages=" + intent.getBooleanExtra(PREFIX + "gpu_stages", false) +
            "; pipeline_creation_threads=" + allowed(intent.getIntExtra(PREFIX + "pipeline_threads", -1), PIPELINE_THREADS, -1) +
            "; turnip_mode=" + turnipMode(intent) + "; render_area_revision=deferred_union_1";
    }
    private static int allowed(int value, int[] choices, int fallback) {
        for (int choice : choices) if (value == choice) return value;
        return fallback;
    }
}
