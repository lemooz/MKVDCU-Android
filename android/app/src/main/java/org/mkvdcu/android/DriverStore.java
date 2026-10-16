package org.mkvdcu.android;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Imported drivers are executable native code, kept entirely in app-private storage. */
final class DriverStore {
    static final int PROBE_VERSION = 202610145;
    private static final long MAX_ARCHIVE = 128L * 1024 * 1024;
    private static final long MAX_EXPANDED = 256L * 1024 * 1024;
    private final Context context;
    private final File root;
    static final class Driver {
        String id, kind, name, version, library, packageIssue = "";
        int minApi, turnipMode, panvkTilerHeapMb;
        boolean panvkLateStencil, presentQueue;
        File directory;
        JSONObject metadata, report;
        String reason() {
            if (kind.equals("panvk") && report != null && report.optInt("panvkTilerHeapMb",0) != panvkTilerHeapMb)
                return "Heap de tiler alterado; verifique o driver novamente.";
            if (!kind.equals("system") && report != null && report.optBoolean("presentQueueRequested", false) != presentQueue)
                return "Verifique novamente o driver com a fila de apresentação selecionada";
            if (kind.equals("panvk") && report != null && report.optBoolean("panvkLateStencil", false) != panvkLateStencil)
                return "Verifique novamente o driver com a opção stencil PanVK selecionada";
            if (kind.equals("turnip") && report != null && report.optInt("turnipMode", -1) != turnipMode)
                return "Verifique novamente o driver com o modo Turnip selecionado";
            if (minApi > Build.VERSION.SDK_INT) return "Requer Android API " + minApi;
            if (!packageIssue.isEmpty()) return packageIssue;
            if (report == null || report.optInt("probeVersion") != PROBE_VERSION ||
                    report.optInt("androidApi") != Build.VERSION.SDK_INT ||
                    !report.optString("buildFingerprint").equals(Build.FINGERPRINT))
                return "Compatibilidade ainda não verificada";
            return report.optString("message", "Driver incompatível");
        }
        String requirements() {
            if (metadata == null) return "";
            StringBuilder text = new StringBuilder();
            for (String key : new String[]{"vendor", "driverVersion", "author"})
                if (metadata.has(key)) text.append(key).append(": ").append(metadata.optString(key)).append('\n');
            for (String key : new String[]{"profile", "kernelInterface", "frontend", "panArch", "kbaseUapi", "gpuId"})
                if (metadata.has(key)) text.append(key).append(": ").append(metadata.optString(key)).append('\n');
            text.append("Android mínimo: API ").append(minApi).append("; atual: ").append(Build.VERSION.SDK_INT).append('\n');
            if (kind.equals("panvk")) text.append("Experimental: GPU/kernel e tessellation precisam passar no teste real. O wrapper DXVK/Winlator não é utilizado.\n");
            if (metadata.has("description")) text.append(metadata.optString("description")).append('\n');
            if (new File(directory, "loader-preparation.json").isFile())
                text.append("Cópia privada preparada: caminhos externos de compilação removidos.\n");
            if (new File(directory, "app-bionic-dependencies.json").isFile())
                text.append("Dependências Bionic fornecidas pelo aplicativo (API 36+), em namespace privado.\n");
            if (report != null && report.optJSONObject("capabilities") != null)
                text.append("Recursos medidos: ").append(report.optJSONObject("capabilities")).append('\n');
            return text.toString();
        }
        boolean usable() {
            return kind.equals("system") || (packageIssue.isEmpty() && minApi <= Build.VERSION.SDK_INT && report != null &&
                (!kind.equals("turnip") || report.optInt("turnipMode", -1) == turnipMode) &&
                (report.optBoolean("presentQueueRequested", false) == presentQueue) &&
                (!kind.equals("panvk") || (report.optBoolean("panvkLateStencil", false) == panvkLateStencil && report.optInt("panvkTilerHeapMb",0) == panvkTilerHeapMb)) &&
                report.optInt("probeVersion") == PROBE_VERSION &&
                report.optInt("androidApi") == Build.VERSION.SDK_INT &&
                report.optString("buildFingerprint").equals(Build.FINGERPRINT) && report.optBoolean("ok"));
        }
    }
    DriverStore(Context context) {
        this.context = context.getApplicationContext();
        root = new File(context.getFilesDir(), "gpu-drivers");
        root.mkdirs();
    }
    Driver system() {
        Driver d = new Driver();
        d.id = "system"; d.kind = "system"; d.name = "Driver do sistema";
        d.library = ""; d.version = ""; d.minApi = 28; d.directory = root;
        try {
            File report = new File(root, "probe.json");
            if (report.isFile()) d.report = new JSONObject(readSmall(report));
        } catch (Exception ignored) { }
        return d;
    }
    List<Driver> list() {
        List<Driver> drivers = new ArrayList<>();
        drivers.add(system());
        File[] files = root.listFiles();
        if (files != null) for (File file : files) {
            if (!file.getName().matches("[a-f0-9]{64}")) continue;
            try { drivers.add(read(file)); } catch (Exception ignored) { }
        }
        return drivers;
    }
    Driver get(String id) {
        if (id == null || id.equals("system")) return system();
        if (!id.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("ID inválido");
        try { return read(new File(root, id)); }
        catch (Exception e) { throw new IllegalArgumentException("Pacote de driver ausente", e); }
    }
    private Driver read(File folder) throws Exception {
        JSONObject meta = new JSONObject(readSmall(new File(folder, "meta.json")));
        Driver d = new Driver();
        d.id = folder.getName(); d.directory = folder;
        d.name = meta.getString("name"); d.version = meta.optString("packageVersion", "?");
        d.library = meta.getString("libraryName"); d.minApi = meta.getInt("minApi");
        d.kind = GpuDriverKind.classify(d.name, meta.optString("backend"), d.library, meta.optString("vendor"));
        d.presentQueue = new GraphicsSettings(context).presentQueue();
        d.panvkTilerHeapMb = d.kind.equals("panvk") ? new GraphicsSettings(context).panvkTilerHeapMb() : 0;
        d.panvkLateStencil = d.kind.equals("panvk") && new GraphicsSettings(context).panvkLateStencil();
        d.turnipMode = d.kind.equals("turnip") ? new GraphicsSettings(context).turnipMode() : 0;
        if (d.kind.equals("unknown")) d.packageIssue = "Backend não identificado: use um pacote PanVK, Turnip ou Qualcomm Adreno com metadados correspondentes";
        d.metadata = meta;
        try { ElfDriver.loadOrder(folder, d.library); }
        catch (IOException error) { d.packageIssue = error.getMessage(); }
        File report = new File(folder, "probe.json");
        if (report.isFile()) d.report = new JSONObject(readSmall(report));
        return d;
    }
    Driver importUri(Uri uri) throws Exception {
        File archive = File.createTempFile("driver-", ".zip", context.getCacheDir());
        File stage = new File(root, ".import-" + UUID.randomUUID());
        stage.mkdirs();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = context.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(archive)) {
                if (in == null) throw new IOException("Não foi possível abrir o pacote");
                byte[] buffer = new byte[65536]; long total = 0; int count;
                while ((count = in.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_ARCHIVE) throw new IOException("Pacote excede 128 MiB");
                    digest.update(buffer, 0, count); out.write(buffer, 0, count);
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
            String id = hex.toString();
            if (new File(root, id).isDirectory()) {
                Driver existing = get(id);
                if (existing.kind.equals("panvk")) {
                    Set<String> existingNames = new HashSet<>();
                    File[] existingFiles = existing.directory.listFiles();
                    if (existingFiles == null) throw new IOException("Pacote PanVK inacessível");
                    for (File file : existingFiles) if (file.isFile()) existingNames.add(file.getName());
                    if (PanvkDependencies.complete(context, existing.directory, existingNames, existing.library)) {
                        File oldReport = new File(existing.directory, "probe.json");
                        if (oldReport.exists() && !oldReport.delete()) throw new IOException("Falha ao invalidar probe antigo");
                    }
                }
                return get(id);
            }
            long expanded = 0; int entries = 0;
            Set<String> names = new HashSet<>();
            try (ZipFile zip = new ZipFile(archive)) {
                Enumeration<? extends ZipEntry> iterator = zip.entries();
                while (iterator.hasMoreElements()) {
                    ZipEntry entry = iterator.nextElement();
                    String name = entry.getName();
                    if (++entries > 128 || name.contains("/") || name.contains("\\") ||
                            name.equals(".") || name.equals("..") || !names.add(name) ||
                            !name.matches("[A-Za-z0-9_+.-]+"))
                        throw new IOException("Pacote deve ser plano, sem caminhos ou entradas duplicadas");
                    if (entry.isDirectory()) throw new IOException("Diretório inesperado no pacote");
                    File target = new File(stage, name);
                    try (InputStream in = zip.getInputStream(entry);
                         OutputStream out = new FileOutputStream(target)) {
                        byte[] buffer = new byte[65536]; int count;
                        while ((count = in.read(buffer)) != -1) {
                            expanded += count;
                            if (expanded > MAX_EXPANDED) throw new IOException("Conteúdo excede 256 MiB");
                            out.write(buffer, 0, count);
                        }
                    }
                }
            }
            JSONObject meta = new JSONObject(readSmall(new File(stage, "meta.json")));
            if (meta.getInt("schemaVersion") != 1 || meta.getString("name").isEmpty() ||
                    meta.getInt("minApi") < 1) throw new IOException("Metadados incompatíveis");
            String abi = meta.optString("abi", "android-aarch64-bionic");
            if (!abi.equals("android-aarch64-bionic")) throw new IOException("É necessário um driver Android/Bionic ARM64");
            String library = meta.getString("libraryName");
            if (!library.matches("[A-Za-z0-9_][A-Za-z0-9_+.-]*\\.so") || !names.contains(library))
                throw new IOException("Biblioteca do driver ausente ou inválida");
            boolean panvk = (meta.getString("name") + " " + meta.optString("backend") + " " + library)
                    .toLowerCase(Locale.ROOT).matches(".*(panvk|panfrost).*");
            org.json.JSONArray prepared = new org.json.JSONArray();
            if (panvk) PanvkDependencies.complete(context, stage, names, library);
            for (String name : names) if (name.contains(".so")) {
                File binary = new File(stage, name);
                if (ElfDriver.prepare(binary, names)) {
                    MessageDigest binaryDigest = MessageDigest.getInstance("SHA-256");
                    try (InputStream in = new FileInputStream(binary)) {
                        byte[] buffer = new byte[65536]; int count;
                        while ((count = in.read(buffer)) != -1) binaryDigest.update(buffer, 0, count);
                    }
                    StringBuilder sha = new StringBuilder();
                    for (byte b : binaryDigest.digest()) sha.append(String.format(Locale.ROOT, "%02x", b & 255));
                    prepared.put(new JSONObject().put("library", name).put("preparedSha256", sha.toString()));
                }
                // Keep incomplete experimental PanVK packages visible, but read()
                // and configure() must reject activation until the full DAG exists.
                if (panvk) ElfDriver.missingDependencies(binary, names);
                else ElfDriver.validate(binary, names);
            }
            if (!panvk) ElfDriver.loadOrder(stage, library);
            File preparation = new File(stage, "loader-preparation.json");
            if (preparation.exists() && !preparation.delete()) throw new IOException("Falha na limpeza de proveniência");
            if (prepared.length() != 0) {
                JSONObject record = new JSONObject().put("policyVersion", 1).put("archiveSha256", id)
                    .put("operation", "remove external DT_RPATH/DT_RUNPATH; instructions unchanged").put("libraries", prepared);
                try (OutputStream out = new FileOutputStream(preparation)) {
                    out.write(record.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            // probe.json from an archive is never trusted; only our own probe can authorize selection.
            File bundledReport = new File(stage, "probe.json");
            if (bundledReport.exists() && !bundledReport.delete()) throw new IOException("Falha na limpeza do pacote");
            if (!stage.renameTo(new File(root, id))) throw new IOException("Falha ao publicar pacote");
            return get(id);
        } finally { archive.delete(); deleteTree(stage); }
    }
    void saveReport(String id, String json) throws Exception {
        Driver d = get(id);
        JSONObject report = new JSONObject(json);
        report.put("probeVersion", PROBE_VERSION).put("androidApi", Build.VERSION.SDK_INT)
                .put("buildFingerprint", Build.FINGERPRINT);
        android.util.AtomicFile file = new android.util.AtomicFile(new File(d.directory, "probe.json"));
        FileOutputStream out = file.startWrite();
        try { out.write(report.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(out); }
        catch (Exception e) { file.failWrite(out); throw e; }
    }
    void remove(String id) { if (!id.equals("system")) deleteTree(get(id).directory); }
    private static String readSmall(File file) throws Exception {
        if (file.length() > 65536) throw new IOException("Metadados excessivamente grandes");
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > 65536) throw new IOException("Metadados excessivamente grandes");
                out.write(buffer, 0, count);
            }
            return out.toString("UTF-8");
        }
    }
    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
