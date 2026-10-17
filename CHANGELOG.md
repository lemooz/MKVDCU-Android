# Changelog
## v0.1.17 — pré-release

### Português

- O preset Desempenho Mali agora reúne opções experimentais de menor custo: FXAA do Presenter desligado, filtragem anisotrópica desligada, mip drop DXT1, decodificação DXT1 compacta RGB5A1 e RelaxedPrecision. As duas últimas podem reduzir qualidade/cor e mantêm fallback quando o formato compacto não é suportado.
- Adicionada seleção do efeito FXAA no Presenter (desativado, FXAA e FXAA extremo); isso afeta apenas o passe final e não desativa efeitos internos da UE3.
- Reduzido o backoff inicial do ring GPU para começar após quatro consultas sem progresso. A mudança preserva os comandos, mas ainda precisa de medição física de desempenho e estabilidade.
- Corrigido o verificador local de configurações para acompanhar a descrição atual do preset.
- Build e regressões locais ARM64/Vulkan; teste em aparelho e leitura da ISO ainda pendentes.

### English

- The Mali Performance preset now groups experimental lower-cost options: Presenter FXAA off, anisotropic filtering off, DXT1 mip drop, compact RGB5A1 DXT1 decoding, and RelaxedPrecision. The latter options may reduce color/visual quality and retain a fallback when the compact format is unsupported.
- Added a Presenter FXAA selector (off, FXAA, and extreme FXAA); it affects only the final presentation pass and does not disable UE3 internal effects.
- Shortened the GPU ring backoff so it begins after four no-progress polls. Commands are preserved, but physical performance and stability measurements remain pending.
- Updated the local graphics-settings regression check to match the current preset description.
- Local ARM64/Vulkan build and regression checks; device testing and ISO inspection remain pending.

## v0.1.16 — local / not published

### Português

- Renomeado o preset de menor custo para **Desempenho Mali — alvo 30 FPS**, deixando explícito que 30 FPS é a cadência de apresentação solicitada e não uma garantia de desempenho ou limite da simulação.
- Documentada e protegida por regressão a separação entre apresentação e relógio do jogo: Android não espera no `XE_SWAP`, o relógio guest inicia em escala 1,0, e limites menores escolhidos pelo usuário (como 24 FPS) continuam respeitados.

### English

- Renamed the lowest-cost preset to **Mali Performance — 30 FPS target**, clarifying that 30 FPS is a requested presentation cadence, not a performance guarantee or a simulation cap.
- Documented and regression-guarded the separation between presentation and game clock: Android does not wait in `XE_SWAP`, the guest clock starts at 1.0 scale, and lower user-selected caps (such as 24 FPS) remain respected.

## v0.1.15 — local / not published

### Português

- Adicionada opção de pulo de 1 a 3 quadros na apresentação Vulkan. Os comandos gráficos, resolves, áudio e simulação continuam em todos os quadros; o recurso reduz somente o trabalho de apresentar o resultado e não promete aumentar o FPS da cena.
- Frame generation tem prioridade: o pulo de apresentação fica inativo enquanto a geração de quadros estiver habilitada.

### English

- Added a Vulkan presentation frame-skip option for 1 to 3 frames. Graphics commands, resolves, audio and simulation still run for every frame; this only reduces presentation work and does not promise a higher scene FPS.
- Frame generation takes precedence: presentation skipping is inactive while frame generation is enabled.

## v0.1.14 — local / not published

### Português

- Corrigidos os seletores de FPS, núcleos, compiladores de pipeline e modo Turnip: as instruções não escondem mais as opções de alteração.
- FG automático evita interpolação e cópias intermediárias quando a cadência real é baixa; mantém os draws e relógios guest. O modo sempre ativo permanece disponível para comparação.
- Corrigida a submissão do frame de origem antes de entregá-lo ao worker do FG, com proteção contra dois produtores simultâneos na apresentação.
- Adicionado comparador A/B na mesma sessão com contadores nativos, intervalos reais, mediana/P95, screenshots e estado térmico. PanVK sem timestamps GPU é registrado como indisponível.
- Controle experimental do heap de tiler PanVK, isolado por cache e verificação do driver; padrão original preservado. Não altera o heap de tessellation.
- Pools de comandos ociosos limitados no Android; apenas pools com fence concluída são destruídos, liberando buffers internos do driver sem omitir draws.
- Limite do cache de pools Vulkan exposto nas configurações (8/16/32) com o custo medido de desempenho/memória; 8 permanece padrão.
- Encerramento Android por falta de RAM não é mais apresentado como falha de inicialização do driver personalizado.
- A comparação preliminar no menu mostrou 6,26 → 7,05 FPS ao evitar o compute de FG. Não constitui medição de combate. Na luta, o A/B separado de pools 8/16/32 mostrou 7,27/7,33/7,57 FPS e PSS maior em ~0,55 GiB no limite 32; a diferença de ~4% não justifica mudar o padrão. PanVK não expõe timestamps GPU válidos neste aparelho, portanto tempos de fence são relatados apenas como espera de CPU.

### English

- Fixed FPS, CPU core, pipeline compiler and Turnip mode selectors: help text no longer hides editable choices.
- Automatic FG bypasses interpolation and intermediate copies at low native cadence while preserving guest draws and clocks. Always-on mode remains available for comparison.
- Submit source frames before handing them to the FG worker; prevent simultaneous presentation producers.
- Added same-session A/B comparison with native counters, actual frame intervals, median/P95, screenshots and thermal state. Missing PanVK GPU timestamps are reported as unavailable.
- Experimental PanVK tiler heap control is scoped to driver verification and cache identity; original driver default preserved. Tessellation heap remains unchanged.
- Bound idle command pool reuse on Android; destroy only pools with completed fences to free driver-private buffers without dropping draws.
- Exposed Vulkan idle pool cache limits (8/16/32) with measured performance/memory tradeoffs; 8 remains the default.
- Android low-memory termination is no longer reported as custom-driver initialization failure.
- Preliminary menu comparison measured 6.26 → 7.05 FPS by bypassing FG compute; this is not a combat result. Separate fight A/B for pool limits 8/16/32 measured 7.27/7.33/7.57 FPS and ~0.55 GiB higher PSS at 32; the ~4% change does not justify changing the default. PanVK exposes no valid GPU timestamps on this device, so fence durations are reported only as CPU wait.

## v0.1.13 — pré-release

### Português

- Corrigida referência nula ao configurar o limitador antes da criação do overlay, que interrompia o carregamento das bibliotecas do jogo na 0.1.12.
- Callbacks de métricas e controles respeitam falhas de inicialização SDL, preservando o erro original sem encerrar o processo por chamadas JNI prematuras.
- Adicionada regressão executando o loader real com overlay ausente/presente e falha controlada.

### English

- Fixed a null HUD reference during frame-cap setup before overlay construction, which interrupted game library loading in 0.1.12.
- Metrics and input callbacks respect SDL initialization failure, preserving the original error without crashing on premature JNI calls.
- Added regression executing the production loader with missing/present HUD and a controlled loader failure.

## v0.1.12 — pré-release

### Português

- Limitador de apresentações Android com opções 24, 30, 45, 60, 90 e 120 FPS ou sem limite; inclui frames gerados.
- Espera por deadline no loop SDL, com coalescimento de pedidos, sem pausa no XE_SWAP e sem segurar locks Vulkan.
- HUD separa FPS do jogo, apresentações bem-sucedidas por segundo e limite configurado. PWR mantém 24 FPS e limita opções maiores a 30.
- Validação local de cadência, retomada de frame pendente e configurações; validação física ainda necessária.

### English

- Android presentation limiter with 24, 30, 45, 60, 90 and 120 FPS or uncapped options, including generated frames.
- SDL deadline scheduling coalesces requests without sleeping in XE_SWAP or holding Vulkan locks.
- HUD separates game FPS, successful presentations per second and configured cap. PWR preserves 24 FPS and caps higher settings at 30.
- Local pacing, pending-frame recovery and settings regressions; device validation remains pending.

## v0.1.11 — local / not published

### Português

- Backoff configurável no loop de ring GPU cheio, preservando simulação e watchdog; tempo de espera registrado separadamente.
- Remoção conservadora de passes dinâmicos vazios, mantendo draws, estados, clears e sincronização necessária.
- Opções experimentais, desligadas por padrão: precisão reduzida em ALU saturada de fragmentos e DXT1 decodificado em RGB5A1 de 16 bits, preservando transparência.
- Caches de pipeline separados por perfil de precisão; diagnóstico da arena inclui formatos e estado de depth/scissor.
- Regressões locais de configuração, buffer diferido, backoff e execução Vulkan do decoder. Ganhos de FPS/térmica e correção do chão ainda exigem validação na Mali.

### English

- Configurable GPU ring-full polling backoff preserves simulation and the guest watchdog; voluntary CPU wait time is recorded separately.
- Conservatively remove empty dynamic passes while preserving draws, state, clears and required synchronization.
- Experimental options, disabled by default: reduced precision for saturated fragment ALU and direct 16-bit RGB5A1 DXT1 decoding with preserved alpha.
- Precision-specific pipeline caches and arena diagnostics covering depth formats/state and scissor origin.
- Local settings, deferred replay, backoff and Vulkan decoder regressions. Mali FPS/thermal gains and the arena floor fix still need physical validation.

## v0.1.6 — 2026-10-15 (pré-release)

Atualização cumulativa desde a v0.1.5, focada em escala real da cena UE3 e medição de otimizações Vulkan. A compatibilidade gráfica e o desempenho continuam experimentais; não há nova validação física nem promessa de FPS nesta versão.

- Escala de cena 0,5×/0,75× agora altera, antes da inicialização dos recursos, as dimensões UE3 1040×624 e o caminho alternativo 1024×576. A alteração é protegida por âncoras do XEX e mantém o backbuffer de saída em 1280×720 para preservar composição/interface.
- Adicionado teste reproduzível que executa o patch de resolução e consumidores recompilados de inicialização/alocação, incluindo dimensões derivadas dos buffers. Âncoras, limites ou imagem incompatíveis fazem a alteração ser recusada sem escrita parcial.
- Configurações gráficas organizadas em presets Baixo, Equilibrado e Alto, com ajuste de escala de cena, cache/mip, anisotropia e submissões agrupadas; opções personalizadas preservam as escolhas do driver e opções avançadas.
- Separado o limite de apresentação da simulação: no Android, o valor de 30/60 Hz é apenas uma solicitação de taxa para a Surface. A espera artificial no caminho de swap convidado foi removida no Android para não desacelerar a simulação; outros alvos mantêm o comportamento existente.
- Adicionada opção independente para limitar áreas de transferências/Resolve à união necessária, com benchmark A/B temporário e métrica CSV própria. Não descarta draws nem operações de transferência.
- O overlay distingue escala de cena solicitada, escala do backend e taxa pedida à tela.
- Inicializado o diretório privado de cache Android para persistência do cache nativo do driver Vulkan.
- Atualizados verificadores de configurações, benchmark A/B e empacotamento para a árvore real do SDK e para verificar os artefatos ARM64.

**Limitações:** escalas de cena são experimentais e podem afetar efeitos/compatibilidade; pedidos de atualização da tela não limitam a simulação e não garantem economia ou ganho de desempenho. Sem teste físico desta versão, não se afirma melhoria de imagem/FPS na Mali nem compatibilidade nova de driver.

## v0.1.0-alpha.1 — 2026-10-13

First public Android ARM64 APK.

- Early playable build; reported playable on Snapdragon 8 Elite and reaches in-game on Exynos 1380.
- Partial experimental custom Vulkan driver support for Adreno and Mali.
- Compatibility, graphics, performance, and PanVK limitations remain.
- See the GitHub prerelease notes and `dist/LEIA-ME-MALI-20261013.txt` for build-specific details.
