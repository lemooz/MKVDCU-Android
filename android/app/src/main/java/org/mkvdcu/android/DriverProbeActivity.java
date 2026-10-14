package org.mkvdcu.android;

import android.app.Activity;
import android.content.Intent;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;

/** Disposable process: native driver failures cannot take down the launcher. */
public final class DriverProbeActivity extends Activity implements SurfaceHolder.Callback {
    private boolean started;
    private final Handler handler = new Handler(Looper.getMainLooper());
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        SurfaceView surface = new SurfaceView(this);
        setContentView(surface);
        surface.getHolder().addCallback(this);
        ResultReceiver receiver = getIntent().getParcelableExtra("probe_receiver");
        if (receiver != null) {
            Bundle data = new Bundle(); data.putInt("pid", android.os.Process.myPid());
            receiver.send(0, data);
        }
        // Also bounded when the parent has been reclaimed by Android.
        handler.postDelayed(() -> {
            JSONObject failure = new JSONObject();
            try { failure.put("ok", false).put("message", "Verificação excedeu 30 segundos. Última etapa: " + DriverNative.probePhase); }
            catch (Exception ignored) { }
            finishResult(failure.toString());
        }, 30000);
    }
    @Override public void surfaceCreated(SurfaceHolder holder) {
        if (started) return;
        started = true;
        new Thread(() -> {
            String result;
            try {
                DriverNative.probeProgress = phase -> {
                    ResultReceiver receiver = getIntent().getParcelableExtra("probe_receiver");
                    if (receiver != null) {
                        Bundle data = new Bundle(); data.putString("phase", phase);
                        receiver.send(1, data);
                    }
                };
                DriverStore.Driver driver = new DriverStore(this).get(getIntent().getStringExtra("driver_id"));
                driver.turnipMode = driver.kind.equals("turnip") ? GraphicsSettings.turnipMode(getIntent()) : 0;
                driver.panvkLateStencil = driver.kind.equals("panvk") && GraphicsSettings.panvkLateStencil(getIntent());
                String error = DriverNative.configure(this, driver);
                if (!error.isEmpty()) throw new IllegalStateException(error);
                DriverNative.setPresentQueueBenchmark(getIntent().getBooleanExtra("graphics_present_queue", false));
                result = DriverNative.probe(holder.getSurface());
            } catch (Throwable error) {
                JSONObject failure = new JSONObject();
                try { failure.put("ok", false).put("message", error.toString()); } catch (Exception ignored) { }
                result = failure.toString();
            }
            final String report = result;
            runOnUiThread(() -> finishResult(report));
        }, "driver-probe").start();
    }
    private void finishResult(String report) {
        if (isFinishing()) return;
        try { report = new JSONObject(report).put("turnipMode", GraphicsSettings.turnipMode(getIntent())).put("panvkLateStencil", GraphicsSettings.panvkLateStencil(getIntent())).put("presentQueueRequested", getIntent().getBooleanExtra("graphics_present_queue", false)).toString(); }
        catch (Exception ignored) { }
        handler.removeCallbacksAndMessages(null);
        setResult(RESULT_OK, new Intent().putExtra("driver_report", report));
        finish();
    }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
        // All driver handles, workers and potentially stalled queues belong to
        // this process. A completed probe must never be reused for the game.
        // Let Activity's result IPC reach the launcher before exiting.
        handler.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 500);
    }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { }
}
