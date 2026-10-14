package org.mkvdcu.android;

import android.content.Context;
import android.os.Build;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Signed app assets, copied only into the selected PanVK package namespace. */
final class PanvkDependencies {
    private static final Map<String,String> HASHES = new LinkedHashMap<>();
    static {
        HASHES.put("libdrm.so", "0e4e8f0bbc6809589206a757759a6506e0aa7c88e0b55de2fe8d54f7ef6a3323");
        HASHES.put("libz.so.1", "c47748ed4b6ca6a2ecd664bab04795f3493956ab1f5cddc4ba0efa737ef3b711");
        HASHES.put("libzstd.so.1", "1677d5e828c1839f111f0261697c08f99a5e4e80434659ab88e5ff859a1374f7");
        HASHES.put("libc++_shared.so", "05bef9d3901910c3ff8ab450b03bc8b28383d363a2290f2f7b9aed7331ea6ff7");
    }
    static boolean complete(Context context, File directory, Set<String> names, String library) throws Exception {
        if (Build.VERSION.SDK_INT < 36) return false;
        Deque<String> pending = new ArrayDeque<>(); pending.add(library);
        Set<String> visited = new HashSet<>(); JSONArray supplied = new JSONArray();
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            if (!visited.add(name)) continue;
            for (String dependency : ElfDriver.dependenciesForImport(new File(directory, name), names)) {
                if (names.contains(dependency)) { pending.add(dependency); continue; }
                String expected = HASHES.get(dependency);
                if (expected == null) continue;
                File temporary = File.createTempFile(".helper-", ".tmp", directory);
                try {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    try (InputStream in = context.getAssets().open("panvk-bionic/" + dependency);
                         OutputStream out = new FileOutputStream(temporary)) {
                        byte[] bytes = new byte[65536]; int count; long size = 0;
                        while ((count = in.read(bytes)) != -1) {
                            if ((size += count) > 4L * 1024 * 1024) throw new IOException("Helper PanVK excessivo");
                            digest.update(bytes, 0, count); out.write(bytes, 0, count);
                        }
                    }
                    String actual = hex(digest.digest());
                    if (!expected.equals(actual)) throw new IOException("SHA256 do helper PanVK inválido: " + dependency);
                    Set<String> available = new HashSet<>(names); available.add(dependency);
                    ElfDriver.prepare(temporary, available);
                    ElfDriver.validate(temporary, available);
                    File target = new File(directory, dependency);
                    if (target.exists() || !temporary.renameTo(target)) throw new IOException("Falha ao instalar helper: " + dependency);
                    names.add(dependency); pending.add(dependency);
                    supplied.put(new JSONObject().put("name", dependency).put("assetSha256", actual)
                        .put("preparedSha256", hash(target)));
                } finally { temporary.delete(); }
            }
        }
        if (supplied.length() == 0) return false;
        AtomicFile record = new AtomicFile(new File(directory, "app-bionic-dependencies.json"));
        FileOutputStream out = record.startWrite();
        try {
            out.write(new JSONObject().put("revision", 1).put("minimumAndroidApi", 36)
                .put("libraries", supplied).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            record.finishWrite(out);
        } catch (Exception error) { record.failWrite(out); throw error; }
        return true;
    }
    private static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] data = new byte[65536]; int count;
            while ((count = in.read(data)) != -1) digest.update(data, 0, count);
        }
        return hex(digest.digest());
    }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
}
