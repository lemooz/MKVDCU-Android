# Changelog
## v0.1.21 — pré-release

### Português

- Adicionado controle da resolução interna do jogo (backbuffer do convidado): 100% (1280×720, padrão), 75% (960×540) e 50% (640×360). Antes o app enviava sempre `--port_internal_resolution_percent=100`; agora o valor é escolhido em Configurações → Gráficos e desempenho → "Resolução interna do jogo (backbuffer)" e entra na próxima sessão.
- A cena e o backbuffer passam a ser resolvidos com menos pixels e o Presenter amplia para a tela; o efeito combina com CAS/FSR. A UI recomenda manter a resolução interna maior ou igual à escala de cena para não reduzir duas vezes.
- Dois presets novos: "Compatibilidade — sem recursos experimentais" (desliga override UE3, DXT1 16 bits e precisão reduzida; cena e backbuffer em 100%, reconstrução bilinear, 60 FPS) e "Fluidez máxima — backbuffer 50%" (cena 50%, backbuffer 50%, FSR 1.0 com nitidez 0,40, 30 FPS).
- Novo item "Diagnóstico do dispositivo" no menu Configurações: aparelho, placa, SoC, Android, ABI, núcleos, RAM, driver Vulkan em uso, preset, escala de cena, backbuffer, reconstrução, filtragem, limite de FPS e cache, com botão Copiar — serve para relatar incompatibilidade de driver sem precisar de ADB.
- Nenhum caminho de renderização nativo foi alterado nesta versão e os padrões continuam idênticos aos da v0.1.20 (backbuffer 100%, presets Desempenho Mali/Padrão/Alto inalterados). Nenhum ganho de FPS é reivindicado sem medição no aparelho; o ganho esperado do backbuffer é proporcional à redução de pixels (75% ≈ 0,56× pixels, 50% ≈ 0,25×), limitado por sombras e efeitos com resolução fixa.

### English

- Added a game internal resolution (guest backbuffer) control: 100% (1280×720, default), 75% (960×540) and 50% (640×360). The app previously always passed `--port_internal_resolution_percent=100`; the value is now selected in Settings → Graphics and applied on the next game session.
- Scene and backbuffer are resolved with fewer pixels and the Presenter upscales to the screen; it combines with CAS/FSR. The UI recommends keeping the internal resolution at or above the scene scale to avoid double reduction.
- Two new presets: "Compatibility — no experimental features" (disables the UE3 override, 16-bit DXT1 and reduced precision; scene and backbuffer at 100%, bilinear reconstruction, 60 FPS) and "Maximum smoothness — 50% backbuffer" (50% scene, 50% backbuffer, FSR 1.0 at 0.40 sharpness, 30 FPS).
- New "Device diagnostics" entry in the Settings menu: device, board, SoC, Android, ABI, cores, RAM, active Vulkan driver, preset, scene scale, backbuffer, reconstruction, filtering, FPS limit and cache, with a Copy button — useful for driver incompatibility reports without ADB.
- No native rendering path was changed and defaults match v0.1.20 (backbuffer 100%, Mali Performance/Balanced/High presets unchanged). No FPS gain is claimed without on-device measurement; the expected backbuffer gain scales with pixel count (75% ≈ 0.56× pixels, 50% ≈ 0.25×) and is limited by fixed-resolution shadows and effects.

## v0.1.20 — pré-release

### Português

- Incluídas na distribuição as alterações locais recentes do ReXGlue: descarte antecipado de draws sem saída, RelaxedPrecision em operações de texture fetch elegíveis e opção Android experimental de `STORE_OP_DONT_CARE` para depth/stencil.
- A mudança de armazenamento pode afetar passes que reutilizam profundidade; desempenho e correção visual ainda precisam de validação física. Nenhum ganho de FPS é reivindicado.
- Atualizada a versão do app e sincronizado `sdk.patch` com o checkout para reproduzir os binários ARM64.

### English

- Included the latest local ReXGlue changes in the distribution: early rejection of draws with no output, RelaxedPrecision for eligible texture-fetch operations, and an experimental Android `STORE_OP_DONT_CARE` option for depth/stencil.
- The store behavior may affect passes that reuse depth; performance and visual correctness still need physical-device validation. No FPS gain is claimed.
- Updated the app version and synchronized `sdk.patch` with the checkout so ARM64 binaries can be reproduced.

## v0.1.19 — pré-release

### Português

- Adicionada seleção de reconstrução da saída Vulkan: bilinear, CAS e FSR espacial 1.0 (EASU + RCAS), com controle de nitidez RCAS. A opção opera na imagem final; não reduz o trabalho de renderização da UE3 nem promete ganho de FPS.
- Presets Baixo e Padrão selecionam FSR para reconstruir escalas de cena menores; Alto mantém bilinear. O usuário ainda pode escolher o efeito e reiniciar o jogo para aplicar.
- Integradas ao patch versionado do ReXGlue as alterações locais do SDK presentes no checkout, incluindo suporte espacial FidelityFX, cache nativo de pipeline, frame generation e atualizações Android/Vulkan, para que o build reproduza os binários da release.
- Build e compatibilidade física em dispositivo precisam ser confirmados; esta versão não reivindica melhoria de desempenho nem validação de gameplay.

### English

- Added Vulkan output reconstruction choices: bilinear, CAS, and spatial FSR 1.0 (EASU + RCAS), with an RCAS sharpness control. This operates on the final image; it does not reduce UE3 rendering work or promise an FPS gain.
- Low and Balanced presets select FSR to reconstruct smaller scene scales; High keeps bilinear. Users can still choose the effect and restart the game to apply it.
- Added the local ReXGlue SDK checkout changes to the versioned patch, including spatial FidelityFX, native pipeline cache, frame generation, and Android/Vulkan updates, so release builds reproduce the shipped native code.
- Build and physical device compatibility still require confirmation; this release does not claim a performance improvement or gameplay validation.

## v0.1.18 — pré-release

### Português

- Inspecionada a `Coalesced.ini` da ISO do jogo; foi confirmado que `Xe-MK8Engine.ini` habilita Branching PCF qualidade 4 e permite mapas de sombra de até 1024/2048 px, enquanto o arquivo solto procurado pelo jogo não existia.
- Adicionado override UE3 opcional, derivado desses valores da ISO, que desativa Branching PCF e limita mapas de sombra a 256/512 px. O preset Desempenho Mali ativa o override; demais presets e configurações padrão deixam-no desativado. O arquivo entra pelo overlay VFS antes do início do XEX, sem suprimir draws ou resolves.
- Adicionada opção manual para ativar/desativar o patch. No Galaxy A54, o log confirmou `Mod loaded` e duas aberturas de `D:\Config\Xe-MK8Engine.ini` sem erro.
- Captura física anterior da luta no Galaxy A54 (Mali-G68, driver do sistema): HUD observou 4 FPS de jogo, 3 FPS de saída e 206,8 ms por quadro, com ~57 ms de submissão GPU. Essa captura usava 0,5×, frame generation forçada e pulo de quadros; não serve como A/B do override. Após aplicá-lo, a nova sessão abriu a configuração e chegou a Press Start (15–19 FPS, ~49–62 ms/frame no logo), sem repetir ainda a mesma luta. Nenhum ganho de combate é reivindicado.
- Com o override ativo, uma sessão posterior chegou à introdução da luta e renderizou os dois lutadores (8 FPS de jogo, 3 FPS de saída, 142,9 ms/frame, ~72,6 ms de submissão GPU). A luta/cena e as opções de geração de quadros diferiam da captura anterior; isso não comprova ganho de FPS. O filtro vermelho persiste.

### English

- Inspected the game's ISO `Coalesced.ini`; `Xe-MK8Engine.ini` enables Branching PCF quality 4 and allows shadow maps up to 1024/2048 px, while the loose file requested by the game was absent.
- Added an optional UE3 override derived from those ISO values. It disables Branching PCF and caps shadow maps at 256/512 px. The Mali Performance preset enables it; other presets and default settings leave it disabled. The file is mounted through the VFS mod overlay before the XEX starts, without suppressing draws or resolves.
- Added a manual enable/disable option. On the Galaxy A54, logs confirmed `Mod loaded` and two successful opens of `D:\Config\Xe-MK8Engine.ini`.
- Physical Galaxy A54 (Mali-G68, system driver) fight capture: HUD showed 4 game FPS, 3 output FPS, 206.8 ms per frame and about 57 ms GPU submission. The session used 0.5×, forced frame generation and frame skipping, so this is an observed reference, not an isolated A/B result. The red tint remains visible.
- With the override enabled, a subsequent session reached the fight intro and rendered both fighters (8 game FPS, 3 output FPS, 142.9 ms/frame, ~72.6 ms GPU submission). The matchup/scene and frame-generation settings differed from the earlier capture, so this does not establish an FPS gain. Red tint persists.

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
