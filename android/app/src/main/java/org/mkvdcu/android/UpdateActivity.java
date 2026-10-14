package org.mkvdcu.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.zip.ZipFile;

/** Launcher-only updater. No network, downloads or installation in the game process. */
public final class UpdateActivity extends Activity {
    private static final String API = "https://api.github.com/repos/lemooz/MKVDCU-Android/releases?per_page=100";
    private static final String ASSET_PREFIX = "https://github.com/lemooz/MKVDCU-Android/releases/download/";
    private static final long MAX_APK = 256L * 1024 * 1024;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean cancelled;
    private volatile HttpURLConnection connection;
    private TextView status;
    private ProgressBar progress;
    private Button check, action, cancel;
    private CheckBox prereleases;
    private boolean busy, ready;
    private Release selected;
    private File apk;

    private static final class Release {
        String tag, notes, url, checksumUrl, filename;
        long size;
        boolean prerelease;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Atualizações");
        apk = new File(getFilesDir(), "updates/update.apk");
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(20 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this); scroll.addView(layout); setContentView(scroll);
        TextView heading = new TextView(this);
        heading.setText("MKVDCU " + BuildConfig.VERSION_NAME + " • " + (BuildConfig.DIAGNOSTIC_DEFAULT ? "Diagnóstico" : "Normal") +
                "\nFonte: github.com/lemooz/MKVDCU-Android");
        heading.setTextSize(18); layout.addView(heading);
        prereleases = new CheckBox(this);
        prereleases.setText("Incluir pré-releases (versões experimentais)");
        prereleases.setChecked(getPreferences(0).getBoolean("prereleases", true));
        prereleases.setOnCheckedChangeListener((button, checked) -> {
            getPreferences(0).edit().putBoolean("prereleases", checked).apply();
            selected = null; ready = false; action.setEnabled(false);
            status.setText("Canal alterado. Verifique as versões novamente.");
        });
        layout.addView(prereleases);
        status = new TextView(this); status.setText("Verifique se há uma versão mais recente. Seus jogos e configurações serão preservados.");
        status.setTextIsSelectable(true); layout.addView(status);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100); layout.addView(progress);
        check = new Button(this); check.setText("Verificar atualizações"); layout.addView(check);
        action = new Button(this); action.setText("Baixar atualização"); action.setEnabled(false); layout.addView(action);
        cancel = new Button(this); cancel.setText("Cancelar operação"); cancel.setVisibility(android.view.View.GONE); layout.addView(cancel);
        check.setOnClickListener(v -> checkReleases());
        action.setOnClickListener(v -> { if (ready) requestInstall(); else download(); });
        cancel.setOnClickListener(v -> { cancelled = true; HttpURLConnection c = connection; if (c != null) c.disconnect(); status.setText("Cancelando…"); });
        if (state != null && state.getBoolean("ready") && apk.isFile()) {
            ready = true; action.setText("Instalar atualização"); action.setEnabled(true);
            status.setText("Download concluído. Toque em instalar para verificar novamente e abrir o instalador.");
        }
    }

    private void running(boolean value) {
        busy = value; check.setEnabled(!value); prereleases.setEnabled(!value);
        action.setEnabled(!value && (selected != null || ready));
        cancel.setVisibility(value ? android.view.View.VISIBLE : android.view.View.GONE);
        if (!value) progress.setIndeterminate(false);
    }
    private void ui(Runnable task) { runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) task.run(); }); }
    private void fail(Exception error) {
        android.util.Log.w("MKVDCU", "[UPDATE] " + error.getMessage());
        ui(() -> { running(false); status.setText(cancelled ? "Operação cancelada." : "Não foi possível atualizar: " + error.getMessage()); });
    }
    private void checkReleases() {
        if (busy) return;
        cancelled = false; ready = false; selected = null; action.setText("Baixar atualização");
        boolean include = prereleases.isChecked();
        running(true); progress.setIndeterminate(true); status.setText("Consultando versões no GitHub…");
        worker.execute(() -> {
            try {
                JSONArray releases = new JSONArray(new String(fetch(API, 2 * 1024 * 1024), java.nio.charset.StandardCharsets.UTF_8));
                Release best = null;
                for (int i = 0; i < releases.length(); ++i) {
                    JSONObject r = releases.getJSONObject(i);
                    if (r.optBoolean("draft") || (!include && r.optBoolean("prerelease"))) continue;
                    String tag = r.optString("tag_name");
                    if (!validVersion(tag)) continue;
                    JSONArray assets = r.optJSONArray("assets"); if (assets == null) continue;
                    for (int j = 0; j < assets.length(); ++j) {
                        JSONObject asset = assets.getJSONObject(j);
                        String name = asset.optString("name");
                        if (!name.startsWith("mkvdcu-") || !name.endsWith(".apk") ||
                                name.toLowerCase(Locale.ROOT).contains("diagnostic") != BuildConfig.DIAGNOSTIC_DEFAULT) continue;
                        if (!asset.optString("state").equals("uploaded")) continue;
                        Release candidate = new Release(); candidate.tag = tag; candidate.filename = name;
                        candidate.size = asset.optLong("size"); candidate.url = asset.optString("browser_download_url");
                        if (candidate.size <= 0 || candidate.size > MAX_APK || !candidate.url.startsWith(ASSET_PREFIX)) continue;
                        candidate.notes = r.optString("body");
                        if (candidate.notes.length() > 8000) candidate.notes = candidate.notes.substring(0, 8000) + "…";
                        candidate.prerelease = r.optBoolean("prerelease");
                        for (int k = 0; k < assets.length(); ++k) {
                            JSONObject hash = assets.getJSONObject(k);
                            if (hash.optString("name").equals(name + ".sha256") && hash.optString("state").equals("uploaded") &&
                                    hash.optString("browser_download_url").startsWith(ASSET_PREFIX))
                                candidate.checksumUrl = hash.optString("browser_download_url");
                        }
                        // A published checksum is part of this project's release contract.
                        if (candidate.checksumUrl == null) continue;
                        if (best == null || compareVersions(tag, best.tag) > 0) best = candidate;
                    }
                }
                if (cancelled) throw new IOException("Cancelado");
                Release result = best;
                ui(() -> {
                    selected = result != null && compareVersions(result.tag, BuildConfig.VERSION_NAME) > 0 ? result : null;
                    running(false); progress.setProgress(0);
                    if (result == null) status.setText("Nenhum APK compatível com checksum publicado neste canal. Ative pré-releases para incluir versões experimentais.");
                    else if (selected == null) status.setText("Você já tem a versão mais recente ou uma versão local mais nova.\nInstalada: " + BuildConfig.VERSION_NAME + "\nGitHub: " + result.tag + "\nNenhum downgrade será instalado.");
                    else status.setText("Disponível: " + result.tag + (result.prerelease ? " (pré-release)" : "") +
                            "\nDownload: " + String.format(Locale.ROOT, "%.1f MiB", result.size / 1048576.0) + "\n\n" + result.notes);
                    android.util.Log.i("MKVDCU", "[UPDATE] installed=" + BuildConfig.VERSION_NAME + " remote=" + (result == null ? "none" : result.tag) + " newer=" + (selected != null));
                });
            } catch (Exception error) { fail(error); }
        });
    }

    // SemVer tags only. The APK's signed versionCode remains authoritative at install time.
    private static boolean validVersion(String s) { return s.matches("[vV]?[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?"); }
    static int compareVersions(String a, String b) {
        String[] x = a.replaceFirst("^[vV]", "").split("\\+", 2)[0].split("-", 2);
        String[] y = b.replaceFirst("^[vV]", "").split("\\+", 2)[0].split("-", 2);
        String[] xc = x[0].split("\\."), yc = y[0].split("\\.");
        for (int i = 0; i < 3; ++i) {
            int c = new java.math.BigInteger(xc[i]).compareTo(new java.math.BigInteger(yc[i])); if (c != 0) return c;
        }
        if (x.length != y.length) return x.length == 1 ? 1 : -1;
        if (x.length == 1) return 0;
        String[] xp = x[1].split("\\."), yp = y[1].split("\\.");
        for (int i = 0; i < Math.min(xp.length, yp.length); ++i) {
            String l = xp[i], r = yp[i]; boolean ln = l.matches("[0-9]+"), rn = r.matches("[0-9]+");
            int c = ln && rn ? new java.math.BigInteger(l).compareTo(new java.math.BigInteger(r)) : ln != rn ? (ln ? -1 : 1) : l.compareTo(r);
            if (c != 0) return c;
        }
        return Integer.compare(xp.length, yp.length);
    }

    private void download() {
        if (busy || selected == null) return;
        Release release = selected;
        new AlertDialog.Builder(this).setTitle("Baixar " + release.tag + "?")
                .setMessage("O download usa sua conexão de internet. A instalação será confirmada pelo Android. Salve e encerre uma partida antes de instalar.")
                .setNegativeButton("Voltar", null).setPositiveButton("Baixar", (d, w) -> beginDownload(release)).show();
    }
    private void beginDownload(Release release) {
        cancelled = false; running(true); progress.setIndeterminate(false); progress.setProgress(0);
        status.setText("Baixando e verificando atualização…");
        worker.execute(() -> {
            File part = new File(apk.getParentFile(), "update.part");
            try {
                if (!apk.getParentFile().isDirectory() && !apk.getParentFile().mkdirs()) throw new IOException("Sem acesso ao armazenamento privado");
                if (getFilesDir().getUsableSpace() < release.size + 64L * 1024 * 1024) throw new IOException("Espaço insuficiente para baixar o APK");
                String hashes = new String(fetch(release.checksumUrl, 16384), java.nio.charset.StandardCharsets.UTF_8);
                String expected = null;
                for (String line : hashes.split("\\r?\\n")) {
                    Matcher m = Pattern.compile("^([a-fA-F0-9]{64})(?:[ \\t]+\\*?(.+))?$").matcher(line.trim());
                    if (m.matches() && (m.group(2) == null || m.group(2).equals(release.filename))) { expected = m.group(1).toLowerCase(Locale.ROOT); break; }
                }
                if (expected == null) throw new IOException("Checksum publicado inválido");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long total = 0, last = 0, deadline = android.os.SystemClock.elapsedRealtime() + 30 * 60 * 1000;
                HttpURLConnection c = open(release.url);
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                    byte[] buf = new byte[65536]; int n;
                    while ((n = in.read(buf)) != -1) {
                        if (cancelled || android.os.SystemClock.elapsedRealtime() > deadline) throw new IOException("Download interrompido");
                        total += n; if (total > release.size || total > MAX_APK) throw new IOException("Tamanho inesperado do APK");
                        out.write(buf, 0, n); digest.update(buf, 0, n);
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (now - last > 200) { last = now; int pct = (int)(total * 100 / release.size); ui(() -> progress.setProgress(pct)); }
                    }
                    out.getFD().sync();
                } finally { c.disconnect(); connection = null; }
                if (cancelled) throw new IOException("Cancelado");
                if (total != release.size) throw new IOException("Download incompleto");
                StringBuilder actual = new StringBuilder(); for (byte value : digest.digest()) actual.append(String.format(Locale.ROOT, "%02x", value & 255));
                if (!actual.toString().equals(expected)) throw new IOException("Checksum SHA-256 não confere");
                validateApk(part);
                if (apk.exists() && !apk.delete()) throw new IOException("Não foi possível substituir o download anterior");
                if (!part.renameTo(apk)) throw new IOException("Não foi possível finalizar o download");
                ui(() -> { ready = true; running(false); progress.setProgress(100); action.setText("Instalar atualização"); status.setText("APK verificado: SHA-256, assinatura, versão e bibliotecas ARM64.\nToque em instalar para continuar."); });
            } catch (Exception error) { part.delete(); fail(error); }
        });
    }

    private HttpURLConnection open(String address) throws IOException {
        URL url = new URL(address);
        for (int i = 0; i < 6; ++i) {
            String host = url.getHost().toLowerCase(Locale.ROOT);
            if (!url.getProtocol().equals("https") || url.getUserInfo() != null || (url.getPort() != -1 && url.getPort() != 443) ||
                    !(host.equals("api.github.com") || host.equals("github.com") || host.equals("release-assets.githubusercontent.com") || host.equals("objects.githubusercontent.com")))
                throw new IOException("Destino de download não permitido");
            if (cancelled) throw new IOException("Cancelado");
            HttpURLConnection c = (HttpURLConnection)url.openConnection(); connection = c;
            c.setConnectTimeout(15000); c.setReadTimeout(30000); c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "MKVDCU-Android/" + BuildConfig.VERSION_NAME);
            c.setRequestProperty("Accept", host.equals("api.github.com") ? "application/vnd.github+json" : "application/octet-stream");
            c.setRequestProperty("Accept-Encoding", "identity");
            int code;
            try { code = c.getResponseCode(); } catch (IOException e) { c.disconnect(); throw e; }
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = c.getHeaderField("Location"); c.disconnect();
                if (location == null) throw new IOException("Redirecionamento inválido");
                url = new URL(url, location); continue;
            }
            if (code != 200) { c.disconnect(); throw new IOException(code == 403 || code == 429 ? "Limite do GitHub atingido. Tente mais tarde." : "Servidor retornou HTTP " + code); }
            return c;
        }
        throw new IOException("Redirecionamentos em excesso");
    }
    private byte[] fetch(String url, int limit) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n; long deadline = android.os.SystemClock.elapsedRealtime() + 60000;
            while ((n = in.read(buffer)) != -1) {
                if (cancelled || android.os.SystemClock.elapsedRealtime() > deadline) throw new IOException("Consulta interrompida");
                if (out.size() + n > limit) throw new IOException("Resposta do servidor muito grande");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally { c.disconnect(); connection = null; }
    }

    private void validateApk(File file) throws Exception {
        PackageManager pm = getPackageManager();
        PackageInfo current = pm.getPackageInfo(getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo next = pm.getPackageArchiveInfo(file.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (next == null || !getPackageName().equals(next.packageName)) throw new IOException("APK não pertence ao MKVDCU");
        if (next.getLongVersionCode() <= current.getLongVersionCode()) throw new IOException("APK não é mais novo que a versão instalada");
        if (next.applicationInfo == null || next.applicationInfo.minSdkVersion > android.os.Build.VERSION.SDK_INT) throw new IOException("APK requer uma versão mais recente do Android");
        if (((next.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) != BuildConfig.DIAGNOSTIC_DEFAULT)
            throw new IOException("APK pertence a outra variante (normal/diagnóstica)");
        if (next.splitNames != null && next.splitNames.length != 0) throw new IOException("APK dividido não é suportado");
        if (next.signingInfo == null || current.signingInfo == null) throw new IOException("Assinatura ausente");
        Set<Signature> installed = new HashSet<>(Arrays.asList(current.signingInfo.getApkContentsSigners()));
        Set<Signature> incoming = new HashSet<>(Arrays.asList(next.signingInfo.getApkContentsSigners()));
        if (installed.isEmpty() || !installed.equals(incoming)) throw new IOException("Assinatura diferente da versão instalada");
        // Require the real port, not an old JNI/probe shell accidentally attached to a release.
        try (ZipFile zip = new ZipFile(file)) {
            for (String lib : new String[]{"libmkvdcu.so", "librexruntime.so", "librexgpu-xenos.so"}) {
                java.util.zip.ZipEntry entry = zip.getEntry("lib/arm64-v8a/" + lib);
                if (entry == null || entry.getSize() < 1024 * 1024) throw new IOException("Biblioteca ARM64 do jogo ausente: " + lib);
                try (DataInputStream in = new DataInputStream(zip.getInputStream(entry))) {
                    byte[] elf = new byte[20]; in.readFully(elf);
                    if (elf[0] != 0x7f || elf[1] != 'E' || elf[2] != 'L' || elf[3] != 'F' ||
                            elf[4] != 2 || elf[5] != 1 || (elf[18] & 255) != 183 || elf[19] != 0)
                        throw new IOException("Biblioteca não é ELF ARM64: " + lib);
                }
            }
        }
    }
    private void requestInstall() {
        if (busy || !ready) return;
        if (!getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this).setTitle("Permitir instalação")
                    .setMessage("Autorize o MKVDCU a instalar atualizações nesta tela do Android. Ao voltar, toque em Instalar atualização.")
                    .setNegativeButton("Voltar", null).setPositiveButton("Abrir configurações", (d, w) -> {
                        try { startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); }
                        catch (Exception e) { status.setText("Abra as configurações do Android e permita instalar apps por esta origem."); }
                    }).show();
            return;
        }
        running(true); status.setText("Verificando APK antes da instalação…");
        worker.execute(() -> {
            try {
                validateApk(apk);
                ui(() -> {
                    running(false);
                    try {
                        Uri uri = Uri.parse("content://" + getPackageName() + ".updates/update.apk");
                        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        intent.setClipData(android.content.ClipData.newRawUri("Atualização MKVDCU", uri));
                        startActivity(intent);
                        status.setText("Confirme a atualização no instalador do Android. Seus dados serão preservados.");
                    } catch (Exception error) { status.setText("Não foi possível abrir o instalador: " + error.getMessage()); }
                });
            } catch (Exception error) { ready = false; fail(error); }
        });
    }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putBoolean("ready", ready); }
    @Override protected void onDestroy() {
        cancelled = true; HttpURLConnection c = connection; if (c != null) c.disconnect();
        worker.shutdownNow(); super.onDestroy();
    }
}
