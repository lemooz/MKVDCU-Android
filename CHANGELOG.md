# Changelog

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
