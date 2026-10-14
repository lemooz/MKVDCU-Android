package org.mkvdcu.android;
import org.json.JSONObject;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_PICK_ISO = 1001;
    private static final int REQ_PICK_DRIVER = 1002;
    private static final int REQ_PROBE_DRIVER = 1003;
    private static final int REQ_EXPORT_PERFORMANCE = 1004;
    private static final int REQ_EXPORT_FRAMES = 1005;
    private final Handler driverHandler = new Handler(Looper.getMainLooper());
    private int probePid;
    private String probePhase = "Inicialização";

    private Prefs prefs;
    private TextView statusView;
    private TextView detailsView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = new Prefs(this);
        statusView = findViewById(R.id.game_status);
        detailsView = findViewById(R.id.game_details);

        findViewById(R.id.btn_pick).setOnClickListener(v -> pickIso());
        findViewById(R.id.btn_play).setOnClickListener(v -> play());
        View btnControls = findViewById(R.id.btn_controls);
        if (btnControls != null) btnControls.setOnClickListener(v -> startActivity(new Intent(this, GamepadEditorActivity.class)));
        findViewById(R.id.btn_settings).setOnClickListener(v -> showSettings());
        findViewById(R.id.btn_about).setOnClickListener(v -> showAbout());

        refresh();
    }

    private void pickIso() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "application/octet-stream",
                "application/x-iso9660-image",
                "application/x-raw-disk-image"
        });
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_PICK_ISO);
        } catch (Exception e) {
            Toast.makeText(this, "Nenhum seletor de arquivos disponível.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode == REQ_EXPORT_PERFORMANCE || requestCode == REQ_EXPORT_FRAMES) && resultCode == RESULT_OK && data != null && data.getData() != null) {
            String source = requestCode == REQ_EXPORT_FRAMES ? "last-frame-times.csv" : "last-performance.csv";
            try (java.io.InputStream in = new java.io.FileInputStream(new java.io.File(getFilesDir(), source));
                 java.io.OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                if (out == null) throw new java.io.IOException("Não foi possível abrir o destino");
                byte[] buffer = new byte[65536]; int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                Toast.makeText(this, "Métricas exportadas", Toast.LENGTH_SHORT).show();
            } catch (Exception error) { showDriverError(error.getMessage()); }
            return;
        }
        if (requestCode == REQ_PICK_DRIVER && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            Toast.makeText(this, "Importando driver…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try {
                    DriverStore.Driver driver = new DriverStore(this).importUri(uri);
                    runOnUiThread(() -> showDriver(driver.id));
                } catch (Exception error) {
                    runOnUiThread(() -> showDriverError(error.getMessage()));
                }
            }, "driver-import").start();
            return;
        }
        if (requestCode == REQ_PROBE_DRIVER) {
            driverHandler.removeCallbacksAndMessages(null);
            // The result IPC has arrived. Reap the old probe now so an
            // immediate second verification cannot reuse its Vulkan process.
            if (probePid != 0 && probePid != android.os.Process.myPid()) android.os.Process.killProcess(probePid);
            probePid = 0;
            String id = prefs.getPendingProbe();
            prefs.setPendingProbe("");
            if (id.isEmpty()) return;
            String report = resultCode == RESULT_OK && data != null ? data.getStringExtra("driver_report") : null;
            if (report == null) {
                JSONObject failure = new JSONObject();
                try { failure.put("ok", false).put("message", "Processo do driver encerrou durante a verificação. Última etapa: " + probePhase); }
                catch (Exception ignored) { }
                report = failure.toString();
            }
            try { new DriverStore(this).saveReport(id, report); showDriver(id); }
            catch (Exception error) { showDriverError(error.getMessage()); }
            return;
        }
        if (requestCode != REQ_PICK_ISO || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        prefs.setIsoUri(uri.toString());
        prefs.setInstalled(false);
        statusView.setText("VALIDANDO IMAGEM…");
        statusView.setTextColor(getColor(R.color.status_waiting));
        detailsView.setText(uri.toString());

        new Thread(() -> {
            final IsoValidator.Result result = IsoValidator.validate(this, uri);
            runOnUiThread(() -> {
                if (result.ok) {
                    prefs.setInstalled(true);
                    prefs.setXexSha256(result.xexSha256);
                    statusView.setText("PRONTO PARA COMBATE");
                    statusView.setTextColor(getColor(R.color.status_ready));
                    detailsView.setText(result.summary());
                } else {
                    prefs.setInstalled(false);
                    statusView.setText("ARQUIVO INCOMPATÍVEL");
                    statusView.setTextColor(getColor(R.color.status_error));
                    detailsView.setText(result.message);
                }
                refresh();
            });
        }, "iso-validate").start();
    }

    private void play() {
        if (!prefs.isInstalled()) {
            Toast.makeText(this, "Jogo não instalado. Selecione a ISO primeiro.",
                    Toast.LENGTH_LONG).show();
            pickIso();
            return;
        }
        if (!new java.io.File(getApplicationInfo().nativeLibraryDir, "libmkvdcu.so").isFile()) {
            Toast.makeText(this,
                    "Runtime nativo (libmkvdcu.so) não incluído nesta build.\n"
                            + "A ISO foi validada; o jogo requer o módulo nativo ARM64.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        // The native game activity is provided by the native runtime build.
        try {
            Intent intent = new Intent();
            intent.setClassName(this, "org.mkvdcu.android.GameActivity");
            intent.putExtra("iso_uri", prefs.getIsoUri());
            DriverStore.Driver driver = new DriverStore(this).get(prefs.getDriverId());
            if (!driver.usable()) {
                prefs.setDriverId("system");
                showDriverError("Driver selecionado indisponível: " + driver.reason());
                return;
            }
            intent.putExtra("driver_id", driver.id);
            intent.putExtra("diagnostics", prefs.diagnostics());
            new GraphicsSettings(this).attach(intent);
            // Developer-only A/B override; a fresh game process receives it.
            if (BuildConfig.DEBUG && getIntent().hasExtra("benchmark_scissor_area"))
                intent.putExtra("benchmark_scissor_area", getIntent().getBooleanExtra("benchmark_scissor_area", false));
            if (BuildConfig.DEBUG && getIntent().hasExtra("benchmark_spirv_optimize"))
                intent.putExtra("benchmark_spirv_optimize", getIntent().getBooleanExtra("benchmark_spirv_optimize", false));
            prefs.setPendingGameDriver(driver.kind.equals("system") ? "" : driver.id);
            intent.putExtra("session_receiver", new ResultReceiver(driverHandler) {
                @Override protected void onReceiveResult(int code, Bundle data) {
                    if (code == 1) prefs.setPendingGameDriver("");
                }
            });
            android.app.ActivityManager manager = (android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE);
            java.util.List<android.app.ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
            if (processes != null) for (android.app.ActivityManager.RunningAppProcessInfo process : processes)
                if (process.uid == android.os.Process.myUid() && process.processName.equals(getPackageName() + ":game"))
                    android.os.Process.killProcess(process.pid);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível iniciar o jogo: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showSettings() {
        String[] items = {"Drivers Vulkan", "Importar driver ZIP / .adpkg.zip",
                "Diagnóstico: " + (prefs.diagnostics() ? "ativado" : "desativado"), "Gráficos e desempenho",
                "Exportar métricas da última sessão", "Exportar frametimes da última sessão", "Controle na tela — personalizar"};
        new AlertDialog.Builder(this)
                .setTitle("Configurações")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) showDrivers();
                    if (which == 1) {
                        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                                .addCategory(Intent.CATEGORY_OPENABLE);
                        startActivityForResult(pick, REQ_PICK_DRIVER);
                    }
                    if (which == 2) { prefs.setDiagnostics(!prefs.diagnostics()); showSettings(); }
                    if (which == 3) showGraphicsSettings();
                    if (which == 6) startActivity(new Intent(this,GamepadEditorActivity.class));
                    if (which == 4 || which == 5) {
                        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/csv")
                                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,
                                    which == 5 ? "mkvdcu-frame-times.csv" : "mkvdcu-performance.csv");
                        startActivityForResult(save, which == 5 ? REQ_EXPORT_FRAMES : REQ_EXPORT_PERFORMANCE);
                    }
                })
                .setPositiveButton("Fechar", null)
                .show();
    }

    private void showGraphicsSettings() {
        GraphicsSettings graphics = new GraphicsSettings(this);
        int filterIndex = 0;
        for (int i = 0; i < GraphicsSettings.FILTERS.length; i++)
            if (GraphicsSettings.FILTERS[i] == graphics.filter()) filterIndex = i;
        String[] items = {"Resolução interna: 1× (nativa)",
            "Filtragem anisotrópica: " + GraphicsSettings.FILTER_NAMES[filterIndex],
            "Cache de texturas: " + graphics.cacheMb() + " MiB",
            "SPIR-V SSA experimental: " + (graphics.ssa() ? "ativado" : "desativado"),
            "Área de renderização experimental: " + (graphics.renderArea() ? "ativada" : "desativada"),
            "Desempenho sustentado Android: " + (graphics.sustained() ? "ativado" : "desativado"),
            "Restaurar padrões", "Detalhe de texturas experimental: " +
                (graphics.mipDrop() == 0 ? "original" : "mínimo mip +" + graphics.mipDrop()),
            "Modo de vídeo solicitado: " + GraphicsSettings.VIDEO_NAMES[graphics.videoMode()],
            "Agrupar submissões experimental: " + (graphics.batch() ? "ativado" : "desativado"),
            "Medição GPU: " + (graphics.gpuTiming() == 0 ? "desativada" : "uma a cada " + graphics.gpuTiming() + " submissões"),
            "Perfil GPU por etapa: " + (graphics.gpuStages() ? "amostrado 1/64" : "desativado"),
            "Compiladores de pipelines: " + (graphics.pipelineThreads() < 0 ? "automático" : graphics.pipelineThreads()),
            "Turnip — modo de renderização: " + GraphicsSettings.TURNIP_MODES[graphics.turnipMode()]};
        new AlertDialog.Builder(this).setTitle("Gráficos — próxima sessão")
            .setItems(items, (dialog, which) -> {
                if (which == 0) new AlertDialog.Builder(this).setTitle("Resolução interna")
                    .setMessage("1× mantém a resolução solicitada pelo jogo. Redução para 0,75× ou 0,5× ainda exige suporte na EDRAM e nos resolves; não está disponível nesta versão.")
                    .setPositiveButton("OK", (d, w) -> showGraphicsSettings()).show();
                if (which == 1) {
                    int selected = 0;
                    for (int i = 0; i < GraphicsSettings.FILTERS.length; i++)
                        if (GraphicsSettings.FILTERS[i] == graphics.filter()) selected = i;
                    new AlertDialog.Builder(this).setTitle("Filtragem anisotrópica")
                        .setSingleChoiceItems(GraphicsSettings.FILTER_NAMES, selected, (d, w) -> {
                            graphics.setFilter(GraphicsSettings.FILTERS[w]); d.dismiss(); showGraphicsSettings();
                        }).setNegativeButton("Cancelar", null).show();
                }
                if (which == 2) {
                    String[] labels = new String[GraphicsSettings.CACHE_MB.length]; int selected = 0;
                    for (int i = 0; i < labels.length; i++) {
                        labels[i] = GraphicsSettings.CACHE_MB[i] + " MiB";
                        if (GraphicsSettings.CACHE_MB[i] == graphics.cacheMb()) selected = i;
                    }
                    new AlertDialog.Builder(this).setTitle("Cache de texturas (limite suave)")
                        .setSingleChoiceItems(labels, selected, (d, w) -> {
                            graphics.setCacheMb(GraphicsSettings.CACHE_MB[w]); d.dismiss(); showGraphicsSettings();
                        }).setNegativeButton("Cancelar", null).show();
                }
                if (which == 3 || which == 4) {
                    boolean currently = which == 3 ? graphics.ssa() : graphics.renderArea();
                    Runnable change = () -> {
                        if (which == 3) graphics.setSsa(!currently); else graphics.setRenderArea(!currently);
                        showGraphicsSettings();
                    };
                    if (currently) change.run();
                    else new AlertDialog.Builder(this).setTitle("Opção experimental")
                        .setMessage(which == 3 ? "Otimiza SPIR-V preservando a aritmética. Ainda não há ganho consistente medido na Mali. Requer nova sessão."
                            : "Une as áreas dos draws antes de enviá-los à GPU para reduzir LOAD/STORE. Esta revisão ainda precisa de comparação visual. Deixe desativada para comparar defeitos gráficos.")
                        .setPositiveButton("Ativar", (d, w) -> change.run()).setNegativeButton("Cancelar", null).show();
                }
                if (which == 5) {
                    android.os.PowerManager power = getSystemService(android.os.PowerManager.class);
                    if (power != null && power.isSustainedPerformanceModeSupported()) graphics.setSustained(!graphics.sustained());
                    else Toast.makeText(this, "Este Android não oferece desempenho sustentado", Toast.LENGTH_LONG).show();
                    showGraphicsSettings();
                }
                if (which == 6) { graphics.reset(); showGraphicsSettings(); }
                if (which == 7) new AlertDialog.Builder(this).setTitle("Detalhe de texturas — próxima sessão")
                    .setSingleChoiceItems(new String[]{"Original", "Mínimo mip +1 (menos detalhe)", "Mínimo mip +2 (menos detalhe)"}, graphics.mipDrop(), (d, w) -> {
                        Runnable change = () -> { graphics.setMipDrop(w); d.dismiss(); showGraphicsSettings(); };
                        if (w == 0) change.run();
                        else new AlertDialog.Builder(this).setTitle("Reduzir detalhe")
                            .setMessage("Usa mipmaps menores de texturas DXT 2D com cadeia de mips e filtragem linear. Pode borrar materiais e máscaras. Não reduz a resolução interna nem o tamanho dos uploads. Ganho de FPS ainda precisa ser medido.")
                            .setPositiveButton("Aplicar", (confirm, button) -> change.run()).setNegativeButton("Cancelar", null).show();
                    }).setNegativeButton("Cancelar", null).show();
                if (which == 8) new AlertDialog.Builder(this).setTitle("Modo de vídeo — próxima sessão")
                    .setMessage("Solicita uma resolução menor ao jogo. O jogo pode manter alvos internos fixos: confira a resolução produzida no overlay. Não altera a velocidade do relógio do jogo.")
                    .setSingleChoiceItems(GraphicsSettings.VIDEO_NAMES, graphics.videoMode(), (d, w) -> {
                        graphics.setVideoMode(w); d.dismiss(); showGraphicsSettings();
                    }).setNegativeButton("Cancelar", null).show();
                if (which == 9) {
                    if (graphics.batch()) { graphics.setBatch(false); showGraphicsSettings(); }
                    else new AlertDialog.Builder(this).setTitle("Agrupar submissões")
                        .setMessage("Reduz submissões no fim de buffers PM4, mantendo envio nos swaps e esperas necessárias. Experimental: desative se houver parada ou diferença visual.")
                        .setPositiveButton("Ativar", (d, w) -> { graphics.setBatch(true); showGraphicsSettings(); })
                        .setNegativeButton("Cancelar", null).show();
                }
                if (which == 10) {
                    int selected = 0;
                    for (int i = 0; i < GraphicsSettings.GPU_TIMING.length; i++)
                        if (GraphicsSettings.GPU_TIMING[i] == graphics.gpuTiming()) selected = i;
                    new AlertDialog.Builder(this).setTitle("Medição de tempo GPU")
                        .setSingleChoiceItems(new String[]{"Amostrada 1/64 (padrão)", "Desativada (menor custo)", "Toda submissão (diagnóstico)"}, selected, (d, w) -> {
                            graphics.setGpuTiming(GraphicsSettings.GPU_TIMING[w]); d.dismiss(); showGraphicsSettings();
                        }).setNegativeButton("Cancelar", null).show();
                }
                if (which == 11) {
                    graphics.setGpuStages(!graphics.gpuStages());
                    showGraphicsSettings();
                }
                if (which == 12) {
                    int selected = 0;
                    for (int i = 0; i < GraphicsSettings.PIPELINE_THREADS.length; i++)
                        if (GraphicsSettings.PIPELINE_THREADS[i] == graphics.pipelineThreads()) selected = i;
                    new AlertDialog.Builder(this).setTitle("Compilação de pipelines")
                        .setMessage("Menos compiladores podem reduzir a disputa de CPU durante o aquecimento do cache, mas aumentar o tempo de compilação. Não omite draws nem reduz a qualidade. Compare na mesma cena, com cache frio e aquecido.")
                        .setSingleChoiceItems(new String[]{"Automático (padrão)", "1 compilador", "2 compiladores", "4 compiladores"}, selected, (d, w) -> {
                            graphics.setPipelineThreads(GraphicsSettings.PIPELINE_THREADS[w]); d.dismiss(); showGraphicsSettings();
                        }).setNegativeButton("Cancelar", null).show();
                }
                if (which == 13) new AlertDialog.Builder(this).setTitle("Turnip — próxima sessão")
                    .setMessage("Somente para driver Turnip importado. Automático mantém a escolha do driver; Sysmem evita o binning em tiles; GMEM força tiles. O melhor modo depende da GPU e da cena. Exige verificar novamente o pacote no gerenciador de drivers. Não afeta o driver do sistema nem PanVK.")
                    .setSingleChoiceItems(GraphicsSettings.TURNIP_MODES, graphics.turnipMode(), (d, w) -> {
                        graphics.setTurnipMode(w); d.dismiss(); showGraphicsSettings();
                    }).setNegativeButton("Cancelar", null).show();
            }).setPositiveButton("Fechar", null).show();
    }

    private void showAbout() {
        StringBuilder sb = new StringBuilder();
        sb.append("MKVDCU — Mortal Kombat vs. DC Universe\n");
        sb.append("Port nativo Android ARM64\n\n");
        sb.append("Motor: ReXGlue (recompilação estática)\n");
        sb.append("Renderer: Vulkan\n");
        sb.append("Arquitetura: arm64-v8a\n\n");
        sb.append("Runtime nativo: ")
                .append(new java.io.File(getApplicationInfo().nativeLibraryDir, "libmkvdcu.so").isFile() ? "presente" : "ausente").append('\n');
        sb.append("Dispositivo: ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append('\n');
        sb.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n\n");
        sb.append("Este aplicativo não inclui arquivos do jogo.\n");
        sb.append("Utilize uma cópia legalmente obtida de Mortal Kombat vs. DC Universe "
                + "para Xbox 360.");

        new AlertDialog.Builder(this)
                .setTitle("Sobre")
                .setMessage(sb.toString())
                .setPositiveButton("Fechar", null)
                .show();
    }

    private void refresh() {
        DriverStore.Driver currentDriver = new DriverStore(this).get(prefs.getDriverId());
        String driverLabel = currentDriver != null ? currentDriver.name : "Sistema";
        if (prefs.isInstalled()) {
            statusView.setText("PRONTO PARA COMBATE");
            statusView.setTextColor(getColor(R.color.status_ready));
            String uri = prefs.getIsoUri();
            String sha = prefs.getXexSha256();
            detailsView.setText("ISO: " + uri + (sha == null || sha.isEmpty() ? ""
                    : "\nXEX: " + sha.substring(0, Math.min(16, sha.length())) + "…")
                    + "\nDriver Vulkan: " + driverLabel);
        } else {
            statusView.setText("AGUARDANDO ISO");
            statusView.setTextColor(getColor(R.color.status_waiting));
            detailsView.setText("Selecione a imagem ISO de Xbox 360 para iniciar o combate."
                    + "\nDriver Vulkan: " + driverLabel);
        }
        View playButton = findViewById(R.id.btn_play);
        playButton.setEnabled(prefs.isInstalled());
    }

    private void showDriverError(String message) {
        new AlertDialog.Builder(this).setTitle("Driver Vulkan").setMessage(message)
                .setPositiveButton("Fechar", null).show();
    }
    private void showDrivers() {
        java.util.List<DriverStore.Driver> drivers = new DriverStore(this).list();
        String[] items = new String[drivers.size()];
        for (int i = 0; i < items.length; i++) {
            DriverStore.Driver d = drivers.get(i);
            items[i] = (d.id.equals(prefs.getDriverId()) ? "✓ " : "") + d.name +
                    (d.kind.equals("system") ? "" : " " + d.version + "\n" + d.reason());
        }
        new AlertDialog.Builder(this).setTitle("Drivers Vulkan")
                .setItems(items, (dialog, which) -> showDriver(drivers.get(which).id))
                .setNegativeButton("Fechar", null).show();
    }
    private void showDriver(String id) {
        DriverStore.Driver d = new DriverStore(this).get(id);
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(d.name)
                .setMessage(d.kind.equals("system") ? "Driver Android original. Seleção padrão.\n" +
                        (d.report == null ? "" : d.reason()) :
                        d.version + "\n" + (d.kind.equals("panvk") ? "PanVK experimental\n" :
                            d.kind.equals("adreno") ? "Adreno personalizado experimental\n" : "Turnip\n") + d.requirements() + d.reason());
        if (d.usable()) builder.setPositiveButton("Selecionar", (dialog, which) -> {
            prefs.setDriverId(id);
            Toast.makeText(this, "Driver será usado na próxima sessão do jogo", Toast.LENGTH_LONG).show();
        });
        else if (d.minApi <= Build.VERSION.SDK_INT && d.packageIssue.isEmpty())
            builder.setPositiveButton("Verificar", (dialog, which) -> verifyDriver(id));
        if (!d.kind.equals("system")) builder.setNeutralButton("Excluir", (dialog, which) -> {
            if (prefs.getDriverId().equals(id)) prefs.setDriverId("system");
            new DriverStore(this).remove(id); showDrivers();
        });
        else builder.setNeutralButton("Verificar", (dialog, which) -> verifyDriver("system"));
        builder.setNegativeButton("Fechar", null).show();
    }
    private void verifyDriver(String id) {
        prefs.setPendingProbe(id);
        probePid = 0;
        probePhase = "Inicialização";
        ResultReceiver receiver = new ResultReceiver(driverHandler) {
            @Override protected void onReceiveResult(int code, Bundle data) {
                if (code == 0) probePid = data.getInt("pid");
                else if (code == 1) probePhase = data.getString("phase", probePhase);
            }
        };
        Intent probe = new Intent(this, DriverProbeActivity.class).putExtra("driver_id", id)
                .putExtra("probe_receiver", receiver);
        new GraphicsSettings(this).attach(probe);
        startActivityForResult(probe, REQ_PROBE_DRIVER);
        driverHandler.postDelayed(() -> {
            if (probePid != 0 && probePid != android.os.Process.myPid()) android.os.Process.killProcess(probePid);
        }, 35000);
    }
    @Override protected void onResume() {
        super.onResume();
        if (prefs == null) return;
        String pending = prefs.getPendingGameDriver();
        if (!pending.isEmpty()) {
            prefs.setPendingGameDriver("");
            prefs.setDriverId("system");
            showDriverError("A sessão do driver personalizado encerrou antes dos primeiros frames. Driver do sistema restaurado.");
        }
    }
}
