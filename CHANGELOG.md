# Changelog

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
