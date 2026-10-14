package org.mkvdcu.android;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {

    private static final String FILE = "mkvdcu";
    private static final String KEY_ISO_URI = "iso_uri";
    private static final String KEY_INSTALLED = "installed";
    private static final String KEY_XEX_SHA = "xex_sha256";

    private final SharedPreferences sp;

    Prefs(Context ctx) {
        sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    String getIsoUri() {
        return sp.getString(KEY_ISO_URI, "");
    }

    String getDriverId() { return sp.getString("driver_id", "system"); }
    void setDriverId(String id) { sp.edit().putString("driver_id", id).commit(); }
    String getPendingProbe() { return sp.getString("pending_driver_probe", ""); }
    void setPendingProbe(String id) { sp.edit().putString("pending_driver_probe", id).commit(); }
    String getPendingGameDriver() { return sp.getString("pending_game_driver", ""); }
    void setPendingGameDriver(String id) { sp.edit().putString("pending_game_driver", id).commit(); }
    boolean diagnostics() { return sp.getBoolean("diagnostics", BuildConfig.DIAGNOSTIC_DEFAULT); }
    void setDiagnostics(boolean value) { sp.edit().putBoolean("diagnostics", value).apply(); }
    boolean isPerformanceOverlayEnabled() { return sp.getBoolean("perf_overlay_enabled", false); }
    void setPerformanceOverlayEnabled(boolean value) { sp.edit().putBoolean("perf_overlay_enabled", value).apply(); }

    void setIsoUri(String uri) {
        sp.edit().putString(KEY_ISO_URI, uri).apply();
    }

    boolean isInstalled() {
        return sp.getBoolean(KEY_INSTALLED, false);
    }

    void setInstalled(boolean installed) {
        sp.edit().putBoolean(KEY_INSTALLED, installed).apply();
    }

    String getXexSha256() {
        return sp.getString(KEY_XEX_SHA, "");
    }

    void setXexSha256(String sha) {
        sp.edit().putString(KEY_XEX_SHA, sha == null ? "" : sha).apply();
    }
}
