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
        // Diagnostic APK only: reuse the same validated import/probe/launch
        // paths for reproducible ADB runs. These controls are absent in release.
        if (BuildConfig.DEBUG) {
            String probe = getIntent().getStringExtra("debug_probe_driver");
            if (getIntent().getBooleanExtra("debug_import_driver", false)) {
                new Thread(() -> {
                    try {
                        DriverStore.Driver d = new DriverStore(this).importUri(Uri.fromFile(new java.io.File(getCacheDir(), "adb-driver.zip")));
                        android.util.Log.i("MKVDCU", "[ADB_DRIVER_IMPORT] id=" + d.id + " " + d.name);
                        runOnUiThread(() -> verifyDriver(d.id));
                    } catch (Exception error) { runOnUiThread(() -> showDriverError(error.toString())); }
                }, "ADB driver import").start();
            } else if (probe != null) driverHandler.post(() -> verifyDriver(probe));
            else if (getIntent().getBooleanExtra("debug_launch", false)) driverHandler.post(this::play);
        }
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
            String debugDriver = BuildConfig.DEBUG ? getIntent().getStringExtra("debug_driver_id") : null;
            DriverStore.Driver driver = new DriverStore(this).get(debugDriver != null ? debugDriver : prefs.getDriverId());
            if (!driver.usable()) {
                prefs.setDriverId("system");
                showDriverError("Driver selecionado indisponível: " + driver.reason());
                return;
            }
            intent.putExtra("driver_id", driver.id);
            intent.putExtra("diagnostics", prefs.diagnostics());
            intent.putExtra("perf_overlay_enabled", prefs.isPerformanceOverlayEnabled());
            new GraphicsSettings(this).attach(intent);
            if (BuildConfig.DEBUG) {
                // Only explicit benchmark extras override the saved snapshot.
                for (String key : new String[]{"graphics_present_queue", "graphics_gpu_stages", "graphics_render_area", "graphics_ssa"})
                    if (getIntent().hasExtra(key)) intent.putExtra(key, getIntent().getBooleanExtra(key, false));
                if (getIntent().hasExtra("graphics_scale_percent")) intent.putExtra("graphics_scale_percent", getIntent().getIntExtra("graphics_scale_percent",100));
                if (getIntent().hasExtra("diagnostics")) intent.putExtra("diagnostics",getIntent().getBooleanExtra("diagnostics",false));
            }
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
        GraphicsSettings graphics = new GraphicsSettings(this);
        String[] items = {"Drivers Vulkan", "Importar driver ZIP / .adpkg.zip",
                "Overlay de desempenho (MangoHUD): " + (prefs.isPerformanceOverlayEnabled() ? "ativado" : "desativado"),
                "Diagnóstico nativo: " + (prefs.diagnostics() ? "ativado" : "desativado"),
                "★ Predefinição gráfica: " + graphics.presetName(),
                "Gráficos e desempenho (avançado)",
                "Exportar métricas da última sessão", "Exportar frametimes da última sessão", "Controle na tela — personalizar", "Atualizações pelo GitHub"};
        new AlertDialog.Builder(this)
                .setTitle("Configurações")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) showDrivers();
                    if (which == 1) {
                        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                                .addCategory(Intent.CATEGORY_OPENABLE);
                        startActivityForResult(pick, REQ_PICK_DRIVER);
                    }
                    if (which == 2) {
                        prefs.setPerformanceOverlayEnabled(!prefs.isPerformanceOverlayEnabled());
                        showSettings();
                    }
                    if (which == 3) { prefs.setDiagnostics(!prefs.diagnostics()); showSettings(); }
                    if (which == 4) showPresetDialog();
                    if (which == 5) showGraphicsSettings();
                    if (which == 9) startActivity(new Intent(this, UpdateActivity.class));
                    if (which == 8) startActivity(new Intent(this, GamepadEditorActivity.class));
                    if (which == 6 || which == 7) {
                        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/csv")
                                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,
                                    which == 7 ? "mkvdcu-frame-times.csv" : "mkvdcu-performance.csv");
                        startActivityForResult(save, which == 7 ? REQ_EXPORT_FRAMES : REQ_EXPORT_PERFORMANCE);
                    }
                })
                .setPositiveButton("Fechar", null)
                .show();
    }

    private void showPresetDialog() {
        GraphicsSettings graphics = new GraphicsSettings(this);
        android.widget.LinearLayout content = new android.widget.LinearLayout(this);
        content.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding / 2, padding, padding / 2);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(content);
        AlertDialog picker = new AlertDialog.Builder(this)
            .setTitle("Predefinições gráficas — próxima sessão")
            .setView(scroll).setNegativeButton("Fechar", null).create();
        for (int i = 0; i < GraphicsSettings.PRESET_NAMES.length; i++) {
            final int preset = i;
            android.widget.Button button = new android.widget.Button(this);
            button.setAllCaps(false);
            button.setText(GraphicsSettings.PRESET_NAMES[i]);
            button.setOnClickListener(v -> {
                graphics.applyPreset(preset);
                picker.dismiss();
                Toast.makeText(this, "Perfil aplicado. Inicie uma nova sessão.", Toast.LENGTH_SHORT).show();
                showGraphicsSettings();
            });
            content.addView(button);
            TextView description = new TextView(this);
            description.setText(GraphicsSettings.PRESET_DESCRIPTIONS[i]);
            description.setTextSize(13);
            description.setPadding(0, 0, 0, padding);
            content.addView(description);
        }
        picker.show();
    }

    // AlertDialog's message view replaces its list. Keep help in a separate
    // action so radio choices remain visible and actionable on Android.
    private AlertDialog.Builder graphicsChoiceDialog(String title, String explanation) {
        return new AlertDialog.Builder(this).setTitle(title)
            .setNeutralButton("Informações", (dialog, which) -> new AlertDialog.Builder(this)
                .setTitle(title).setMessage(explanation)
                .setPositiveButton("Voltar", (help, button) -> showGraphicsSettings()).show());
    }

    private void showGraphicsSettings() {
        GraphicsSettings graphics = new GraphicsSettings(this);
        int filterIndex = 0;
        for (int i = 0; i < GraphicsSettings.FILTERS.length; i++)
            if (GraphicsSettings.FILTERS[i] == graphics.filter()) filterIndex = i;
        int scaleIndex = 0;
        for (int i=0;i<GraphicsSettings.SCALES.length;i++) if (GraphicsSettings.SCALES[i]==graphics.scalePercent()) scaleIndex=i;
        String[] items = {
            "★ PREDEFINIÇÃO: " + graphics.presetName(),
            "Resolução da cena: " + GraphicsSettings.SCALE_NAMES[scaleIndex],
            "Filtragem anisotrópica: " + GraphicsSettings.FILTER_NAMES[filterIndex],
            "Cache de texturas: " + graphics.cacheMb() + " MiB",
            "SPIR-V SSA experimental: " + (graphics.ssa() ? "ativado" : "desativado"),
            "Área de renderização experimental: " + (graphics.renderArea() ? "ativada" : "desativada"),
            "Desempenho sustentado Android: " + (graphics.sustained() ? "ativado" : "desativado"),
            "Restaurar padrões", "Detalhe de texturas experimental: " +
                (graphics.mipDrop() == 0 ? "original" : "mínimo mip +" + graphics.mipDrop()),
            "Sobre as escalas de resolução",
            "Agrupar submissões experimental: " + (graphics.batch() ? "ativado" : "desativado"),
            "Medição GPU: " + (graphics.gpuTiming() == 0 ? "desativada" : "uma a cada " + graphics.gpuTiming() + " submissões"),
            "Perfil GPU por etapa: " + (graphics.gpuStages() ? "amostrado 1/64" : "desativado"),
            "Compiladores de pipelines: " + (graphics.pipelineThreads() < 0 ? "automático (até 2)" : graphics.pipelineThreads()),
            "Turnip — modo de renderização: " + GraphicsSettings.TURNIP_MODES[graphics.turnipMode()],
            "PanVK — stencil tardio experimental: " + (graphics.panvkLateStencil() ? "ativado" : "desativado"),
            "Núcleos do processo do jogo: " + (graphics.cores()==0 ? "automático" : "até " + graphics.cores()),
            "Economia de energia (PWR): " + (graphics.economy() ? "ativada" : "desativada"),
            "RAM — cache compacto: " + (graphics.compactMemory() ? "ativado" : "desativado"),
            "Limite de FPS de saída: " + (graphics.fpsLimit()==0 ? "sem limite" : graphics.fpsLimit() + " FPS"),
            "Fila de apresentação separada: " + (graphics.presentQueue() ? "ativada" : "desativada"),
            "Área de transferências EDRAM/Resolve: " + (graphics.transferArea() ? "limitada" : "completa"),
            "Frame generation experimental: " + (graphics.frameGeneration() ? (graphics.frameGenerationAdaptive() ? "adaptativa " : "forçada ") + graphics.frameGenerationFactor() + "×" : "desativada"),
            "Espera econômica do ring GPU: " + (graphics.ringBackoff() ? "ativada" : "desativada"),
            "Precisão reduzida em cores experimental: " + (graphics.relaxedFragment() ? "ativada" : "desativada"),
            "Texturas DXT1 em 16 bits experimental: " + (graphics.compactDxt1() ? "ativadas" : "desativadas"),
            "Heap de tiler PanVK: " + (graphics.panvkTilerHeapMb()==0 ? "padrão do driver" : graphics.panvkTilerHeapMb()+" MiB"),
            "Pools Vulkan ociosos: " + graphics.idleCommandPoolLimit(),
            "Pulo de quadros da apresentação: " + graphics.frameSkip()};
        new AlertDialog.Builder(this).setTitle("Gráficos — próxima sessão")
            .setItems(items, (dialog, which) -> {
                if (which == 0) showPresetDialog();
                if (which == 1) {
                    int selected=0; for(int i=0;i<GraphicsSettings.SCALES.length;i++) if(GraphicsSettings.SCALES[i]==graphics.scalePercent()) selected=i;
                    new AlertDialog.Builder(this).setTitle("Cena UE3 — escala experimental")
                        .setSingleChoiceItems(GraphicsSettings.SCALE_NAMES, selected, (d,w)->{ graphics.setScalePercent(GraphicsSettings.SCALES[w]); d.dismiss(); showGraphicsSettings(); })
                        .setNegativeButton("Cancelar",null).show();
                }
                if (which == 2) {
                    int selected = 0;
                    for (int i = 0; i < GraphicsSettings.FILTERS.length; i++)
                        if (GraphicsSettings.FILTERS[i] == graphics.filter()) selected = i;
                    new AlertDialog.Builder(this).setTitle("Filtragem anisotrópica")
                        .setSingleChoiceItems(GraphicsSettings.FILTER_NAMES, selected, (d, w) -> {
                            graphics.setFilter(GraphicsSettings.FILTERS[w]); d.dismiss(); showGraphicsSettings();
                        }).setNegativeButton("Cancelar", null).show();
                }
                if (which == 3) {
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
                if (which == 4 || which == 5) {
                    boolean currently = which == 4 ? graphics.ssa() : graphics.renderArea();
                    Runnable change = () -> {
                        if (which == 4) graphics.setSsa(!currently); else graphics.setRenderArea(!currently);
                        showGraphicsSettings();
                    };
                    if (currently) change.run();
                    else new AlertDialog.Builder(this).setTitle("Opção experimental")
                        .setMessage(which == 4 ? "Otimiza SPIR-V preservando a aritmética. Ainda não há ganho consistente medido na Mali. Requer nova sessão."
                            : "Une as áreas dos draws antes de enviá-los à GPU para reduzir LOAD/STORE. Padrão ativado: três pares A/B na Mali-G68 mediram 2,42 para 3,01 FPS nesta cena. Mantém draws e áreas de scissor; outras cenas e GPUs ainda precisam de validação. Desative para comparar defeitos gráficos.")
                        .setPositiveButton("Ativar", (d, w) -> change.run()).setNegativeButton("Cancelar", null).show();
                }
                if (which == 6) {
                    android.os.PowerManager power = getSystemService(android.os.PowerManager.class);
                    if (power != null && power.isSustainedPerformanceModeSupported()) graphics.setSustained(!graphics.sustained());
                    else Toast.makeText(this, "Este Android não oferece desempenho sustentado", Toast.LENGTH_LONG).show();
                    showGraphicsSettings();
                }
                if (which == 7) { graphics.reset(); showGraphicsSettings(); }
                if (which == 8) new AlertDialog.Builder(this).setTitle("Detalhe de texturas — próxima sessão")
                    .setSingleChoiceItems(new String[]{"Original", "Mínimo mip +1 (menos detalhe)", "Mínimo mip +2 (menos detalhe)"}, graphics.mipDrop(), (d, w) -> {
                        Runnable change = () -> { graphics.setMipDrop(w); d.dismiss(); showGraphicsSettings(); };
                        if (w == 0) change.run();
                        else new AlertDialog.Builder(this).setTitle("Reduzir detalhe")
                            .setMessage("Usa mipmaps menores de texturas DXT 2D com cadeia de mips e filtragem linear. Pode borrar materiais e máscaras. Não reduz a resolução interna nem o tamanho dos uploads. Ganho de FPS ainda precisa ser medido.")
                            .setPositiveButton("Aplicar", (confirm, button) -> change.run()).setNegativeButton("Cancelar", null).show();
                    }).setNegativeButton("Cancelar", null).show();
                if (which == 9) new AlertDialog.Builder(this).setTitle("Escalas experimentais")
                    .setMessage("0,5× solicita buffers de cena 520×312; 0,75× solicita 780×468. A saída permanece em 1280×720 para preservar a interface. A alteração atua na configuração dos buffers da UE3 antes de sua criação e também acompanha o modo alternativo 1024×576. A compatibilidade visual e o ganho de FPS precisam de validação no aparelho. Use 1× como referência se houver cortes, efeitos incorretos ou problemas de interface. Reinicie o jogo após alterar.")
                    .setPositiveButton("OK",null).show();
                if (which == 10) {
                    if (graphics.batch()) { graphics.setBatch(false); showGraphicsSettings(); }
                    else new AlertDialog.Builder(this).setTitle("Agrupar submissões")
                        .setMessage("Reduz submissões no fim de buffers PM4, mantendo envio nos swaps e esperas necessárias. Experimental: desative se houver parada ou diferença visual.")
                        .setPositiveButton("Ativar", (d, w) -> { graphics.setBatch(true); showGraphicsSettings(); })
                        .setNegativeButton("Cancelar", null).show();
                }
                if (which == 11) {
                    int selected = 0;
                    for (int i = 0; i < GraphicsSettings.GPU_TIMING.length; i++)
                        if (GraphicsSettings.GPU_TIMING[i] == graphics.gpuTiming()) selected = i;
                    new AlertDialog.Builder(this).setTitle("Medição de tempo GPU")
                        .setSingleChoiceItems(new String[]{"Amostrada 1/64 (padrão)", "Desativada (menor custo)", "Toda submissão (diagnóstico)"}, selected, (d, w) -> {
                            graphics.setGpuTiming(GraphicsSettings.GPU_TIMING[w]); d.dismiss(); showGraphicsSettings();
                        }).setNegativeButton("Cancelar", null).show();
                }
                if (which == 12) {
                    graphics.setGpuStages(!graphics.gpuStages());
                    showGraphicsSettings();
                }
                if (which == 13) {
                    int selected = 0;
                    for (int i = 0; i < GraphicsSettings.PIPELINE_THREADS.length; i++)
                        if (GraphicsSettings.PIPELINE_THREADS[i] == graphics.pipelineThreads()) selected = i;
                    graphicsChoiceDialog("Compilação de pipelines","Automático usa até dois compiladores para limitar concorrência e memória de pico. Quatro podem aquecer o cache mais rápido, mas consumir mais RAM. Não omite draws nem reduz a qualidade. Compare na mesma cena, com cache frio e aquecido.")
                        .setSingleChoiceItems(new String[]{"Automático (até 2)", "1 compilador", "2 compiladores", "4 compiladores"}, selected, (d, w) -> {
                            graphics.setPipelineThreads(GraphicsSettings.PIPELINE_THREADS[w]); d.dismiss(); showGraphicsSettings();
                        }).setNegativeButton("Cancelar", null).show();
                }
                if (which == 16) {
                    int[] counts=GraphicsSettings.coreCounts(this);String[] names=new String[counts.length];int selected=0;
                    for(int i=0;i<names.length;i++){ int n=counts[i];names[i]=n==0 ? "Automático — Android decide" : "Limitar a "+n+" núcleo(s)";if(n==graphics.cores())selected=i; }
                    if (selected == 0 && graphics.cores() != 0) {
                        for(int i=1;i<counts.length;i++)if(counts[i] >= graphics.cores()) { selected=i; break; }
                    }
                    graphicsChoiceDialog("Núcleos — próxima sessão","Aplica afinidade às CPUs permitidas pelo Android e mostra a máscara realmente aceita no overlay. A seleção prioriza cpu_capacity; se não estiver disponível, usa a frequência máxima por CPU. Se nenhum ranking for legível, mantém o escalonador Android sem fixar núcleos por número. Limitar CPUs não reduz diretamente a frequência nem garante menor temperatura.")
                        .setSingleChoiceItems(names,selected,(d,w)->{graphics.setCores(counts[w]);d.dismiss();showGraphicsSettings();})
                        .setNegativeButton("Cancelar",null).show();
                }
                if (which == 17) new AlertDialog.Builder(this).setTitle("Economia de energia")
                    .setMessage("Limita a apresentação a no máximo 30 FPS e usa até dois compiladores automáticos. Mantém um limite menor, como 24 FPS, se você o selecionou. O limite não pausa o XE_SWAP nem altera o relógio guest (escala 1,0), evitando desacelerar a simulação por espera do limitador. Ele pode reduzir trabalho de apresentação, mas não limita todos os draws da GPU nem acelera uma cena lenta. O modo Desempenho Mali também usa alvo de saída de 30 FPS. O Android escolhe os núcleos por padrão; afinidade manual é separada e pode reduzir desempenho.")
                    .setPositiveButton(graphics.economy() ? "Desativar" : "Ativar",(d,w)->{graphics.setEconomy(!graphics.economy());showGraphicsSettings();})
                    .setNegativeButton("Cancelar",null).show();
                if (which == 18) new AlertDialog.Builder(this).setTitle("Cache compacto — próxima sessão")
                    .setMessage("Limita o cache de texturas a no máximo 128 MiB suave / 256 MiB rígido e libera texturas antigas após 5 s quando o orçamento é excedido. Permite escolher 64 MiB no cache. Pode aumentar recargas e travadinhas. Não é um limite da RAM total: memória guest, áudio, drivers e render targets continuam necessários.")
                    .setPositiveButton(graphics.compactMemory() ? "Desativar" : "Ativar",(d,w)->{graphics.setCompactMemory(!graphics.compactMemory());showGraphicsSettings();})
                    .setNegativeButton("Cancelar",null).show();
                if (which == 19) {
                    int selected=0;for(int i=0;i<GraphicsSettings.FPS_LIMITS.length;i++)if(GraphicsSettings.FPS_LIMITS[i]==graphics.fpsLimit())selected=i;
                    graphicsChoiceDialog("Limite de FPS de saída — próxima sessão","Limita as apresentações finais, incluindo frames gerados, sem pausar a simulação ou os swaps do jogo. O HUD separa FPS do jogo e de saída. Não aumenta FPS de cenas lentas nem limita todo o trabalho da GPU. PWR reduz opções acima de 30 para 30 FPS. Reinicie o jogo após alterar.")
                        .setSingleChoiceItems(new String[]{"Sem limite (avançado)", "24 FPS", "30 FPS", "45 FPS", "60 FPS", "90 FPS", "120 FPS"},selected,(d,w)->{graphics.setFpsLimit(GraphicsSettings.FPS_LIMITS[w]);d.dismiss();showGraphicsSettings();})
                        .setNegativeButton("Cancelar",null).show();
                }
                if (which == 20) new AlertDialog.Builder(this).setTitle("Fila de apresentação — experimental")
                    .setMessage("Usa a segunda fila da mesma família gráfica quando o driver realmente oferece duas. Pode reduzir a disputa entre Present e comandos do jogo. Drivers com uma fila mantêm o caminho original. Requer nova sessão; compare FPS e estabilidade antes de manter ativada.")
                    .setPositiveButton(graphics.presentQueue() ? "Desativar" : "Ativar", (d,w)->{graphics.setPresentQueue(!graphics.presentQueue());showGraphicsSettings();})
                    .setNegativeButton("Cancelar",null).show();
                if (which == 21) {
                    boolean enabled = graphics.transferArea();
                    new AlertDialog.Builder(this).setTitle("Área de transferências EDRAM/Resolve")
                        .setMessage("Limita somente o render area dos passes de transferência e dos clears de resolve à união das regiões realmente calculadas, expandida para alinhamento. Não remove operações nem reduz a resolução. Esta opção é independente da área dos draws. O A/B anterior não isolou seu efeito; compare ligada/desligada na mesma arena antes de atribuir ganho ou manter alterações visuais.")
                        .setPositiveButton(enabled ? "Desativar para comparar" : "Ativar para comparar", (d,w)->{graphics.setTransferArea(!enabled);showGraphicsSettings();})
                        .setNegativeButton("Cancelar",null).show();
                }
                if (which == 22) {
                    int selected = !graphics.frameGeneration() ? 0 :
                        (graphics.frameGenerationAdaptive() ? 1 : 4) + graphics.frameGenerationFactor() - 2;
                    graphicsChoiceDialog("Frame generation - proxima sessao",
                        "O modo adaptativo só interpola após manter cerca de 30 FPS reais e desvia abaixo de 24 FPS. 3×/4× podem aumentar a cadência de saída, mas elevam custo e pressão na fila; o driver e as imagens disponíveis limitam o que chega ao Presenter. O modo forçado é para diagnóstico e pode reduzir FPS real. A simulação não é acelerada nem pausada.")
                        .setSingleChoiceItems(new String[]{"Desativada", "Adaptativa 2×", "Adaptativa 3×", "Adaptativa 4×", "Forçada 2× (diagnóstico)", "Forçada 3× (diagnóstico)", "Forçada 4× (diagnóstico)"}, selected, (d,w) -> {
                            if (w != 0) graphics.setFrameGenerationFactor(w <= 3 ? w + 1 : w - 2);
                            graphics.setFrameGeneration(w != 0); graphics.setFrameGenerationAdaptive(w >= 1 && w <= 3);
                            d.dismiss(); showGraphicsSettings();
                        }).setNegativeButton("Cancelar", null).show();
                }
                if (which == 26) {
                    int selected=0;for(int i=0;i<GraphicsSettings.PANVK_TILER_HEAPS.length;i++)if(GraphicsSettings.PANVK_TILER_HEAPS[i]==graphics.panvkTilerHeapMb())selected=i;
                    graphicsChoiceDialog("Heap de tiler PanVK - experimental",
                        "Usa PANVK_TILER_HEAP_MB quando o driver oferece essa opcao. O FristOneRR usa 512 MiB por padrao. Valores menores podem poupar RAM, mas um heap insuficiente pode perder geometria ou falhar. Nao altera texturas, resolucao ou simulacao. Requer nova verificacao do driver e nova sessao. Padrao: escolha do driver.")
                        .setSingleChoiceItems(new String[]{"Padrao do driver", "128 MiB (experimental)", "256 MiB (experimental)", "512 MiB", "1024 MiB"},selected,(d,w)->{
                            graphics.setPanvkTilerHeapMb(GraphicsSettings.PANVK_TILER_HEAPS[w]);d.dismiss();showGraphicsSettings();
                        }).setNegativeButton("Cancelar",null).show();
                }
                if (which == 27) {
                    int selected = 0;
                    for (int i = 0; i < GraphicsSettings.IDLE_COMMAND_POOL_LIMITS.length; i++)
                        if (GraphicsSettings.IDLE_COMMAND_POOL_LIMITS[i] == graphics.idleCommandPoolLimit()) selected = i;
                    graphicsChoiceDialog("Pools Vulkan ociosos — próxima sessão",
                        "Controla quantos command pools já concluídos ficam em cache para reduzir criação/destruição. No A/B da luta em PanVK, 8/16/32 mediram 7,27/7,33/7,57 FPS; 32 usou cerca de 0,55 GiB de PSS adicional frente a 8. O ganho medido foi pequeno e depende da cena. Use 8 para priorizar RAM, 32 para comparar desempenho. Pools com trabalho em voo nunca são descartados. Reinicie o jogo após alterar.")
                        .setSingleChoiceItems(new String[]{"8 — menor uso de RAM (padrão)", "16 — equilibrado", "32 — mais cache de pools"}, selected, (d, w) -> {
                            graphics.setIdleCommandPoolLimit(GraphicsSettings.IDLE_COMMAND_POOL_LIMITS[w]); d.dismiss(); showGraphicsSettings();
                        }).setNegativeButton("Cancelar", null).show();
                }
                if (which == 28) {
                    int selected = graphics.frameSkip();
                    graphicsChoiceDialog("Pulo de quadros da apresentação — próxima sessão",
                        "Mostra 1 quadro e pula o número selecionado de apresentações seguintes. Os draws, resolves, áudio e simulação do jogo continuam em todos os quadros; isso reduz somente o trabalho final de apresentação e pode não aumentar o FPS da cena. Frame generation tem prioridade e desativa este pulo enquanto estiver ativa. Reinicie o jogo após alterar.")
                        .setSingleChoiceItems(new String[]{"Desativado", "Pular 1 quadro (mostrar 1 de cada 2)", "Pular 2 quadros (mostrar 1 de cada 3)", "Pular 3 quadros (mostrar 1 de cada 4)"}, selected, (d, w) -> {
                            graphics.setFrameSkip(GraphicsSettings.FRAME_SKIP_VALUES[w]); d.dismiss(); showGraphicsSettings();
                        }).setNegativeButton("Cancelar", null).show();
                }
                if (which == 23) new AlertDialog.Builder(this).setTitle("Espera econômica do ring GPU")
                    .setMessage("Quando a GPU não libera espaço no ring, o thread do jogo espera de 50 a 400 µs após 16 consultas sem progresso. O atraso é reiniciado quando a GPU avança. Preserva os comandos e o watchdog do jogo; não limita XE_SWAP nem a simulação. Pode reduzir CPU e aquecimento; o resultado depende do aparelho. Exige nova sessão.")
                    .setPositiveButton(graphics.ringBackoff() ? "Desativar para comparar" : "Ativar",(d,w)->{graphics.setRingBackoff(!graphics.ringBackoff());showGraphicsSettings();})
                    .setNegativeButton("Cancelar",null).show();
                if (which == 24) new AlertDialog.Builder(this).setTitle("Precisão reduzida — experimental")
                    .setMessage("Permite RelaxedPrecision em operações saturadas do pixel shader. Mantém vértices, depth, exports de memória e constantes em precisão original. O driver pode usar FP16 ou manter FP32; ganho não é garantido e cores podem mudar. Usa cache próprio para evitar misturar shaders de precisão diferente. Padrão desativado. Exige nova sessão.")
                    .setPositiveButton(graphics.relaxedFragment() ? "Desativar" : "Ativar para comparar",(d,w)->{graphics.setRelaxedFragment(!graphics.relaxedFragment());showGraphicsSettings();})
                    .setNegativeButton("Cancelar",null).show();
                if (which == 25) new AlertDialog.Builder(this).setTitle("Texturas DXT1 em 16 bits — experimental")
                    .setMessage("Em GPUs sem BC1 nativo, decodifica DXT1 para RGB5A1: 2 bytes por texel em vez de 4. Preserva a máscara de transparência de 1 bit, reduz precisão RGB e pode causar banding. DXT3/DXT5, texturas nativas BC1 e memória guest permanecem no formato original. Usa RGBA8 se o formato compacto não for suportado. Padrão desativado. Exige nova sessão.")
                    .setPositiveButton(graphics.compactDxt1() ? "Desativar" : "Ativar para comparar",(d,w)->{graphics.setCompactDxt1(!graphics.compactDxt1());showGraphicsSettings();})
                    .setNegativeButton("Cancelar",null).show();
                if (which == 15) new AlertDialog.Builder(this).setTitle("PanVK — próxima sessão")
                    .setMessage("Opção PANVK_STENCIL_LATE do FristOneRR 2.0-rc1. Pode corrigir triângulos pretos/luz ausente, mas também reduzir FPS e escurecer a imagem. Padrão desativado. Exige verificar novamente o pacote. Não altera o driver do sistema nem Turnip.")
                    .setPositiveButton(graphics.panvkLateStencil() ? "Desativar" : "Ativar", (d, w) -> { graphics.setPanvkLateStencil(!graphics.panvkLateStencil()); showGraphicsSettings(); })
                    .setNegativeButton("Cancelar", null).show();
                if (which == 14) graphicsChoiceDialog("Turnip — próxima sessão","Somente para driver Turnip importado. Automático mantém a escolha do driver; Sysmem evita o binning em tiles; GMEM força tiles. O melhor modo depende da GPU e da cena. Exige verificar novamente o pacote no gerenciador de drivers. Não afeta o driver do sistema nem PanVK.")
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
                .setTitle("Sobre — " + BuildConfig.VERSION_NAME)
                .setMessage(sb.toString())
                .setNeutralButton("Atualizações", (d, w) -> startActivity(new Intent(this, UpdateActivity.class)))
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
            refresh();
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
        if (BuildConfig.DEBUG && getIntent().hasExtra("graphics_present_queue"))
            probe.putExtra("graphics_present_queue",getIntent().getBooleanExtra("graphics_present_queue",false));
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
            // A low-memory kill is not evidence that the selected ICD failed.
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.app.ActivityManager manager = getSystemService(android.app.ActivityManager.class);
                if (manager != null) try {
                    for (android.app.ApplicationExitInfo info : manager.getHistoricalProcessExitReasons(getPackageName(), 0, 16)) {
                        if (!(getPackageName() + ":game").equals(info.getProcessName())) continue;
                        if (info.getReason() == android.app.ApplicationExitInfo.REASON_LOW_MEMORY) {
                            refresh();
                            showDriverError("O Android encerrou o jogo por falta de memória RAM. O driver selecionado foi mantido. Reduza o cache de texturas ou feche outros aplicativos antes de tentar novamente.");
                            return;
                        }
                        break;
                    }
                } catch (RuntimeException error) { android.util.Log.w("MKVDCU", "Exit reason unavailable", error); }
            }
            prefs.setDriverId("system");
            showDriverError("A sessão do driver personalizado encerrou antes dos primeiros frames. Driver do sistema restaurado.");
        }
    }
}
