package org.mkvdcu.android;

import android.net.Uri;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.content.Intent;
import android.app.AlertDialog;
import android.widget.Toast;
import android.view.KeyEvent;
import android.view.View;
import org.libsdl.app.SDLActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Fullscreen SDL-backed Activity for Mortal Kombat vs. DC Universe.
 *
 * Boots the ReXGlue native runtime, initializes the Vulkan swapchain
 * on the Android ANativeWindow surface, loads the precompiled ARM64 game
 * logic, mounts the user's ISO image via VFS DiscImageDevice, and handles
 * controller / touch / keyboard events.
 */
public class GameActivity extends SDLActivity {

    private static final String TAG = "MKVDCU";
    private static ParcelFileDescriptor sIsoPfd;
    private VirtualGamepadView virtualGamepadView;
    private android.view.View inGameMenuView;
    private final Handler healthHandler = new Handler(Looper.getMainLooper());
    private TextView healthView;
    private long healthTick;
    private boolean diagnostics;
    private java.io.BufferedWriter performanceLog;
    private java.io.BufferedWriter frameLog;
    private long frameCursor;
    private boolean startupReported;
    private int renderAreaBenchmark = -1;
    private android.content.BroadcastReceiver benchmarkReceiver;
    private final Runnable performanceUpdate = new Runnable() {
        @Override public void run() {
            try {
                String snapshot = nativeReadPerformanceSnapshot();
                if (!startupReported && !snapshot.startsWith("0,")) {
                    startupReported = true;
                    android.os.ResultReceiver receiver = getIntent().getParcelableExtra("session_receiver");
                    if (receiver != null) receiver.send(1, new Bundle());
                }
                if (performanceLog != null) {
                    android.os.PowerManager power = getSystemService(android.os.PowerManager.class);
                    int thermal = android.os.Build.VERSION.SDK_INT >= 29 && power != null
                            ? power.getCurrentThermalStatus() : -1;
                    String actualDriver = DriverNative.identity().replace("\"", "\"\"").replace('\n', ' ');
                    performanceLog.write(android.os.SystemClock.elapsedRealtime() + "," +
                        snapshot + ",\"" + actualDriver + "\"," + thermal + "," + renderAreaBenchmark + "\n");
                    performanceLog.flush();
                }
                if (frameLog != null) {
                    String rows = nativeReadFrameIntervals(frameCursor);
                    int newline = rows.indexOf('\n');
                    frameCursor = Long.parseLong(rows.substring(0, newline));
                    frameLog.write(rows.substring(newline + 1));
                    frameLog.flush();
                }
            } catch (java.io.IOException error) { Log.e(TAG, "Performance CSV", error); }
            healthHandler.postDelayed(this, 1000);
        }
    };
    private final Runnable healthUpdate = new Runnable() {
        @Override public void run() {
            String snapshot = nativeReadHealthSnapshot();
            StringBuilder text = new StringBuilder("LIVE ").append(++healthTick)
                    .append("  count/age(ms)\n");
            int shown = 0;
            for (String field : snapshot.split(" ")) {
                if (field.startsWith("swap_out=") || field.startsWith("gpu_swap=") ||
                        field.startsWith("paint_out=") || field.startsWith("present=") ||
                        field.startsWith("input=") || field.startsWith("start_down=") ||
                        field.startsWith("audio_out=") || field.startsWith("audio_play=")) {
                    text.append(field).append("  ");
                    if (++shown % 4 == 0) text.append('\n');
                }
            }
            if (healthView != null) healthView.setText(text);
            healthHandler.postDelayed(this, 250);
        }
    };

    static native void nativeSetVirtualGamepadState(int buttons, int thumbLx, int thumbLy);
    static native void nativeSetVirtualGamepadStateV2(int buttons,int thumbLx,int thumbLy,int thumbRx,int thumbRy,int leftTrigger,int rightTrigger);
    static native String nativeReadHealthSnapshot();
    static native String nativeReadPerformanceSnapshot();
    static native String nativeReadFrameIntervals(long afterFrame);
    static native void nativeStartCpuProfile();

    static void setVirtualGamepadState(int buttons, int thumbLx, int thumbLy) {
        nativeSetVirtualGamepadStateV2(buttons, thumbLx, thumbLy,0,0,0,0);
    }

    /** The Xbox game is authored for a landscape display; SDL's resizable
     * default requests FULL_USER and otherwise overrides the manifest. */
    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        diagnostics = getIntent().getBooleanExtra("diagnostics", BuildConfig.DIAGNOSTIC_DEFAULT);
        super.onCreate(savedInstanceState);
        // Only debuggable APKs expose the ADB benchmark control. The native
        // atomic is consumed on the GPU thread; Vulkan stays on that thread.
        if (BuildConfig.DEBUG) {
            benchmarkReceiver = new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context context, android.content.Intent intent) {
                    if ((getPackageName() + ".DEBUG_CPU_PROFILE").equals(intent.getAction())) {
                        nativeStartCpuProfile();
                        return;
                    }
                    if ((getPackageName() + ".DEBUG_FRAME_CAPTURE").equals(intent.getAction())) {
                        DriverNative.armCapture();
                        try {
                            if (performanceLog != null) {
                                performanceLog.write("# manual_gpu_capture_at=" + android.os.SystemClock.elapsedRealtime() +
                                        "; subsequent timings include diagnostic stalls; restart for benchmarks\n");
                                performanceLog.flush();
                            }
                        } catch (java.io.IOException error) { Log.e(TAG, "Capture CSV", error); }
                        return;
                    }
                    boolean enabled = intent.getBooleanExtra("enabled", false);
                    DriverNative.setRenderAreaBenchmark(enabled);
                    renderAreaBenchmark = enabled ? 1 : 0;
                }
            };
            android.content.IntentFilter filter = new android.content.IntentFilter(getPackageName() + ".DEBUG_RENDER_AREA");
            filter.addAction(getPackageName() + ".DEBUG_FRAME_CAPTURE");
            filter.addAction(getPackageName() + ".DEBUG_CPU_PROFILE");
            if (android.os.Build.VERSION.SDK_INT >= 33)
                registerReceiver(benchmarkReceiver, filter, android.content.Context.RECEIVER_EXPORTED);
            else registerReceiver(benchmarkReceiver, filter);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        android.os.PowerManager power = getSystemService(android.os.PowerManager.class);
        boolean sustained = GraphicsSettings.sustained(getIntent()) && power != null && power.isSustainedPerformanceModeSupported();
        getWindow().setSustainedPerformanceMode(sustained);
        Log.i(TAG, "Graphics settings: " + GraphicsSettings.summary(getIntent()) + "; sustained_active=" + sustained);
        try {
            performanceLog = new java.io.BufferedWriter(new java.io.FileWriter(
                    new java.io.File(getFilesDir(), "last-performance.csv")));
            String injectedLayers = androidInjectedGpuLayers();
            Log.i(TAG, "Performance environment: diagnostic=" + diagnostics + "; Android requested GPU layers=" + injectedLayers);
            performanceLog.write("# driver=" + getIntent().getStringExtra("driver_id") + "; diagnostic=" + diagnostics +
                    "; android_requested_gpu_layers=" + injectedLayers + "; spirv_ssa_override=" +
                    (getIntent().hasExtra("benchmark_spirv_optimize") ? getIntent().getBooleanExtra("benchmark_spirv_optimize", false) : "default") +
                    "; " + GraphicsSettings.summary(getIntent()) + "; sustained_active=" + sustained +
                    "; frame_probe_prepare=" + (diagnostics || BuildConfig.DEBUG) + "\n");
            performanceLog.write("elapsed_ms,guest_frames,game_fps,avg_ms,median_ms,p95_ms,p99_ms,inclusive_stage_count_total_ms,actual_driver,thermal_status,render_area_benchmark\n");
            frameLog = new java.io.BufferedWriter(new java.io.FileWriter(new java.io.File(getFilesDir(), "last-frame-times.csv")));
            frameLog.write("frame_id,interval_ms\n");
        } catch (java.io.IOException error) { Log.e(TAG, "Performance CSV", error); }
        android.view.View content = SDLActivity.getContentView();
        if (content instanceof ViewGroup) {
            ViewGroup root = (ViewGroup) content;
            virtualGamepadView = new VirtualGamepadView(this, GameActivity::nativeSetVirtualGamepadStateV2,
                () -> startActivity(new android.content.Intent(this, GamepadEditorActivity.class)),
                this::toggleInGameMenu);
            root.addView(virtualGamepadView, new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            Log.i(TAG, "Virtual touchscreen gamepad enabled with menu hook");

            inGameMenuView = getLayoutInflater().inflate(R.layout.layout_ingame_menu, root, false);
            inGameMenuView.setVisibility(android.view.View.GONE);
            root.addView(inGameMenuView, new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            setupInGameMenu();
            if (diagnostics) {
            healthView = new TextView(this);
            healthView.setTextColor(0xFFE0FFDD);
            healthView.setBackgroundColor(0xAA102018);
            healthView.setTextSize(9);
            healthView.setTypeface(android.graphics.Typeface.MONOSPACE);
            healthView.setPadding(8, 4, 8, 4);
            RelativeLayout.LayoutParams healthParams = new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            healthParams.leftMargin = 20;
            healthParams.topMargin = 145;
            root.addView(healthView, healthParams);
            healthView.setOnLongClickListener(view -> {
                DriverNative.armCapture();
                android.widget.Toast.makeText(this, "Captura dos próximos 5 frames armada", android.widget.Toast.LENGTH_LONG).show();
                return true;
            });
            }
        } else {
            Log.e(TAG, "Could not attach virtual touchscreen gamepad: SDL content view missing");
        }
    }

    private String androidInjectedGpuLayers() {
        try {
            boolean enabled = android.provider.Settings.Global.getInt(getContentResolver(), "enable_gpu_debug_layers", 0) != 0;
            String app = android.provider.Settings.Global.getString(getContentResolver(), "gpu_debug_app");
            if (!enabled || !getPackageName().equals(app)) return "none";
            String layers = android.provider.Settings.Global.getString(getContentResolver(), "gpu_debug_layers");
            return layers == null ? "unspecified" : layers.replace('\n', ' ').replace('\r', ' ');
        } catch (SecurityException error) {
            return "unavailable";
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if(virtualGamepadView!=null)virtualGamepadView.reloadLayout();
        healthHandler.removeCallbacks(healthUpdate);
        if (healthView != null) healthHandler.post(healthUpdate);
        healthHandler.removeCallbacks(performanceUpdate);
        healthHandler.post(performanceUpdate);
    }

    @Override
    protected void onPause() {
        healthHandler.removeCallbacks(healthUpdate);
        healthHandler.removeCallbacks(performanceUpdate);
        if(virtualGamepadView!=null)virtualGamepadView.releaseAll();
        nativeSetVirtualGamepadStateV2(0,0,0,0,0,0,0);
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if(!focused && virtualGamepadView!=null)virtualGamepadView.releaseAll();
    }

    @Override
    protected void onDestroy() {
        if (benchmarkReceiver != null) {
            unregisterReceiver(benchmarkReceiver);
            benchmarkReceiver = null;
        }
        healthHandler.removeCallbacks(healthUpdate);
        healthHandler.removeCallbacks(performanceUpdate);
        if (performanceLog != null) try { performanceLog.close(); } catch (java.io.IOException ignored) { }
        if (frameLog != null) try { frameLog.close(); } catch (java.io.IOException ignored) { }
        if(virtualGamepadView!=null)virtualGamepadView.releaseAll();
        nativeSetVirtualGamepadStateV2(0,0,0,0,0,0,0);
        super.onDestroy();
        if (sIsoPfd != null) {
            try {
                sIsoPfd.close();
            } catch (Exception ignored) {
            }
            sIsoPfd = null;
        }
    }

    private void setupInGameMenu() {
        if (inGameMenuView == null) return;
        inGameMenuView.findViewById(R.id.btn_menu_close).setOnClickListener(v -> hideInGameMenu());
        inGameMenuView.findViewById(R.id.btn_menu_resume).setOnClickListener(v -> hideInGameMenu());
        inGameMenuView.findViewById(R.id.btn_menu_controls).setOnClickListener(v -> {
            hideInGameMenu();
            startActivity(new Intent(this, GamepadEditorActivity.class));
        });
        inGameMenuView.findViewById(R.id.btn_menu_hud).setOnClickListener(v -> toggleHud());
        inGameMenuView.findViewById(R.id.btn_menu_restart).setOnClickListener(v -> confirmRestart());
        inGameMenuView.findViewById(R.id.btn_menu_exit).setOnClickListener(v -> confirmExit());
    }

    private void toggleHud() {
        if (healthView != null) {
            boolean isVisible = healthView.getVisibility() == View.VISIBLE;
            healthView.setVisibility(isVisible ? View.GONE : View.VISIBLE);
            if (!isVisible) healthHandler.post(healthUpdate);
            else healthHandler.removeCallbacks(healthUpdate);
            Toast.makeText(this, isVisible ? "HUD de desempenho ocultado" : "HUD de desempenho ativado", Toast.LENGTH_SHORT).show();
        } else {
            View content = SDLActivity.getContentView();
            if (content instanceof ViewGroup) {
                ViewGroup root = (ViewGroup) content;
                healthView = new TextView(this);
                healthView.setTextColor(0xFFE0FFDD);
                healthView.setBackgroundColor(0xAA102018);
                healthView.setTextSize(9);
                healthView.setTypeface(android.graphics.Typeface.MONOSPACE);
                healthView.setPadding(8, 4, 8, 4);
                RelativeLayout.LayoutParams healthParams = new RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                healthParams.leftMargin = 20;
                healthParams.topMargin = 145;
                root.addView(healthView, healthParams);
                healthHandler.post(healthUpdate);
                Toast.makeText(this, "HUD de desempenho ativado", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void confirmRestart() {
        new AlertDialog.Builder(this)
                .setTitle("Reiniciar Partida")
                .setMessage("Deseja reiniciar a sessão atual do jogo?")
                .setPositiveButton("Reiniciar", (dialog, which) -> restartGameSession())
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void confirmExit() {
        new AlertDialog.Builder(this)
                .setTitle("Sair do Port")
                .setMessage("Deseja encerrar o jogo e voltar ao menu principal?")
                .setPositiveButton("Sair", (dialog, which) -> exitPort())
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void restartGameSession() {
        hideInGameMenu();
        if (virtualGamepadView != null) virtualGamepadView.releaseAll();
        nativeSetVirtualGamepadStateV2(0, 0, 0, 0, 0, 0, 0);
        Intent restart = new Intent(getIntent());
        restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        finish();
        startActivity(restart);
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private void exitPort() {
        hideInGameMenu();
        Log.i(TAG, "Exiting port cleanly upon user request");
        if (virtualGamepadView != null) virtualGamepadView.releaseAll();
        nativeSetVirtualGamepadStateV2(0, 0, 0, 0, 0, 0, 0);
        healthHandler.removeCallbacksAndMessages(null);
        if (performanceLog != null) try { performanceLog.close(); } catch (Exception ignored) { }
        if (frameLog != null) try { frameLog.close(); } catch (Exception ignored) { }
        if (sIsoPfd != null) {
            try { sIsoPfd.close(); } catch (Exception ignored) { }
            sIsoPfd = null;
        }
        finish();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            android.os.Process.killProcess(android.os.Process.myPid());
        }, 120);
    }

    public void showInGameMenu() {
        if (inGameMenuView == null) return;
        if (virtualGamepadView != null) virtualGamepadView.releaseAll();
        nativeSetVirtualGamepadStateV2(0, 0, 0, 0, 0, 0, 0);
        TextView info = inGameMenuView.findViewById(R.id.ingame_session_info);
        if (info != null) {
            String driverId = getIntent().getStringExtra("driver_id");
            DriverStore.Driver driver = new DriverStore(this).get(driverId);
            info.setText("Driver Vulkan: " + (driver != null ? driver.name : "Sistema") +
                    " • 1× Resolução Nativa • Xbox 360 ARM64");
        }
        inGameMenuView.setVisibility(View.VISIBLE);
        inGameMenuView.bringToFront();
    }

    public void hideInGameMenu() {
        if (inGameMenuView != null) {
            inGameMenuView.setVisibility(View.GONE);
        }
    }

    public void toggleInGameMenu() {
        if (inGameMenuView != null && inGameMenuView.getVisibility() == View.VISIBLE) {
            hideInGameMenu();
        } else {
            showInGameMenu();
        }
    }

    @Override
    public void onBackPressed() {
        toggleInGameMenu();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
            toggleInGameMenu();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void loadLibraries() {
        DriverStore.Driver driver = new DriverStore(this).get(getIntent().getStringExtra("driver_id"));
        driver.turnipMode = driver.kind.equals("turnip") ? GraphicsSettings.turnipMode(getIntent()) : 0;
        if (!driver.usable()) throw new UnsatisfiedLinkError("Driver incompatível: " + driver.reason());
        String error = DriverNative.configure(this, driver);
        if (!error.isEmpty()) throw new UnsatisfiedLinkError(error);
        DriverNative.setDiagnosticMode(diagnostics);
        super.loadLibraries();
    }

    @Override
    protected String[] getLibraries() {
        return new String[] {
            "rexruntime",
            "rexgpu-xenos",
            "mkvdcu"
        };
    }

    @Override
    protected String getMainSharedObject() {
        return getContext().getApplicationInfo().nativeLibraryDir + "/libmkvdcu.so";
    }

    @Override
    protected String getMainFunction() {
        return "SDL_main";
    }

    @Override
    protected String[] getArguments() {
        List<String> args = new ArrayList<>();
        args.add("mkvdcu");

        String isoUri = getIntent().getStringExtra("iso_uri");
        if (isoUri == null || isoUri.isEmpty()) {
            Prefs prefs = new Prefs(this);
            isoUri = prefs.getIsoUri();
        }

        String gamePath = null;
        if (isoUri != null && !isoUri.isEmpty()) {
            if (isoUri.startsWith("content://") || isoUri.startsWith("file://")) {
                try {
                    Uri parsedUri = Uri.parse(isoUri);
                    if (sIsoPfd != null) {
                        try {
                            sIsoPfd.close();
                        } catch (Exception ignored) {
                        }
                    }
                    sIsoPfd = getContentResolver().openFileDescriptor(parsedUri, "r");
                    if (sIsoPfd != null) {
                        int fd = sIsoPfd.getFd();
                        gamePath = "/proc/self/fd/" + fd;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to open ISO file descriptor for " + isoUri + ": " + e.getMessage(), e);
                }
            } else {
                gamePath = isoUri;
            }
        }

        if (gamePath != null) {
            args.add("--game_data_root=" + gamePath);
        }

        args.add("--fullscreen=true");
        // Keep the real translated Xenos pixel shader active while recording
        // the fetch constant and per-channel TextureSign for the EULA font draw.
        args.add("--android_debug_eula_probe_stage=0");
        args.add("--gpu_plugin=xenos");
        args.add("--vulkan_async_skip_incomplete_frames=false");
        args.add("--native_2x_msaa=false");
        GraphicsSettings.appendArguments(getIntent(), args);
        args.add("--android_swap_passthrough=true");
        // This investigation observes progress; old pixel readbacks would add
        // GPU stalls and change the timing of the Start transition.
        args.add("--vulkan_validation_enabled=" + diagnostics);
        args.add("--vulkan_frame_probe=" + diagnostics);
        // Reserve legal TRANSFER_SRC usage at image creation for a later manual
        // debug capture. This enables no readbacks, validation, or verbose logs.
        args.add("--vulkan_frame_probe_prepare=" + (diagnostics || BuildConfig.DEBUG));
        args.add("--android_gpu_diagnostics=" + diagnostics);
        args.add("--port_render_mode=native");

        Log.i(TAG, "Launching MKVDCU with args: " + args);
        return args.toArray(new String[0]);
    }
}
