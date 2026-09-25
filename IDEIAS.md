# Pulsa · Ideias para depois

> Lista de melhorias futuras (não implementadas ainda).
>
> **Plano grande:** [Central multimídia](#central-multimídia-música--vídeo--rádio--podcast) (música + vídeo + rádio + podcast, carro/TV/cast) — auditado em 2026-09.

- [x] **Redesign Terminal Radar** (4.14.0): repaginação completa da identidade — adeus synthwave (rosa/roxo/ciano). Terminal de varredura: paleta lima `#B6FF2E` + rubi de alerta `#FF2E4D` sobre preto-esverdeado (`#06100B`/`#0C1C13`); painéis e cards com canto cortado a 45° em toda a interface — chassi chanfrado via `ShapeAppearance.Pulsa.Radar` (tema) + `RadarPanelDrawable` (rows/inputs/decks/cápsulas) —; fundo com retículo de travação e blips (`bg_aurora`), sweep de radar animado nos anéis da tela de reprodução (`OrbitRingsView`), HUD monoespaçado (`TextAppearance.Pulsa.Hud`), avatares/ícones com gradientes refinados. Virgínia (locutora) e curiosidades entre as músicas seguem intactas

## ✓ Feito (2026)

- [x] **Pulsa Rewind** (4.12.0): resumo local no app (`Perfil → Pulsa Rewind`) — Top 10, tempo/tocadas, gráficos por mês/dia/hora e fatos, com filtros Sempre/30d/7d (`RewindActivity` + `DjLearn.rewind`); replay/ano no dashboard web fica em aberto

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
- [x] **Redesign Holográfico** (4.4.0): nova identidade visual futurista em toda a UI — fundo violeta profundo com brilhos magenta/ciano (`colors.xml`/`bg_aurora`), magenta elétrico como cor principal (harmonizado com a Virgínia), nav inferior flutuante em pílula (`bg_bottomnav` + `BottomNav`), mini-player holográfico (`bg_mini_player`), headers com base arredondada (`bg_header_rounded`), linhas de música em cartão (`bg_song_normal/selected`), orbes/login/DJ repaginados; avatar, Configurações e funções mantidos intactos
- [x] **Scrobble Last.fm** (4.4.0): envia now playing + scrobble do que você ouve quando vincula a conta (`sync/LastFm.kt` + `PlaybackService` + `Settings`)
- [x] **Retomar de onde parou** (4.4.1): "virgi, volta pra música" retoma a faixa/posição da última sessão, entendendo várias frases (volta a tocar, continua de onde parou, recomeça, retoma) — `MainVirgin` + `DjSession` + `DjMemory`
- [x] **Fade-out no "mix dormir"** (4.5.0): o mix CALM toca o set uma vez e, em vez de repetir seco, o volume diminui gradualmente por ~9s e pausa — `PlaybackService` + `DjSession` + `MainVirgin`
- [x] **Sem repetir na sessão** (4.5.0): músicas já tocadas enquanto o app está aberto saem dos próximos mixes (com reposição em bibliotecas pequenas) — `DjEngine` + `DjSessionMemory`
- [x] **Reação da Virgínia** (4.5.0): a Virgin comemora um like, zoa um skip/dislike e varia o "próxima", alternando frases em pt/en/es — `DjReactions` + `DjVoice`
- [x] **Comando encadeado** (4.5.0): "virgi, toca X e depois pausa" — duas ações numa fala, executadas em sequência — `DjCommander` + `MainVirgin` + `DjSession`
- [x] **"O que toquei ontem"** (4.5.0): "virgi, o que toquei ontem" lista as faixas do `play_log` do dia anterior por voz — `DjLearn.playedOnDay` + `DjCommander`

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

## A fazer (propostas)

> Ordenadas por prioridade (mais alta primeiro).

### Esqueleto novo / home dashboard (em andamento 5.0)
- [x] **Bottom nav + home dashboard**: barra inferior (Virgin/Músicas/Biblioteca/Buscar/Perfil) no lugar das abas de texto; home com seções em vez da grade de cards; Biblioteca como hub com seções Álbuns/Artistas/Favoritas/Playlists/Vídeos; Now Playing virou Activity própria; DJ/Rádio saíram da toolbar para a home
- [x] **Card "Tocando agora/continuar"** na home: retoma a faixa atual com um toque
- [x] **Música favorita do avatar**: a faixa mais tocada vira a favorita da Virgin/Victor e aparece no deck da home (`AvatarFavorites` + `DjLearn.topSongs`)
- [x] **Virgin FM abre pela favorita**: o Rádio Virgin agora anuncia e toca primeiro a música favorita do avatar antes de embaralhar a biblioteca (`TamiRadio.start` + primeira posição da fila)
- [x] **Botão de saudação do avatar** na home: pílula neon "👋 Bom dia/Boa tarde/Boa noite" que fala a saudação com a voz do avatar e atualiza o texto no botão (`btn_greet` + `home_greet_day/after/night` + a voz)
- [x] **Casal dançando na favorita**: quando a música favorita do avatar toca, Virgin e Victor aparecem juntos dançando ao lado da capa no Now Playing (`np_virgin_dance_b` + `DancingVirginView.setForceMale` + `AvatarFavorites.favoriteId`)
- [ ] **Lista da fila (Queue)** no Now Playing: arrastar a capa para cima abre a fila atual com reordenação — `NowPlayingActivity` + `Playback.queue`
- [ ] **Painel de letras sincronizadas** embutido no Now Playing (hoje é dialog) — `Lyrics` + `NowPlayingActivity`
- [ ] **Mini visualizador de áudio sempre visível** no Now Playing (sem ter que abrir o EQ) — `AudioVisualizerView` + `MusicShaderView`
- [ ] **Seletor de skin rápido na home**: troca o tema sem ir para Configurações — `Settings` + `VirginHomeFragment`
- [ ] **Estatística de reprodução resumida** no card da Virgin (tocadas/tempo) — `DjLearn` + `VirginHomeFragment`
- [ ] **Favoritas do mês**: atalho que monta mix só com músicas curtidas do mês — `DjEngine` + `PlaylistDb`
- [ ] **"Adicionadas recentemente"**: sessão horizontal de capas na home — `Library.allSongs` + `RecyclerView`

### DJ Virgin / voz
- [ ] **Despertador da Virgínia**: "virgi, me acorda às 7h com chuva" — alarme toca som ambiente + uma faixa escolhida na hora definida; também sleep-timer falado ("para em 20 min") — `DjCommander` + `AlarmManager`
- [ ] **Virgin bilíngue**: responde na língua da pergunta (pt/en/es), não só no idioma do app — `DjVoice` + strings
- [ ] **Resumo semanal falado**: Virgin resume o `play_log` ("você ouviu 4h de Rock, 12x essa música") com voz, em vez de só dashboard web — `DjVoice` + `DjActivity`
- [ ] **Trivia "quanto você conhece a Virgínia"**: quiz usando `DjMemory`/`DjLearn` — "qual minha música favorita?", reconhecimento por trecho de faixa

### Áudio / UX
- [ ] **Mistura ambiente + música**: "virgi, toca chuva com a música" — som ambiente e faixa somando no mesmo fone (ganho misturado) — `Ambient` + `Playback` + `DjCommander`
- [ ] **Karaokê sincronizado de verdade**: letras com highlight palavra-a-palavra (falta o sync temporal ao `Lyrics.kt`) na tela da dança — `DancingVirginView` + `Lyrics`
- [ ] **Fila por energia**: "virgi, toca pra malhar/dirigir" monta fila pelo BPM/gênero detectado da faixa — `Library` + `DjCommander`
- [ ] **Notificação com seek + letra**: mini-player na notificação com barra de progresso arrastável e letra da faixa — service + `NotificationCompat`
- [ ] **Importar/exportar playlist M3U**: compartilha/recebe listas de outros players — `Library`
- [ ] **Crossfade/fade sem gaps**: detectar BPM por DSP e transicionar em batida entre faixas (sem silêncio) — modo festa contínuo — `Playback` + `AudioFx`

### Conexão / sincronia
- [ ] **Convidado pede música na sessão**: dentro do "Ouvir juntos", o convidado manda faixa por voz e o host toca — `MirrorSync` + `DjCommander`
- [ ] **QR code pra sessão**: host mostra QR, convidado escaneia em vez de digitar o código — `MirrorSync` + `SettingsActivity`

### Dados / memória
- [ ] **Backup/restaurar preferências**: exporta/importa `DjLearn` + `DjMemory` + presets de EQ num arquivo (ou no servidor) — `SettingsActivity`
- [ ] **Limpeza guiada por voz**: lista músicas nunca tocadas/duplicadas e apaga com confirmação — `DjMemory` + `DjCommander`

### Polimento
- [ ] **Widget de tela inicial**: mini-player da Virgin com play/pause/próxima sem abrir o app — `AppWidgetProvider`

### Nova leva (set/2026)
- [x] **Playlist dinâmica por voz com condições** (5.6.0): "virgi, toca rock que não ouço há 2 meses", "monta uma fila de pagode que toquei pouco" ou "as que pulei menos de 3 vezes" — a Virgin filtra a biblioteca na hora (gênero, tempo sem ouvir, nº de toques/pulos, favoritas, nunca-tocadas) e monta a fila em tempo real — `DjCommander.dyn` + `DjLearn.lastPlayedMap` + `MainVirgin.virgDynamicQueue`
- [x] **Volume do ambiente por voz** (5.5.0): "virgi, chuva mais alta/mais baixa" regula o ganho do som ambiente sem depender do botão físico — `Ambient` + `DjCommander`
- [x] **Ducking automático do ambiente** (5.5.0): quando a Virgínia fala, o som ambiente abaixa sozinho (20%) e volta ao terminar — `Ambient.setDuck` + `MainVirgin`
- [x] **Novos sons ambientes** (5.5.0): +4 geradores — Chuva e trovões, Fogueira, Riacho e Pássaros na manhã (antes eram 8, agora 12) — `Ambient`
- [x] **Som ambiente em estéreo 3D** (5.5.1): geradores agora saem em estéreo com panning dinâmico por modo — trovoada rola de um lado pro outro, riacho pende à direita, fogueira estala no centro com balanço — `Ambient.panFor` + `CHANNEL_OUT_STEREO`
- [x] **Avatar aprende o que você evita** (5.7.0): além do dislike, a Virgin deixa de fora dos próximos mixes (wild/sleep/favoritas do mês/dinâmica/década/mix com artista) as faixas que você sempre pula (3+ skips e skips ≥ toques, sem ser dislike) e explica por voz "deixei N de fora porque você sempre pula" — `DjLearn.avoided` + `DjEngine.Learn` + `MainVirgin.avoidNote`
- [ ] **Hotword mãos-livres**: acionar "virgi" sem abrir o app (reconhecimento contínuo leve em background, opção nas Configurações) — `DjVoice` + `DjActivity`
- [ ] **Capa sincronizada no Ouvir juntos**: o convidado vê a arte de capa da faixa atual além de faixa/posição/play-pause — `MirrorSync` + pulsaweb
- [ ] **Nota prévia da faixa**: antes de tocar uma faixa que você costuma pular, a Virgin avisa "essa aí você costuma pular; pulo?" — `DjMemory` + `DjEngine`
- [ ] **Pulsa Cloud: backup de playlists/favoritas**: espelha as playlists (além do DjLearn) pelo servidor e restaura em outro celular — `PlaylistDb` + `RemoteSync`
- [ ] **Interval timer vocal**: "virgi, cronometra 3 rounds de 1 min com chuva" — timer de treino com o ambiente de fundo e contagem falada — `DjCommander` + `Ambient`
- [ ] **Tela de dirigir**: modo fullscreen com capa gigante + botões grandes, tudo controlado por voz (sem ler nada) — `DjActivity` + modo
- [ ] **Replay do ano falado**: "virgi, qual foi minha música do ano?" — resumo anual narrado com seus tops (o semanal já existe) — `DjLearn.rewind` + `DjVoice`

### Novas ideias (set/2026)
- [x] **Túnel do tempo por década** (5.7.0): "virgi, toca anos 80", "anos 2000", "década de 90" — monta fila filtrando por década/faixa de anos da biblioteca (``DjCommander.decadeQuery`` + `MainVirgin.virgDecadeMix`); aceita dígitos ("anos 80"/"anos 2000") e por extenso ("anos oitenta")
- [ ] **Shazam interno**: "virgi, qual é essa música?" grava um trecho, reconhece e já pergunta se quer tocar — `DjRecognizer` + `DjCommander`
- [ ] **Cartão de música pra compartilhar**: gera imagem da faixa (capa + arte Pulsa) pra postar, estilo Wrapped pequeno — `AvatarFavorites` + share
- [ ] **Rádio por cena**: "virgi, toca pra malhar/estudar/viajar/dirigir" — preset de cena juntando BPM + gênero + ambiente de fundo + fila curada — `DjCommander` + `Ambient` + `Library`
- [x] **Rádio com memória**: quando uma faixa está há dias sem ser tocada, a Virgin anuncia "essa você não ouvia há X dias/semanas/meses" (ou "ainda não ouviu!" na primeira vez) antes do "tocando agora" — `DjSession` + `DjLearn.lastPlayedMap` (5.7.0)
- [ ] **Resumo do fim de semana**: na segunda, a Virgin narra o que você mais ouviu no sábado/domingo — `DjVoice` + `play_log`
- [ ] **Despertador progressivo**: variação do alarme — o volume sobe gradual + som ambiente em vez de estourar — `AlarmManager` + `Ambient`
- [ ] **Karaokê de viagem**: letra sincronizada em landscape fullscreen (pra TV/modo passeio) — `Lyrics` + `DancingVirginView`

## Web player (pulsaweb) — a fazer (app separado da web)
- [ ] **Controle do "Ouvir juntos" pelo PC**: o web player entra na sessão com o código de 5 letras e espelha/controla sem celular — `MirrorSync` + pulsaweb
- [ ] **Pulsa Rewind / replay do ano na web**: replay das músicas mais tocadas + gráfico de ano completo no dashboard web, gerado da telemetria (`/stats` já agrega) — o resumo já existe no app (4.12.0)

## Central multimídia (música + vídeo + rádio + podcast)

> Auditoria do código em 2026-09: o que já existe e o que falta para o Pulsa virar uma central de mídia de verdade (uma app só para áudio, vídeo, rádio e podcast, com integração com carro/TV/fone e download offline).

### Já existe (base boa)
- [x] Áudio com serviço de background, MediaSession e notificação estilo (`playback/PlaybackService` + `MediaPlayer`), crossfade, A/B, sleep timer, scrobble Last.fm
- [x] Rádio em streaming via ExoPlayer + busca no radio-browser (`RadioActivity` + `dj/TamiRadio`)
- [x] Vídeo local (`VideoPlayerActivity` + `ui/VideosTabFragment`)
- [x] Biblioteca, playlists, favoritas e letras (`data/Library.kt`, `data/PlaylistDb`, `sync/Lyrics`)
- [x] Importação de arquivos (SAF/USB) via `dj/DjSession` + `media/GalleryScanner`
- [x] Sync com servidor próprio e PWA web como segunda tela (`sync/RemoteSync`, `sync/MirrorSync`, `web/`)
- [x] DJ/Virgin, hotword, memória e aprendizado de gosto (o diferencial do app)

### Bloqueadores (o que impede de ser "central")
- [ ] **Playback em três lugares**: `MediaPlayer` (música), ExoPlayer (rádio) e `VideoView` (vídeo) — sem Media3 unificado não há HLS/DASH, sem `MediaSessionService` não há Android Auto/Wear/TV/Assistant, sem `MediaButtonReceiver` não há botão de fone (`playback/Playback.kt`, `RadioActivity.kt:188`, `VideoPlayerActivity.kt`)
- [ ] **Biblioteca exposta ao sistema**: as músicas já vêm do MediaStore (`data/Library.kt:25`), mas playlists/favoritas/meta vivem num SQLite próprio (`data/PlaylistDb` v6) e nada disso é publicado — sem `MediaLibraryService` não há Android Auto, Wear, Assistant, busca do sistema nem "tocando agora" do Android
- [ ] **Vídeo é de arquivo local só**: sem HLS/DASH, sem legenda, sem Picture-in-Picture, sem gesture de seek, sem modo paisagem/TV
- [ ] **Download sem fila**: HTTP direto → MediaStore, sem retomar, sem `DownloadManager`, sem download de streaming, e bloqueia URLs de YouTube/Spotify (`media/MusicDownloader.kt`, `SettingsActivity.kt:578`)
- [ ] **Podcast inexistente**: só um `DIRECTORY_PODCASTS` solto no scan; sem modelo de feed, assinatura, episódio, capítulos, auto-download (`media/GalleryScanner.kt:31`)
- [ ] **Sem carro/TV/cast**: sem Cast SDK, `androidx.car`, `androidx.tv.leanback`; USB e Bluetooth são só texto, não integração
- [ ] **Sync limitado**: polling com `ThreadPool`, sem Room/WorkManager; backup cobre aprendizado do DJ e EQ, não biblioteca/playlists (`SettingsActivity.kt:677`, `sync/RemoteSync.kt`)

### Fases (ordem que destrava o resto)
- [ ] **F1 · Fundação** ([plano detalhado em `docs/F1-FUNDACAO-MEDIA3.md`](docs/F1-FUNDACAO-MEDIA3.md)): migrar o playback para Media3 + `MediaSessionService`/`MediaLibraryService`; MediaStore como fonte única; Room para playlists/favoritas; WorkManager para sync e downloads. Só isso já entrega Android Auto, Wear, fone, system controls e busca do sistema. Fazer por camadas (trocar o motor mantendo a API atual) para não quebrar DJ/hotword
- [ ] **F2 · Vídeo e streaming**: ExoPlayer/Media3 para vídeo local + HLS/DASH, legenda (SRT/VTT), PiP, gesto de seek, downloaded offline, encoder de download
- [ ] **F3 · Podcast e séries**: modelo de feed e assinatura, episódios com data/duração, capítulos, marcar ouvido, auto-download por regra, gerenciar storage
- [ ] **F4 · Casa, carro e TV**: Cast SDK (Chromecast/speaker), Android Auto (template de mídia), TV leanback (10 feet, foco/remote), equalizador e segundo display
- [ ] **F5 · A central em si**: busca e filas unificadas entre áudio/vídeo/podcast, "continuar de onde parou" cruzando mídias, Download Center com fila/pausa, handoff celular↔PC↔TV (a PWA em `web/` já é a superfície), atalhos (shortcuts) e compartilhamento (M3U, áudio, vídeo)
- [ ] **Transversal**: Room + DataStore no lugar de SQLite/prefs, `kotlinx.coroutines` no lugar de `ThreadPool`, backup completo da biblioteca (hoje só DJ/EQ) com restauração em outro aparelho, e um banco de mídia (`MediaLibraryService`) como fonte para Assistant e Android Auto

### Novas ideias para a central
- [ ] **Fila universal**: uma única fila aceitando música, vídeo, episódio e rádio, com reordenação por voz ("virgi, joga o vídeo pro fim") e histórico do que já tocou de cada tipo (`Playback.queue` + novo modelo)
- [ ] **"Continuar de onde parei" cruzando mídias**: a home mostra um card só com a última coisa tocada (música OU vídeo OU episódio) e a Virgin retoma falando "voltando pro episódio de ontem, você parou no minuto 34" (`DjMemory` + `MainVirgin`)
- [ ] **Busca unificada com filtros**: um campo de busca que casa música, vídeo, podcast, artista e episódio, com chips de filtro (baixado, não ouvido, duração, data) (`SearchActivity` + `SearchAdapter`)
- [ ] **Sessão com objetivo**: "virgi, monta uma sessão de 40 minutos pra dormir: 2 músicas calmas e 1 podcast" — a Virgin escolhe por duração somada, não só por gênero (`DjEngine` + `Ambient`)
- [ ] **Download Center**: fila com pausar/retomar/cancelar, Velocidade, "só no Wi-Fi", limite de espaço por mídia e aviso antes de encher o cartão (`MediaDownloader` + WorkManager)
- [ ] **Baixar por voz**: "virgi, baixa esse episódio pra ouvir no carro" / "limpa o que tá ocupando espaço" (`DjCommander` + Download Center)
- [ ] **Limpeza automática do offline**: apaga o que não foi ouvido há X dias (com aviso de voz antes), liberando cartão sozinho (`Settings` + WorkManager)
- [ ] **Download de streaming com API oficial**: iTunes/Spotify/Podcast Index/ytdlp local em vez de raspar página — hoje o app bloqueia essas URLs de propósito (`SettingsActivity.kt:578`)
- [ ] **Pular propaganda de podcast**: detecta silêncio/mesma faixa no começo do episódio e corta, com opção "não pular" (`Media3` + `DjLearn` do listener)
- [ ] **Modo podcast no EQ**: preset de voz (corta 2–4 kHz e realça presença) separado do preset de música, porque podcast e música não se mixam igual (`AudioFx.presetForGenre` + perfil de fonte)
- [ ] **Transcrição e busca falada no podcast**: transcrever o episódio (Whisper local ou no servidor) e perguntar "virgi, o que falaram sobre bitcoin nesse episódio?" — com resposta em voz e o trecho citado (`Lyrics` → novo módulo de transcrição + `DjCommander`)
- [ ] **Sumário falado de episódio**: a Virgin resume o episódio em 3 frases ao terminar ("o que ficou") e sugere o próximo da mesma série (`DjVoice` + resumo do Gemini já usado em `DjSuggest`)
- [ ] **Capítulos e marcadores**: navegação por capítulo dentro de podcasts e vídeos longos, com marcadores por voz ("virgi, pula pro capítulo 3") (`Lyrics` modelo de capítulos + `DjCommander`)
- [ ] **Picture-in-Picture e player flutuante**: sair do app continua o vídeo numa janela PIP, com retorno ao audio ao fechar (`VideoPlayerActivity` + Media3)
- [ ] **Legenda no vídeo**: carregar SRT/VTT, busca por hora, fonte e tamanho ajustáveis, e legenda "ouvir junto" (vira letra no Now Playing) (`Lyrics` reaproveitado)
- [ ] **Vídeo por voz**: "virgi, continua o filme", "pausa e volta 30s" — igual o "retomar" que já existe para música (`MainVirgin` + `VideoPlayerActivity`)
- [ ] **Velocidade e saltinho**: 1.2x/1.5x/2x + pulo de 15s nos episódios e vídeos, com a Virgin ganhando voz se você usa muito (`Playback` + `DjLearn`)
- [ ] **Android Auto de verdade**: abas por mídia (música/rádio/podcast/vídeo), voz do Google Assist ("toca meu podcast"), e navegador para o resto — exige `MediaBrowserService` e passar pelo review do Google
- [ ] **Wear OS**: app mínimo com play/pause, favoritar e "tocar X" no pulso, espelhando o estado pelo `MediaSession`
- [ ] **TV 10 feet**: home por capas, foco com controle remoto, busca por voz do OK Google e fila da Virgin aparecendo na TV (`androidx.tv.leanback`)
- [ ] **Cast/AirPlay de verdade**: Mandar música, vídeo e podcast para Chromecast/speaker com a capa e o controle voltando pro celular (`Cast SDK` + `MediaSession`)
- [ ] **Handoff entre aparelhos**: começa a ouvir no fone, aparece no carro; começa no carro, volta pro celular — reaproveitando o servidor que já existe (`RemoteSync` + `MirrorSync`)
- [ ] **Nuvem de mídia de verdade**: além do `/songs`, o servidor guarda playlists, favoritos, progresso de cada mídia e o que está baixado — a PWA vira segunda tela completa, tocando o que você quer pelo navegador (`RemoteSync` + `web/`)
- [ ] **Economia de dados e bateria**: baixa resolução de vídeo em dados móveis, pause de vídeo na tela desligada, e "não baixar nada sem Wi-Fi" por padrão (`Settings` + `NetworkCapabilities`)
- [ ] **Atalho na tela inicial** para "Baixados offline", "Continuar", "Novo episódio" e "Virgin FM" (`AppWidgetProvider` + shortcuts estáticos)
- [ ] **Modo família/kids**: filtro por faixa/podcast com senha, volume travado e sem rádio — útil com o app em carro compartilhado
- [ ] **Cartão compartilhável unificado**: música, episódio ou vídeo viram um card com capa pra mandar no zap (`AvatarFavorites` reaproveitado)

### Armadilhas conhecidas (antes de começar)
- [ ] **O EQ é preso ao `sessionId` do `MediaPlayer`**: ao trocar por Media3 o `sessionId` muda e o `Equalizer`/`BassBoost` de `audio/AudioFx` param de aplicar — precisa religar por sessão e testar banda a banda antes de considerar a migração pronta
- [ ] **DJ e hotword tocam o player diretamente**: `dj/DjSession`, `dj/MainVirgin` e `HotwordBridge` assumem a API de `Playback`. Migrar o motor sem manter essa API é o jeito mais rápido de quebrar a Virgin — daí a estratégia em camadas da F1
- [ ] **Permissões e política**: `MANAGE_EXTERNAL_STORAGE`/acesso total de arquivos e `FOREGROUND_SERVICE_MICROPHONE`pedem justificativa; o `build.yml` só assina release, mas publicar na Play exige ficha de dados e política de privacidade
- [ ] **Rádio em `.m3u8` pode não tocar** hoje: falta o módulo HLS do ExoPlayer, então algumas URLs de stream do radio-browser falham silenciosamente — já vale corrigir na F1
- [ ] **Sync atual depende de rede cleartext e servidor de pé**: a F5 assume um servidor real (VPS/túnel) e não só LAN; testar em 4G é obrigatório antes de vender como "nuvem"
