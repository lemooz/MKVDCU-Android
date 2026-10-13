package org.mkvdcu.android;
import java.util.Locale;

/** Package hints select a loader; only the Vulkan probe authorizes activation. */
final class GpuDriverKind {
    static String classify(String name, String backend, String library, String vendor) {
        String text = (name + " " + backend + " " + library + " " + vendor).toLowerCase(Locale.ROOT);
        if (text.contains("panvk") || text.contains("panfrost")) return "panvk";
        // Adreno is also a hardware/vendor hint in Mesa packages. Preserve
        // Turnip identity checks when such a package names that backend.
        if (text.contains("turnip") || text.contains("freedreno")) return "turnip";
        if (text.contains("adreno") || text.contains("qualcomm")) return "adreno";
        return "unknown";
    }
}
