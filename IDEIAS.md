# Pulsa · Ideias para depois

> Lista de melhorias futuras (não implementadas ainda).

## ✓ Feito (2026)

- [x] **Delete por voz com confirmação**: a Virgínia pergunta "quer apagar?" e aceita "sim", "sim.", ", sim", "pode apagar"/"pode excluir" diretamente (ignora pontuação/enj do reconhecedor — `DjCommander.affirm`, via `DjVoice.kt`)
- [x] **Som ambiente audível**: geradores (noite/chuva/oceano/via) com ganho de saída (`OUTPUT_GAIN` em `Ambient.kt`) + volume padrão maior
- [x] **Botões físicos de volume controlam o som ambiente** quando ele está ativo, com indicação de % (`MainActivity` + `DjActivity` → `onKeyDown` → `Ambient.setVolume`)
- [x] **Normalização de ganho no reconhecimento do DJ** (audD): áudio do microfone amplificado de forma consistente (`DjRecognizer`)
- [x] **Letras**: busca recursiva por pasta + por título + pastas públicas (Música/Downloads/Podcasts/Ringtones) + fallback online (LRCLIB) quando sem metadados, com cache local (`Lyrics.kt`)
- [x] **Atualização automática**: o app baixa sozinho em background e instala via `PackageInstaller` (sem abrir o instalador), com fallback para o instalador + notificação (`UpdateChecker` + `UpdateService` + `InstallReceiver`)
- [x] **Changelog "O que foi feito"** a cada versão, com o texto da versão atual (pt/en/es)
- [x] **Dashboard de telemetria**: músicas mais tocadas, horários, sugestões aceitas/rejeitadas (`/dashboard` + `/stats` em http://192.168.100.7:8081/dashboard)
- [x] **CI publicando release automática no push**: build + publicação do APK no `pulsaweb` via GitHub Actions (`.github/workflows/build.yml`)
- [x] **Equalizador**: presets Graves/Vozes/Agudos/Rock/Dance/**Pop**/**Jazz** (`Settings`) + EQ personalizado de 5 bandas com sliders, salvar/resetar (`NowPlayingFragment` → `AudioFx`); corrigido o liga/desliga que as bandas custom ignoravam
- [x] **EQ automático por gênero**: qualidade "Automático (por gênero)" aplica preset conforme o gênero da faixa (Rock/Dance/Pop/Jazz/Graves/Vozes) via `AudioFx.presetForGenre` + `Library.genreOf` (seleção no `SettingsActivity`)
- [x] **Migração para v4.0**: `versionCode 75` / `versionName "4.0"`
- [x] **Virgínia dançando**: a Virgin assume a capa do álbum e balança no ritmo da música (bass do visualizer) — `DancingVirginView` no `NowPlayingFragment`, com microfone de karaokê no desenho
- [x] **Avatar estilo TAMI** (4.2.2): esfera rosada com laço, olhos brilhantes, bochechas e sorriso — `virgin_avatar.xml`
- [x] **Piscada estilo TAMI** (4.2.3): a Virgin pisca os olhos a cada ~4s via `AnimatedVectorDrawable` (`virgin_avatar_animated` + `virgin_blink`) no DJ, comandos de voz e na dança
- [x] **Nunca mais parada** (4.2.4): respiração/balanço contínuo mesmo sem música — idle breathe no `DancingVirginView` + avatar bob no `DjActivity`
- [x] **Som ambiente corrigido** (4.2.1): oceano com ondas longas (~8s) e chuva com chiar constante + gotas — antes soavam trocados (`Ambient.genOcean`/`genRain`)
- [x] **Migração para v4.2**: `versionCode 77` / `versionName "4.2"`
- [x] **Ícone novo do Pulsa** (4.2.5): a cara da Virgínia (estilo TAMI) com fundo neon + notas musicais — `ic_launcher.xml`
- [x] **Redesign Aurora Índigo** (4.3.0): visual moderno/futurista neutro (deep-space `#06091A` + gradientes violeta/ciano `#8A7CFB`/`#2FD8E8`, vidro, botões neon); tipografia Space Grotesk (display) + Outfit (corpo); animações de transição entre telas (window + fragments); splash 12+ aurora (`themes.xml`, `types.xml`, `colors.xml`, `drawable/bg_*`, `anim/*`)
- [x] **EQ com bandas reais do aparelho** (4.3.0): o equalizador agora lê as bandas/frequências reais do aparelho (diálogo dinâmico + curva espelhada por frequência log) e ganhou presets novos Acústico/Clássico/Sonoridade (`AudioFx` + `NowPlayingFragment` + `Settings`)
- [x] **DjLearn espelhado na nuvem** (4.3.0): stats de play/skip/like/dislike espelhados via `/djlearn` (POST push quando sujo + GET pull/merge max a cada 5min) — Virgin aprende entre sessões/dispositivos (`DjLearn.snapshot/mergeRemote` + `RemoteSync.maybeSyncDjLearn`)
- [x] **Assinatura via GitHub Actions** (4.3.0): keystore/senhas fora do código, via secrets `KEYSTORE_BASE64/PASSWORD/ALIAS` com fallback local (`app/build.gradle.kts` + `.github/workflows/build.yml`)
- [x] **Status SMTP verificado** (4.3.0): envio parseia resposta do `/sendmail` (distingue falha de SMTP 535/536) e a tela de login consulta `/smtp/status` mostrando online/offline/não verificado (`ConfirmMail` + `LoginActivity`)
- [x] **Ouvir juntos** (4.3.2): sessão entre dois aparelhos Pulsa via servidor (`/session` — criar/entrar/sair com código de 5 letras); o convidado espelha faixa (match por id → título+artista), posição (seek quando drift > 6s) e play/pause (`MirrorSync` + `SettingsActivity`). **Servidor configurável** em Configurações (LAN/túnel/VPS) — dá pra entrar de outro estado pela internet (cloudflared/ngrok -> opção "Servidor")

## Web player (pulsaweb) — remote/sync via telemetria
- [x] **Espelho da biblioteca**: o app manda a lista de músicas pro servidor (/songs, hash p/ reenviar só quando muda); o web mostra "Músicas do celular"
- [x] **Sync de fila/agora-tocando**: o app empurra estado (/state: tocando, faixa, posição, fila, volume) a cada 3s; o web consulta /remote e exibe
- [x] **Remote control pelo PC**: o web posta comandos (/cmd: play/pause/next/prev/seek/shuffle/repeat/volume); o app consulta e executa (`RemoteSync` → `Playback`/`AudioManager`)
- [x] **Botão "Buscar novidades" no web**: recarrega a lista do celular direto do servidor
- [x] Servidor serve o próprio web player em `/` (static) na porta 8081

## DJ Virgin (mais natural)
- [x] **Relembrar contexto** (4.2.5): memória persistida (`DjMemory`) — "meu telefone/whats é 99999-9999" é guardado e "qual é meu número?" é respondido; também pergunta/elembra do Bluetooth
- [x] **Perfis de humor** (4.2.5): dormir → fila calma curta (CALM); "bombar/anima/acelera/festa" → mix enérgico (WILD); padrão → balançado
- [x] **Comandos faltantes** (4.2.5): "toca só X" (`only`), "mistura com Y" (`mixwith`), "repete essa" (`repeat`) já existiam — agora com a memória e o humor novos também regem esses fluxos

## Áudio/qualidade de som
- [x] **Mais bandas/presets no equalizador** (4.3.0): EQ com bandas reais do aparelho + presets Acústico/Clássico/Sonoridade
- [ ] Filtro passa-banda no som ambiente (ex.: chuva mais fechada / oceano com mais presença)

## Uso/dados
- [x] **Espelhar DjLearn pra nuvem** (4.3.0): Virgin aprende entre sessões/dispositivos (push/pull `/djlearn`)
- [ ] Sugestões do DJ por dia da semana/horário (usar `play_log`)

## Engenharia
- [x] **Assinatura via GitHub Actions** (4.3.0): publica sem depender do PC (secrets)
- [x] **Verificar status SMTP** (4.3.0): senha de app testada no servidor; app mostra o status na tela de login
