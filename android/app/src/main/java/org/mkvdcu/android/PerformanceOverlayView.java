package org.mkvdcu.android;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.text.Html;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * Unified MangoHUD / RivaTuner (RTSS) style performance overlay.
 *
 * Displays real-time metrics:
 * - FPS & Frametime (guest frames, avg ms)
 * - % GPU (via readable sysfs only; unavailable otherwise) + Driver name
 * - Process CPU share of logical cores + effective HUD-thread affinity
 * - RAM (System used/total + Port RSS)
 * - Battery Temp & Level (% + charging state)
 * - CPU / Thermal status & Temp
 * - Shader translation and pipeline creation operations (not live object counts)
 *
 * Supports drag-to-reposition, compact mode toggle, and complete disabling (View.GONE + stop polling).
 */
public final class PerformanceOverlayView extends LinearLayout {

    public interface SnapshotProvider {
        String getPerformanceSnapshot();
        String getHealthSnapshot();
        String getDriverName();
        default long getPresentCount() { return -1; }
    }

    private SnapshotProvider provider;
    private long lastPresentCount = -1, lastPresentAt;
    private volatile int presentationLimit;
    public void setPresentationLimit(int fps) { presentationLimit = fps; }
    private Runnable onCloseCallback;

    private TextView titleView;
    private TextView btnCompact;
    private TextView btnClose;
    private TextView contentTextView;

    private final Handler updateHandler = new Handler(Looper.getMainLooper());
    private volatile boolean isRunning = false;
    private volatile int generation;
    private java.util.concurrent.ScheduledExecutorService sampler;
    private double lastGpuCount, lastGpuTotalMs;
    private float gpuSubmissionMs = -1f;
    private long gpuSampleAt;
    private String outputSize = "--";
    private volatile String cpuPolicyStatus = "CPU: política ainda não informada";
    private boolean isCompactMode = false;

    // CPU Tracking
    private long lastCpuRealtime = 0;
    private long lastCpuTime = 0;
    private float cachedCpuUsage = 0f;

    // Thermal caching
    private float cachedCpuTemp = 0f;
    private long lastThermalCheck = 0;

    // Drag-to-reposition
    private float dX, dY;
    private boolean isDragging = false;
    private float touchStartX, touchStartY;

    public PerformanceOverlayView(Context context) {
        super(context);
        init(context);
    }

    public PerformanceOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        setOrientation(VERTICAL);
        setBackgroundResource(R.drawable.bg_mangohud_panel);
        setClickable(true);
        setFocusable(false);

        // Header Layout
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, 4);

        titleView = new TextView(context);
        titleView.setText("MKVDCU // MANGO-HUD");
        titleView.setTextColor(0xFF94A3B8);
        titleView.setTextSize(10f);
        titleView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        LayoutParams titleParams = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        header.addView(titleView, titleParams);

        // Compact toggle button [-]
        btnCompact = new TextView(context);
        btnCompact.setText(" [─] ");
        btnCompact.setTextColor(0xFF38BDF8);
        btnCompact.setTextSize(11f);
        btnCompact.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        btnCompact.setPadding(8, 2, 8, 2);
        btnCompact.setOnClickListener(v -> toggleCompactMode());
        header.addView(btnCompact);

        // Close button [✕]
        btnClose = new TextView(context);
        btnClose.setText(" [✕] ");
        btnClose.setTextColor(0xFFF87171);
        btnClose.setTextSize(11f);
        btnClose.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        btnClose.setPadding(8, 2, 8, 2);
        btnClose.setOnClickListener(v -> {
            if (onCloseCallback != null) {
                onCloseCallback.run();
            } else {
                stop();
                setVisibility(GONE);
            }
        });
        header.addView(btnClose);

        addView(header);

        // Content TextView with Monospace font for exact tabular alignment
        contentTextView = new TextView(context);
        contentTextView.setTextColor(Color.WHITE);
        contentTextView.setTextSize(11f);
        contentTextView.setMaxWidth((int)Math.min(420*getResources().getDisplayMetrics().density, getResources().getDisplayMetrics().widthPixels*0.6f));
        contentTextView.setTypeface(Typeface.MONOSPACE);
        contentTextView.setLineSpacing(2f, 1f);
        addView(contentTextView);

        setupDragHandling();
    }

    private void setupDragHandling() {
        setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchStartX = event.getRawX();
                    touchStartY = event.getRawY();
                    dX = view.getX() - event.getRawX();
                    dY = view.getY() - event.getRawY();
                    isDragging = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float diffX = Math.abs(event.getRawX() - touchStartX);
                    float diffY = Math.abs(event.getRawY() - touchStartY);
                    if (diffX > 10 || diffY > 10) {
                        isDragging = true;
                    }
                    if (isDragging) {
                        View parent = view.getParent() instanceof View ? (View)view.getParent() : null;
                        float maxX = parent != null ? Math.max(0,parent.getWidth()-view.getWidth()) : Float.MAX_VALUE;
                        float maxY = parent != null ? Math.max(0,parent.getHeight()-view.getHeight()) : Float.MAX_VALUE;
                        view.setX(Math.max(0,Math.min(maxX,event.getRawX()+dX)));
                        view.setY(Math.max(0,Math.min(maxY,event.getRawY()+dY)));
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (!isDragging) {
                        view.performClick();
                    }
                    return true;
            }
            return false;
        });
    }

    @Override
    public boolean performClick() {
        super.performClick();
        toggleCompactMode();
        return true;
    }

    public void setSnapshotProvider(SnapshotProvider provider) {
        this.provider = provider;
    }

    public void setCpuPolicyStatus(String status) {
        cpuPolicyStatus = status == null || status.isEmpty() ? "CPU: política n/d" : status;
    }

    public void setOnCloseCallback(Runnable onCloseCallback) {
        this.onCloseCallback = onCloseCallback;
    }

    public void toggleCompactMode() {
        isCompactMode = !isCompactMode;
        btnCompact.setText(isCompactMode ? " [+] " : " [─] ");
        titleView.setText(isCompactMode ? "HUD" : "MKVDCU // MANGO-HUD");
        // The next sampler tick redraws using the selected mode.
    }

    public void start() {
        if (isRunning) return;
        isRunning = true;
        setVisibility(VISIBLE);
        final int token = ++generation;
        lastPresentCount = -1; lastPresentAt = 0;
        sampler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Android HUD");
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        sampler.scheduleWithFixedDelay(() -> {
            if (isRunning && generation == token) {
                try { updateMetrics(token); }
                catch (RuntimeException error) { android.util.Log.w("MKVDCU", "HUD sample unavailable", error); }
            }
        }, 0, 1000, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void stop() {
        isRunning = false;
        ++generation;
        if (sampler != null) { sampler.shutdownNow(); sampler = null; }
        setVisibility(GONE);
    }

    @Override protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    public boolean isRunning() { return isRunning; }

    private void updateMetrics(int token) {
        Context context = getContext();
        if (context == null) return;

        long presentAt = SystemClock.elapsedRealtime();
        long presentCount = provider == null ? -1 : provider.getPresentCount();
        float presentFps = lastPresentCount >= 0 && presentCount >= lastPresentCount && presentAt > lastPresentAt
            ? (presentCount-lastPresentCount)*1000f/(presentAt-lastPresentAt) : -1f;
        lastPresentCount = presentCount; lastPresentAt = presentAt;
        final String outputCadence = (presentFps >= 0 ? String.format(java.util.Locale.US, "%.1f", presentFps) : "n/d")
            + " FPS / " + (presentationLimit > 0 ? "limite " + presentationLimit : "sem limite");

        // 1. FPS & Frametimes
        float fps = 0f;
        float frametimeMs = 0f;
        int totalShaders = -1;
        int totalPipelines = -1;

        if (provider != null) {
            String perfSnap = provider.getPerformanceSnapshot();
            if (perfSnap != null && !perfSnap.isEmpty()) {
                String[] parts = perfSnap.split(",");
                if (parts.length >= 3) {
                    try {
                        fps = Float.parseFloat(parts[1]);
                        frametimeMs = Float.parseFloat(parts[2]);
                    } catch (Exception ignored) { }
                }
                // PerformanceSummary contains cumulative operation counts, not
                // live/unique shader and pipeline objects.
                for (String item : perfSnap.split(" ")) {
                    if (item.startsWith("output=")) outputSize = item.substring(7);
                    if (!item.startsWith("gpu_submission=")) continue;
                    try {
                        String[] pair = item.substring(15).split("/");
                        double count = Double.parseDouble(pair[0]), ms = Double.parseDouble(pair[1]);
                        if (count > lastGpuCount && ms >= lastGpuTotalMs) {
                            gpuSubmissionMs = (float)((ms-lastGpuTotalMs)/(count-lastGpuCount));
                            gpuSampleAt = SystemClock.elapsedRealtime();
                        }
                        lastGpuCount = count; lastGpuTotalMs = ms;
                    } catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) { }
                }
                // Parse shader and pipeline operation counters from PerformanceSummary
                for (String part : parts) {
                    if (part.contains("shader=")) {
                        totalShaders = parseMetricCount(part, "shader=");
                    }
                    if (part.contains("pipeline=")) {
                        totalPipelines = parseMetricCount(part, "pipeline=");
                    }
                }
            }
        }

        // 2. CPU Usage
        float cpuUsage = calculateCpuUsage();

        // 3. GPU Usage
        float gpuUsage = readGpuUsage();
        // 4. Memory Info
        float usedRamGb = 0f;
        float totalRamGb = 0f;
        long appMemMb = 0;
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            totalRamGb = mi.totalMem / (1024f * 1024f * 1024f);
            usedRamGb = (mi.totalMem - mi.availMem) / (1024f * 1024f * 1024f);
        }
        appMemMb = readRssMiB();

        // 5. Battery
        float batTempC = 0f;
        int batLevel = -1;
        boolean isCharging = false;
        try {
            Intent bi = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (bi != null) {
                batTempC = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0f;
                batLevel = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int status = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                              status == BatteryManager.BATTERY_STATUS_FULL);
            }
        } catch (Exception ignored) { }

        // 6. CPU Temp & Thermal status
        float cpuTempC = readCpuTemperature();

        String driverName = provider != null ? provider.getDriverName() : "Vulkan";

        // Sampling / proc reads happen off the Android UI thread. Only publish
        // immutable values here; callbacks from a stopped session are discarded.
        final float fFps=fps, fTime=frametimeMs, fGpu=gpuUsage, fCpu=cpuUsage,
                fUsed=usedRamGb, fTotal=totalRamGb, fBat=batTempC, fTemp=cpuTempC;
        final long fRss=appMemMb;
        final int fLevel=batLevel, fShaders=totalShaders, fPipelines=totalPipelines, fAllowed=allowedCpuCount();
        final boolean fCharging=isCharging;
        final String fDriver=driverName, fOutput=outputSize, fCpuPolicy=cpuPolicyStatus;
        final float fGpuMs=SystemClock.elapsedRealtime()-gpuSampleAt <= 5000 ? gpuSubmissionMs : -1f;
        updateHandler.post(() -> {
            if (!isRunning || generation != token) return;
            if (isCompactMode) buildCompactText(fFps,fTime,fGpu,fCpu,fUsed,fBat,outputCadence);
            else buildFullMangoHudText(fFps,fTime,fGpu,fDriver,fCpu,fTemp,fUsed,fTotal,
                    fRss,fBat,fLevel,fCharging,fShaders,fPipelines,fOutput,fGpuMs,fAllowed,fCpuPolicy,outputCadence);
        });
    }

    private void buildFullMangoHudText(float fps, float frametimeMs, float gpuUsage, String driverName,
                                      float cpuUsage, float cpuTempC, float usedRamGb, float totalRamGb,
                                      long appMemMb, float batTempC, int batLevel, boolean isCharging,
                                      int shaders, int pipelines, String output, float gpuMs, int allowedCores,
                                      String cpuPolicy, String outputCadence) {
        StringBuilder sb = new StringBuilder();

        // FPS line: Bold Neon Green / Cyan
        String fpsColor = fps >= 55f ? "#00FF66" : fps >= 28f ? "#FACC15" : "#EF4444";
        sb.append("<font color=\"").append(fpsColor).append("\"><b>JOGO </b></font>")
          .append("<font color=\"#FFFFFF\"><b>").append(String.format("%.1f", fps)).append("</b></font>  ")
          .append("<font color=\"#86EFAC\">").append(String.format("%.1f ms", frametimeMs)).append("</font><br>");

        sb.append("<font color=\"#67E8F9\"><b>SAÍDA </b></font>").append(outputCadence).append("<br>");

        // GPU line: Coral Orange
        sb.append("<font color=\"#FB923C\"><b>GPU  </b></font>")
          .append("<font color=\"#FFFFFF\"><b>").append(percent(gpuUsage)).append("</b></font>  ")
          .append("<font color=\"#94A3B8\">").append(Html.escapeHtml(driverName)).append("</font><br>");

        // CPU line: Sky Blue
        int cores = Runtime.getRuntime().availableProcessors();
        sb.append("<font color=\"#38BDF8\"><b>CPU  </b></font>")
          .append("<font color=\"#FFFFFF\"><b>").append((int) cpuUsage).append("%</b></font>  ")
          .append("<font color=\"#7DD3FC\">").append(cpuTempC >= 0 ? String.format("%.1f°C", cpuTempC) : "n/d").append("</font>  ")
          .append("<font color=\"#94A3B8\">(").append(allowedCores >= 0 ? allowedCores+" CPUs elegíveis / " : "").append(cores).append(" disponíveis)</font><br>")
          .append("<font color=\"#94A3B8\">").append(Html.escapeHtml(cpuPolicy)).append("</font><br>");

        // RAM line: Violet / Orchid
        sb.append("<font color=\"#C084FC\"><b>RAM  </b></font>")
          .append("<font color=\"#FFFFFF\">").append(String.format("%.1f", usedRamGb))
          .append(" / ").append(String.format("%.1f GB", totalRamGb)).append("</font>  ")
          .append("<font color=\"#D8B4FE\">(RSS ").append(appMemMb >= 0 ? appMemMb+" MiB" : "n/d").append(")</font><br>");

        // BAT line: Yellow / Amber
        sb.append("<font color=\"#FACC15\"><b>BAT  </b></font>")
          .append("<font color=\"#FFFFFF\">").append(String.format("%.1f°C", batTempC)).append("</font>  ")
          .append("<font color=\"#FEF08A\">").append(batLevel >= 0 ? batLevel + "%" : "--")
          .append(isCharging ? " ⚡" : "").append("</font><br>");

        // SHADER line: Gold / Bronze
        sb.append("<font color=\"#F59E0B\"><b>SHD  </b></font>")
          .append("<font color=\"#FFFFFF\">").append(shaders >= 0 ? Integer.toString(shaders) : "n/d").append("</font> ")
          .append("<font color=\"#CBD5E1\">trad.  </font>")
          .append("<font color=\"#FFFFFF\">").append(pipelines >= 0 ? Integer.toString(pipelines) : "n/d").append("</font> ")
          .append("<font color=\"#CBD5E1\">tent. pip.</font>");

        sb.append("<br><font color=\"#94A3B8\">Swap ").append(Html.escapeHtml(output))
          .append(" | GPU/submit ").append(gpuMs >= 0 ? String.format("%.2f ms",gpuMs) : "n/d")
          .append("</font>");
        contentTextView.setText(Html.fromHtml(sb.toString()));
    }

    private void buildCompactText(float fps, float frametimeMs, float gpuUsage, float cpuUsage,
                                  float usedRamGb, float batTempC, String outputCadence) {
        String fpsColor = fps >= 55f ? "#00FF66" : fps >= 28f ? "#FACC15" : "#EF4444";
        String html = "<font color=\"" + fpsColor + "\"><b>" + String.format("Jogo %.1f FPS", fps) + "</b></font> " +
                "<font color=\"#94A3B8\">|</font> " +
                "<font color=\"#67E8F9\">Saída " + outputCadence + "</font> | " +
                "<font color=\"#FB923C\">GPU " + percent(gpuUsage) + "</font> " +
                "<font color=\"#94A3B8\">|</font> " +
                "<font color=\"#38BDF8\">CPU " + (int) cpuUsage + "%</font> " +
                "<font color=\"#94A3B8\">|</font> " +
                "<font color=\"#C084FC\">RAM " + String.format("%.1fG", usedRamGb) + "</font> " +
                "<font color=\"#94A3B8\">|</font> " +
                "<font color=\"#FACC15\">" + String.format("%.1f°C", batTempC) + "</font>";
        contentTextView.setText(Html.fromHtml(html));
    }

    private int parseMetricCount(String part, String prefix) {
        try {
            int idx = part.indexOf(prefix);
            if (idx >= 0) {
                String sub = part.substring(idx + prefix.length());
                int slash = sub.indexOf('/');
                int space = sub.indexOf(' ');
                int end = (slash >= 0) ? slash : ((space >= 0) ? space : sub.length());
                return Integer.parseInt(sub.substring(0, end).trim());
            }
        } catch (Exception ignored) { }
        return -1;
    }

    private static String percent(float value) { return value >= 0 ? ((int)value)+"%" : "n/d"; }

    private long readRssMiB() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/statm"))) {
            String[] words=br.readLine().trim().split("\\s+");
            return Long.parseLong(words[1])*android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)/(1024*1024);
        } catch (Exception ignored) { return -1; }
    }

    private int allowedCpuCount() {
        try (BufferedReader reader=new BufferedReader(new FileReader("/proc/thread-self/status"))) {
            String line;
            while ((line=reader.readLine())!=null) if (line.startsWith("Cpus_allowed_list:")) {
                int count=0;
                for (String range:line.substring(line.indexOf(':')+1).trim().split(",")) {
                    String[] bounds=range.split("-");
                    count+=bounds.length==1 ? 1 : Integer.parseInt(bounds[1])-Integer.parseInt(bounds[0])+1;
                }
                return count;
            }
        } catch (Exception ignored) { }
        return -1;
    }

    private float calculateCpuUsage() {
        try {
            long now = SystemClock.elapsedRealtime();
            long cpuTime = Process.getElapsedCpuTime();
            if (lastCpuRealtime > 0 && now > lastCpuRealtime) {
                int eligibleCpus = allowedCpuCount();
                if (eligibleCpus < 1) eligibleCpus = Runtime.getRuntime().availableProcessors();
                long timeDelta = (now - lastCpuRealtime) * eligibleCpus;
                long cpuDelta = cpuTime - lastCpuTime;
                if (timeDelta > 0) {
                    cachedCpuUsage = Math.min(100f, Math.max(0f, (cpuDelta * 100f) / (float) timeDelta));
                }
            }
            lastCpuRealtime = now;
            lastCpuTime = cpuTime;
        } catch (Exception ignored) { }
        return cachedCpuUsage;
    }

    private float readGpuUsage() {
        // 1. Qualcomm Adreno gpubusy
        try {
            File f = new File("/sys/class/kgsl/kgsl-3d0/gpubusy");
            if (f.exists() && f.canRead()) {
                BufferedReader br = new BufferedReader(new FileReader(f));
                String line = br.readLine();
                br.close();
                if (line != null) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) {
                        float busy = Float.parseFloat(parts[0]);
                        float total = Float.parseFloat(parts[1]);
                        if (total > 0) return Math.min(100f, (busy * 100f) / total);
                    }
                }
            }
        } catch (Exception ignored) { }

        // 2. ARM Mali utilization
        try {
            File f = new File("/sys/class/misc/mali0/device/utilization");
            if (f.exists() && f.canRead()) {
                BufferedReader br = new BufferedReader(new FileReader(f));
                String line = br.readLine();
                br.close();
                if (line != null) { float value=Float.parseFloat(line.trim());
                    if (Float.isFinite(value) && value >= 0 && value <= 100) return value;
                }
            }
        } catch (Exception ignored) { }

        return -1f;
    }

    private float readCpuTemperature() {
        long now = SystemClock.elapsedRealtime();
        if (lastThermalCheck > 0 && now - lastThermalCheck < 2000) {
            return cachedCpuTemp;
        }
        lastThermalCheck = now;

        // Only explicitly named CPU/SoC sensors. Battery is shown separately;
        // neither thermal status nor battery temperature estimates CPU degrees.
        File[] zones = new File("/sys/class/thermal").listFiles();
        if (zones != null) for (File zone : zones) {
            if (!zone.getName().startsWith("thermal_zone")) continue;
            try (BufferedReader type = new BufferedReader(new FileReader(new File(zone,"type")))) {
                String name=type.readLine().toLowerCase(java.util.Locale.ROOT);
                if (!(name.contains("cpu") || name.contains("soc") || name.equals("ap"))) continue;
                try (BufferedReader temp = new BufferedReader(new FileReader(new File(zone,"temp")))) {
                    float value=Float.parseFloat(temp.readLine().trim());
                    if (value > 1000f) value /= 1000f;
                    if (value >= 0f && value <= 125f) { cachedCpuTemp=value; return value; }
                }
            } catch (Exception ignored) { }
        }
        cachedCpuTemp=-1f;
        return -1f;
    }
}
