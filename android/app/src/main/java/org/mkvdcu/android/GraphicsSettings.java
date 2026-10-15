package org.mkvdcu.android;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.util.List;

/** Snapshot settings in the launcher: SharedPreferences is not multiprocess. */
final class GraphicsSettings {
    static final int[] FILTERS = {-1, 0, 2, 3, 4, 5};
    static final String[] FILTER_NAMES = {"Do jogo", "Desativada (menor custo)", "2×", "4×", "8×", "16×"};
    static final int[] CACHE_MB = {64, 128, 256, 384, 512, 768};
    static final int[] VIDEO_WIDTHS = {1280, 960, 854};
    static final int[] VIDEO_HEIGHTS = {720, 540, 480};
    static final String[] VIDEO_NAMES = {"1280×720 (padrão)", "960×540 (experimental)", "854×480 (experimental)"};
    static final int[] GPU_TIMING = {64, 0, 1};
    static final int[] PIPELINE_THREADS = {-1, 1, 2, 4};
    private static final String PREFIX = "graphics_";
    static final int[] SCALES = {100, 75, 50};
    static final String[] SCALE_NAMES = {"1× — cena 1040×624", "0,75× — cena 780×468 (experimental)", "0,5× — cena 520×312 (experimental)"};
    static final int[] CORE_COUNTS = {0, 1, 2, 3, 4, 6, 8, 12};
    static final int[] FPS_LIMITS = {0, 30, 45, 60, 90, 120};

    static final int PRESET_LOW = 0;
    static final int PRESET_BALANCED = 1;
    static final int PRESET_HIGH = 2;
    static final String[] PRESET_NAMES = {
        "Baixo — Mali / menor custo",
        "Padrão — equilibrado",
        "Alto — qualidade"
    };
    static final String[] PRESET_DESCRIPTIONS = {
        "Cena solicitada 0,5× (520×312), saída 720p, mip DXT +1, cache 256/512 MiB, submissões agrupadas e pedido de tela 30 Hz. Escala experimental; não limita a simulação do jogo.",
        "Cena solicitada 0,75× (780×468), saída 720p, texturas originais, cache 384/768 MiB, submissões agrupadas e pedido de tela 60 Hz. Escala experimental; não limita a simulação do jogo.",
        "Cena nativa 1040×624, saída 720p, texturas originais, filtragem 2×, cache 768/1536 MiB e pedido de tela 60 Hz. Maior uso de RAM."
    };

    static final String[] TURNIP_MODES = {"Automático (padrão do driver)", "Sysmem (experimental)", "GMEM (experimental)"};
    private final SharedPreferences prefs;
    GraphicsSettings(Context context) { prefs = context.getSharedPreferences("mkvdcu", Context.MODE_PRIVATE); }
    int activePreset() { return prefs.getInt(PREFIX + "active_preset", -1); }
    String presetName() {
        int p = activePreset();
        if (p >= 0 && p < PRESET_NAMES.length) return PRESET_NAMES[p];
        return "Personalizado";
    }
    void applyPreset(int preset) {
        if (preset < PRESET_LOW || preset > PRESET_HIGH) return;
        // Driver selection and compatibility options belong to the driver
        // manager. Applying a graphics preset must not reset those choices.
        boolean low = preset == PRESET_LOW;
        boolean high = preset == PRESET_HIGH;
        prefs.edit()
            .putInt(PREFIX + "scale_percent", low ? 50 : high ? 100 : 75)
            .putInt(PREFIX + "filter", low ? 0 : high ? 2 : -1)
            .putInt(PREFIX + "cache_mb", low ? 256 : high ? 768 : 384)
            .putInt(PREFIX + "mip_drop", low ? 1 : 0)
            .putBoolean(PREFIX + "batch", !high)
            .putInt(PREFIX + "gpu_timing", 64)
            .putBoolean(PREFIX + "gpu_stages", false)
            .putBoolean(PREFIX + "ssa", high)
            .putBoolean(PREFIX + "render_area", true)
            .putBoolean(PREFIX + "transfer_area", true)
            .putBoolean(PREFIX + "economy", false)
            .putBoolean(PREFIX + "compact_memory", false)
            .putBoolean(PREFIX + "frame_generation", false)
            .putInt(PREFIX + "fps_limit", low ? 30 : 60)
            .putInt(PREFIX + "pipeline_threads", -1)
            .putInt(PREFIX + "video_mode", 0)
            .putInt(PREFIX + "active_preset", preset)
            .apply();
    }
    private SharedPreferences.Editor editCustom() {
        return prefs.edit().putInt(PREFIX + "active_preset", -1);
    }
    int scalePercent() { return allowed(prefs.getInt(PREFIX + "scale_percent", 100), SCALES, 100); }
    void setScalePercent(int value) { editCustom().putInt(PREFIX + "scale_percent", allowed(value, SCALES, 100)).apply(); }
    int cores() { return allowed(prefs.getInt(PREFIX + "cpu_cores", 0), CORE_COUNTS, 0); }
    void setCores(int value) { editCustom().putInt(PREFIX + "cpu_cores", allowed(value, CORE_COUNTS, 0)).apply(); }
    boolean economy() { return prefs.getBoolean(PREFIX + "economy", false); }
    void setEconomy(boolean value) { editCustom().putBoolean(PREFIX + "economy", value).apply(); }
    boolean compactMemory() { return prefs.getBoolean(PREFIX + "compact_memory", false); }
    void setCompactMemory(boolean value) { editCustom().putBoolean(PREFIX + "compact_memory", value).apply(); }
    int fpsLimit() { return allowed(prefs.getInt(PREFIX + "fps_limit", 60), FPS_LIMITS, 60); }
    void setFpsLimit(int value) { editCustom().putInt(PREFIX + "fps_limit", allowed(value, FPS_LIMITS, 60)).apply(); }
    boolean frameGeneration() { return prefs.getBoolean(PREFIX + "frame_generation", false); }
    void setFrameGeneration(boolean value) { editCustom().putBoolean(PREFIX + "frame_generation", value).apply(); }
    static int cores(Intent intent) { return allowed(intent.getIntExtra(PREFIX + "cpu_cores", 0), CORE_COUNTS, 0); }
    static boolean economy(Intent intent) { return intent.getBooleanExtra(PREFIX + "economy", false); }
    static int effectiveFpsLimit(Intent intent) {
        int limit = allowed(intent.getIntExtra(PREFIX + "fps_limit", 60), FPS_LIMITS, 60);
        return intent.getBooleanExtra(PREFIX + "economy", false)
            ? (limit == 0 ? 30 : Math.min(limit, 30)) : limit;
    }
    static int displayRateRequestHz(Intent intent) {
        int source = effectiveFpsLimit(intent);
        if (source <= 0) return 0;
        return Math.min(240, source * (intent.getBooleanExtra(PREFIX + "frame_generation", false) ? 2 : 1));
    }
    static int scalePercent(Intent intent) { return allowed(intent.getIntExtra(PREFIX + "scale_percent", 100), SCALES, 100); }
    static String scaleDescription(Intent intent) { int n=scalePercent(intent);return (1040*n/100)+"×"+(624*n/100)+" (cena "+(n/100f)+"× solicitada; saída 1280×720)"; }
    int filter() { return allowed(prefs.getInt(PREFIX + "filter", -1), FILTERS, -1); }
    int cacheMb() { return allowed(prefs.getInt(PREFIX + "cache_mb", 384), CACHE_MB, 384); }
    boolean panvkLateStencil() { return prefs.getBoolean(PREFIX + "panvk_late_stencil", false); }
    void setPanvkLateStencil(boolean value) { prefs.edit().putBoolean(PREFIX + "panvk_late_stencil", value).apply(); }
    static boolean panvkLateStencil(Intent intent) { return intent.getBooleanExtra(PREFIX + "panvk_late_stencil", false); }
    boolean ssa() { return prefs.getBoolean(PREFIX + "ssa", false); }
    boolean presentQueue() { return prefs.getBoolean(PREFIX + "present_queue", false); }
    void setPresentQueue(boolean value) { prefs.edit().putBoolean(PREFIX + "present_queue", value).apply(); }
    boolean renderArea() { return prefs.getBoolean(PREFIX + "render_area", true); }
    boolean transferArea() { return prefs.getBoolean(PREFIX + "transfer_area", renderArea()); }
    boolean sustained() { return prefs.getBoolean(PREFIX + "sustained", false); }
    boolean batch() { return prefs.getBoolean(PREFIX + "batch", false); }
    boolean gpuStages() { return prefs.getBoolean(PREFIX + "gpu_stages", false); }
    int pipelineThreads() { return allowed(prefs.getInt(PREFIX + "pipeline_threads", -1), PIPELINE_THREADS, -1); }
    int turnipMode() { return allowed(prefs.getInt(PREFIX + "turnip_mode", 0), new int[]{0, 1, 2}, 0); }
    void setTurnipMode(int value) { prefs.edit().putInt(PREFIX + "turnip_mode", allowed(value, new int[]{0, 1, 2}, 0)).apply(); }
    static int turnipMode(Intent intent) { return allowed(intent.getIntExtra(PREFIX + "turnip_mode", 0), new int[]{0, 1, 2}, 0); }
    void setPipelineThreads(int value) { editCustom().putInt(PREFIX + "pipeline_threads", allowed(value, PIPELINE_THREADS, -1)).apply(); }
    void setGpuStages(boolean value) { editCustom().putBoolean(PREFIX + "gpu_stages", value).apply(); }
    int videoMode() { return allowed(prefs.getInt(PREFIX + "video_mode", 0), new int[]{0, 1, 2}, 0); }
    int gpuTiming() { return allowed(prefs.getInt(PREFIX + "gpu_timing", 64), GPU_TIMING, 64); }
    void setBatch(boolean value) { editCustom().putBoolean(PREFIX + "batch", value).apply(); }
    void setVideoMode(int value) { editCustom().putInt(PREFIX + "video_mode", allowed(value, new int[]{0, 1, 2}, 0)).apply(); }
    void setGpuTiming(int value) { editCustom().putInt(PREFIX + "gpu_timing", allowed(value, GPU_TIMING, 64)).apply(); }
    int mipDrop() { return allowed(prefs.getInt(PREFIX + "mip_drop", 0), new int[]{0, 1, 2}, 0); }
    void setFilter(int value) { editCustom().putInt(PREFIX + "filter", allowed(value, FILTERS, -1)).apply(); }
    void setCacheMb(int value) { editCustom().putInt(PREFIX + "cache_mb", allowed(value, CACHE_MB, 384)).apply(); }
    void setSsa(boolean value) { editCustom().putBoolean(PREFIX + "ssa", value).apply(); }
    void setRenderArea(boolean value) { editCustom().putBoolean(PREFIX + "render_area", value).apply(); }
    void setTransferArea(boolean value) { editCustom().putBoolean(PREFIX + "transfer_area", value).apply(); }
    void setSustained(boolean value) { editCustom().putBoolean(PREFIX + "sustained", value).apply(); }
    void setMipDrop(int value) { editCustom().putInt(PREFIX + "mip_drop", allowed(value, new int[]{0, 1, 2}, 0)).apply(); }
    void reset() {
        prefs.edit().remove(PREFIX + "filter").remove(PREFIX + "cache_mb")
            .remove(PREFIX + "ssa").remove(PREFIX + "render_area").remove(PREFIX + "transfer_area").remove(PREFIX + "present_queue").remove(PREFIX + "sustained").remove(PREFIX + "mip_drop")
            .remove(PREFIX + "batch").remove(PREFIX + "video_mode").remove(PREFIX + "gpu_timing").remove(PREFIX + "pipeline_threads")
            .remove(PREFIX + "gpu_stages").remove(PREFIX + "turnip_mode").remove(PREFIX + "panvk_late_stencil").remove(PREFIX + "scale_percent").remove(PREFIX + "cpu_cores")
            .remove(PREFIX + "economy").remove(PREFIX + "compact_memory").remove(PREFIX + "fps_limit")
            .remove(PREFIX + "frame_generation")
            .remove(PREFIX + "active_preset").apply();
    }
    void attach(Intent intent) {
        intent.putExtra(PREFIX + "filter", filter()).putExtra(PREFIX + "cache_mb", cacheMb())
            .putExtra(PREFIX + "ssa", ssa()).putExtra(PREFIX + "render_area", renderArea()).putExtra(PREFIX + "transfer_area", transferArea()).putExtra(PREFIX + "present_queue", presentQueue())
            .putExtra(PREFIX + "sustained", sustained()).putExtra(PREFIX + "mip_drop", mipDrop())
            .putExtra(PREFIX + "batch", batch()).putExtra(PREFIX + "video_mode", videoMode())
            .putExtra(PREFIX + "gpu_timing", gpuTiming()).putExtra(PREFIX + "gpu_stages", gpuStages())
            .putExtra(PREFIX + "pipeline_threads", pipelineThreads()).putExtra(PREFIX + "turnip_mode", turnipMode()).putExtra(PREFIX + "panvk_late_stencil", panvkLateStencil())
            .putExtra(PREFIX + "scale_percent", scalePercent()).putExtra(PREFIX + "cpu_cores", cores())
            .putExtra(PREFIX + "economy", economy()).putExtra(PREFIX + "compact_memory", compactMemory()).putExtra(PREFIX + "fps_limit", fpsLimit())
            .putExtra(PREFIX + "frame_generation", frameGeneration());
    }
    static boolean sustained(Intent intent) { return intent.getBooleanExtra(PREFIX + "sustained", false); }
    static void appendArguments(Intent intent, List<String> args) {
        int filter = allowed(intent.getIntExtra(PREFIX + "filter", -1), FILTERS, -1);
        int cache = allowed(intent.getIntExtra(PREFIX + "cache_mb", 384), CACHE_MB, 384);
        boolean compact = intent.getBooleanExtra(PREFIX + "compact_memory", false);
        if (compact) cache = Math.min(cache, 128);
        int limit = effectiveFpsLimit(intent);
        args.add("--vulkan_separate_present_queue=" + intent.getBooleanExtra(PREFIX + "present_queue", false));
        args.add("--gpu_frame_rate_limit=" + limit);
        args.add("--gpu_frame_generation=" + intent.getBooleanExtra(PREFIX + "frame_generation", false));
        args.add("--gpu_frame_generation_factor=2");
        args.add("--port_internal_resolution_percent=100");
        args.add("--port_scene_resolution_percent=" + scalePercent(intent));
        args.add("--texture_cache_memory_limit_soft_lifetime=" + (compact ? 5 : 30));
        args.add("--anisotropic_override=" + filter);
        args.add("--texture_cache_memory_limit_soft=" + cache);
        args.add("--texture_cache_memory_limit_hard=" + (cache * 2));
        args.add("--vulkan_texture_mip_drop=" + allowed(intent.getIntExtra(PREFIX + "mip_drop", 0), new int[]{0, 1, 2}, 0));
        args.add("--vulkan_spirv_optimize=" + ssa(intent));
        args.add("--vulkan_scissor_render_area=" + area(intent));
        args.add("--vulkan_transfer_render_area=" + intent.getBooleanExtra(PREFIX + "transfer_area", true));
        args.add("--vulkan_submit_on_primary_buffer_end=" + !intent.getBooleanExtra(PREFIX + "batch", true));
        args.add("--occlusion_query_enable=false");
        args.add("--vulkan_gpu_timing_interval=" + allowed(intent.getIntExtra(PREFIX + "gpu_timing", 0), GPU_TIMING, 0));
        args.add("--vulkan_gpu_stage_timing_interval=" + (intent.getBooleanExtra(PREFIX + "gpu_stages", false) ? 64 : 0));
        args.add("--vulkan_pipeline_creation_threads=" + effectivePipelineThreads(intent));
        args.add("--video_mode_width=1280");
        args.add("--video_mode_height=720");
    }
    private static int effectivePipelineThreads(Intent intent) {
        int workers = allowed(intent.getIntExtra(PREFIX + "pipeline_threads", -1), PIPELINE_THREADS, -1);
        // Mesa compilers can have large per-worker working sets. Android auto
        // bounds both startup and later pipeline creation instead of using 6/8
        // workers on an eight-core phone. Explicit choices remain available.
        if (workers < 0) workers = 2;
        if (cores(intent) > 0) workers = Math.min(workers, Math.max(1, cores(intent)-1));
        return workers;
    }
    static boolean ssa(Intent intent) {
        return BuildConfig.DEBUG && intent.hasExtra("benchmark_spirv_optimize")
            ? intent.getBooleanExtra("benchmark_spirv_optimize", false)
            : intent.getBooleanExtra(PREFIX + "ssa", false);
    }
    static boolean area(Intent intent) {
        return BuildConfig.DEBUG && intent.hasExtra("benchmark_scissor_area")
            ? intent.getBooleanExtra("benchmark_scissor_area", false)
            : intent.getBooleanExtra(PREFIX + "render_area", true);
    }
    static String summary(Intent intent) {
        return "separate_present_queue=" + intent.getBooleanExtra(PREFIX + "present_queue", false) + "; requested_internal=" + scaleDescription(intent) + "; cpu_cores=" + cores(intent) + "; economy=" + economy(intent) +
            "; compact_memory=" + intent.getBooleanExtra(PREFIX + "compact_memory", false) + "; display_rate_request_hz=" + effectiveFpsLimit(intent) + "; anisotropic=" + allowed(intent.getIntExtra(PREFIX + "filter", -1), FILTERS, -1) +
            "; texture_cache_soft_mb=" + allowed(intent.getIntExtra(PREFIX + "cache_mb", 384), CACHE_MB, 384) +
            "; spirv_ssa=" + ssa(intent) + "; scissor_area=" + area(intent) + "; transfer_area=" + intent.getBooleanExtra(PREFIX + "transfer_area", true) +
            "; sustained_requested=" + sustained(intent) + "; texture_mip_drop=" +
            allowed(intent.getIntExtra(PREFIX + "mip_drop", 0), new int[]{0, 1, 2}, 0) +
            "; guest_video_mode=" + allowed(intent.getIntExtra(PREFIX + "video_mode", 0), new int[]{0, 1, 2}, 0) +
            "; batch_submissions=" + intent.getBooleanExtra(PREFIX + "batch", false) +
            "; gpu_timing_interval=" + allowed(intent.getIntExtra(PREFIX + "gpu_timing", 64), GPU_TIMING, 64) +
            "; gpu_stages=" + intent.getBooleanExtra(PREFIX + "gpu_stages", false) +
            "; pipeline_creation_threads_requested=" + allowed(intent.getIntExtra(PREFIX + "pipeline_threads", -1), PIPELINE_THREADS, -1) +
            "; pipeline_creation_threads_effective=" + effectivePipelineThreads(intent) +
            "; frame_generation_2x=" + intent.getBooleanExtra(PREFIX + "frame_generation", false) +
            "; panvk_late_stencil=" + panvkLateStencil(intent) + "; turnip_mode=" + turnipMode(intent) + "; render_area_revision=deferred_union_1";
    }
    private static int allowed(int value, int[] choices, int fallback) {
        for (int choice : choices) if (value == choice) return value;
        return fallback;
    }
}
