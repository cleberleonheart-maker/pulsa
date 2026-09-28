# F1 · Fundação de mídia (Media3 + sessão + biblioteca)

> Plano detalhado da fase F1 da [central multimídia](../IDEIAS.md#central-multimídia-música--vídeo--rádio--podcast).
> Auditoria do código em 2026-09 (v5.8.1, `versionCode 120`).

## 1. Objetivo

Um único motor de reprodução, controlável de fora do app, e uma biblioteca que o Android inteiro enxerga.

**Entrega:**
- Um `ExoPlayer` (Media3) tocando música, rádio e vídeo na mesma fila, na mesma sessão.
- `MediaSessionService` + `MediaController`: notificação, tela de bloqueio, fone, Android Auto, Wear e Assistant passam a funcionar sem código extra.
- `MediaLibraryService` publicando a biblioteca (músicas, rádio, vídeos, podcasts) como árvore de mídia.
- Playlists/favoritas em Room (hoje SQLite cru) e trabalho de fundo em WorkManager (hoje `ThreadPool` + `Handler` + `Thread` soltos).
- **A Virgin não perde nada:** o contrato público de `Playback` continua igual.

**Fora do escopo da F1** (entram depois): HLS/DASH como produto final, Picture-in-Picture, podcasts como modelo, Cast, TV, Android Auto certificado. A F1 só deixa o terreno pronto.

## 2. Estado atual (o que existe)

| Peça | Onde | Nota |
|---|---|---|
| Motor da música | `playback/PlaybackService.kt:518` | `android.media.MediaPlayer`, um objeto novo por faixa |
| Crossfade | `PlaybackService.kt:502-546`, `633-674` | fading manual em `Handler` sobre `setVolume` |
| Notificação + sessão | `PlaybackService.kt:235-244`, `848-892` | `MediaSessionCompat` + `androidx.media.app.NotificationCompat.MediaStyle` (API antiga, não Media3) |
| Áudio 8D (pan LFO) | `PlaybackService.kt:196-212` | `setVolume(left, right)` a cada 150 ms |
| Velocidade/pitch (modo dança) | `PlaybackService.kt:274-282` | `MediaPlayer.playbackParams` |
| Loop A/B | `PlaybackService.kt:335-357`, `763-766` | markers em memória + seek no tick |
| Sleep mix (fade e pausa) | `PlaybackService.kt:378-402` | fade de 10 passos |
| EQ / BassBoost / karaokê | `audio/AudioFx.kt:59-82` | `android.media.audiofx.Equalizer` preso ao `audioSessionId` |
| Visualizador | `audio/MusicVisualizer.kt` | `Visualizer` preso ao mesmo `audioSessionId` |
| Rádio | `RadioActivity.kt:281-284` | `ExoPlayer` (Media3 1.3.1) **independente**, sem sessão |
| Vídeo | `VideoPlayerActivity` | `VideoView` |
| Biblioteca de músicas | `data/Library.kt:25-43` | **já consulta o MediaStore** (ok) |
| Playlists / favoritas / meta | `data/PlaylistDb.kt:12` | SQLite próprio `pulsa.db` v6 |
| Ajustes | `core/Settings.kt:73` | `SharedPreferences` (segredos em `EncryptedSharedPreferences`, linha 163) |
| Fundo | `core/ThreadPool.kt:8` | 3 threads fixas; `RemoteSync` faz polling por `Handler` (`RemoteSync.kt:68`); `UpdateService` cria `Thread` na mão |
| Fora do app | — | nenhum `MediaButtonReceiver`, nenhum `MediaBrowserService`, nada de Auto/Wear/TV/Cast |

**Dependências hoje:** `media3-exoplayer` e `media3-common` 1.3.1, `androidx.media:media` 1.7.0, sem `media3-session`, sem `media3-datasource-okhttp`, sem coroutines, sem Room, sem WorkManager. `minSdk 23`, `compileSdk 34`, `targetSdk 34`, AGP/Gradle 8.2.1, JDK 17 alvo.

## 3. O contrato que não pode quebrar

A Virgin, o hotword, o widget e a tela de reprodução dependem da superfície pública de `Playback` (`playback/Playback.kt:7-124`). Antes de trocar o motor, essa lista é o contrato de aceitação:

```
estado   currentSong, isPlaying, index, position, queue, shuffle,
         repeatAll, repeatOne, abActive, markerA, markerB, audioSessionId
comando  start(List<Song>, Int), toggle, pause, next, prev, seekTo,
         setShuffle, setRepeatAll, setRepeatOne, cycleRepeat, setSleepMix,
         setMarkerA, setMarkerB, clearAbLoop, setMicListening, refreshFx,
         refreshCurrentMeta
eventos  Listener{onSongChanged, onPlayStateChanged, onProgress}
acesso   Playback.service  ← acoplamento direto (ver §6)
```

Quem usa, e quanto (medido no código):

- `dj/MainVirgin.kt` — maior consumidor: fila por voz, humor, aprendizados (`setRepeatAll`, `setSleepMix`, `start`, `setShuffle`, `toggle`, `currentSong`)
- `dj/DjSession.kt` — monta os mixes, toca, pula, retoma (`start`, `next`, `toggle`, `queue`, `currentSong`)
- `ui/NowPlayingFragment.kt` — letra sincronizada, EQ com bandas reais (`Playback.audioSessionId`), A/B
- `ui/VirginHomeFragment.kt`, `ui/SongsTabFragment.kt`, `ui/PlaylistDetailFragment.kt`, `ui/TrendsFragment.kt`, `ui/SongActions.kt` — `start(...)`
- `sync/MirrorSync.kt` — pause/play do "ouvir juntos"
- `DjActivity.kt`, `MainActivity.kt` — fazem `startService` + `bindService` e setam `Playback.service`
- `SettingsActivity.kt` (4: 133, 141, 546, 547) e `widget/PulsaWidget.kt` (20, 29) — **puxam `Playback.service` direto**, fora do objeto

### 3.1 Auditoria do contrato em 5.9.3 (E0, 27/09)

A lista de §3 **continua válida**: `Playback.kt` tem exatamente os 12 estados, os 17
comandos e o `Listener` listados, nada foi acrescentado desde o 5.8.1. O que mudou é o
**tamanho do acoplamento** e o **número de furos** que a fachada vai ter de fechar:

| Medida | 5.9.3 |
|---|---|
| Arquivos que chamam `Playback.*` | 29 |
| Call sites `Playback.*` | ~250 |
| `MainVirgin` / `DjSession` / `NowPlayingFragment` | 66 / 59 / 32 |
| Acessos a `Playback.service` fora do objeto | 14, em 6 arquivos (12 externos + 2 dentro do próprio `PlaybackService`) |

Furos que a fachada precisa fechar **antes** de trocar o motor (não estão em §3 porque já
nasceram vazando):

1. `PlaybackService.applyDanceParamsForRefresh()` — `SettingsActivity.kt:547`. Não existe no
   objeto `Playback`: quem chama a Config precisa do serviço para religar os efeitos depois
   de trocar Equalizer/BassBoost. **Tem que entrar na fachada** (ex.: `Playback.refreshFx()`
   fazendo isso, ou um `setAudioFxMode` novo).
2. `PlaybackService.currentArt(): Bitmap` — `widget/PulsaWidget.kt:20,29`. O widget monta o
   `RemoteViews` com a capa. Uma fachada sobre `MediaController` não devolve `Bitmap`; ou o
   widget passa a pedir a capa pelo mesmo caminho que a tela Now Playing usa, ou a
   `currentArt` vira um método do serviço acessível por um handle explícito.
3. `Playback.service != null` como "o app já subiu?" — `MainVirgin.kt:1223` faz *polling* de
   8 × 250 ms esperando o bind. Com `MediaController` (conexão assíncrona) esse padrão
   continua válido, mas a fachada precisa de um equivalente honesto — senão o alarme da
   Virgin perde o `start` e a música não toca.

Nenhuma alteração de `Playback` é permitida sem mexer nesses 12 pontos e nos ~250 call sites
ao mesmo tempo. É por isso que E2 troca **a conexão**, não o motor.


## 4. Arquitetura alvo

```
UI / DJ / widget / sync          (Activities, Fragments, dj/*)
        │
        │  MediaController (async, thread-safe) + estado em cache local
        ▼
PulsaPlayback (object)           ← fachada: mesma API de §3, sem "service"
        │
        │  comandos do MediaController / MediaSession.Callback
        ▼
PulsaSessionService : MediaSessionService   (manifest: foregroundServiceType=mediaPlayback)
        │  ├── queue unificada (Song | Radio | Video | Podcast)
        │  ├── ExoPlayer único (Media3)
        │  └── AudioFx / Visualizer religados por audioSessionId
        │
        ▼
PulsaLibrary : MediaLibraryService   (árvore: Músicas / Rádio / Vídeos / Podcasts)
```

Decisões:
- **A fachada `Playback` vira `PulsaPlayback` sobre `MediaController`** e passa a funcionar mesmo com o app morto (Estado salvo/reconectado). Nenhum consumidor precisa mudar.
  > **Corrigido na prática (E2–E3b):** a fachada ficou como está — só deixa de conhecer o serviço
  > e passa a falar com o motor pela conexão. Ler posição continua por `Playback` (cache local),
  > não por round-trip de `MediaController`; a sessão Media3 vive dentro do mesmo serviço.
- **Um `ExoPlayer` só**, com `ExoPlayer.Builder().setAudioAttributes(..., handleAudioFocus = false)` — o foco de áudio continua gerenciado à mão pelo serviço, porque a Virgin precisa de *duck* e *pause sob perda de foco transitória* (`PlaybackService.kt:108-145`), comportamento que o `handleAudioFocus` padrão não reproduz.
- **Fila unificada já nasce aqui**: `MediaItem` com `mediaId` estável (`song:<id>`, `radio:<url>`, `video:<id>`), o que é pré-requisito do "Fila universal" e do `MediaLibraryService`.
- **Crossfade** deixa de ser `setVolume` em `Handler` e passa a ser `ExoPlayer` com `setVolume` controlado por `Player.Listener` no `onMediaItemTransition` — mesma sensação, menos código.
- **A/B, sleep mix e 8D** continuam na fachada, lendo posição do `MediaController` (com cache local para não depender de round-trip).

## 5. Execução passo a passo

Cada passo é um commit. Nada de etapa que deixe o app sem música no meio do caminho.

**E0 · Baseline (meio dia)**
- Rodar e anotar o resultado atual: `./gradlew test` (3 suítes: `DjCommanderTest`, `DjEngineTest`, `LyricsTest`) e `./gradlew assembleDebug`.
- Gravar um checklist de comportamento observável, que vira o teste manual de cada passo: crossfade, 8D, EQ por gênero, EQ custom 5 bandas, karaokê, A/B, "mix dormir" com fade, retocar após 3 s, resume, 8D, som ambiente com ducking, notificação, Last.fm scrobble, playskip no `play_log`, "ouvir juntos", widget, atualização automática.
- Salvar o APK atual (`5.8.1`) como referência de comparação.

**E1 · Dependências (meio dia)**
- `app/build.gradle.kts`: `media3-session:1.3.1`, `media3-exoplayer-hls`, `media3-exoplayer-dash`, `media3-datasource-okhttp`, `media3-common`; `androidx.work:work-runtime-ktx:2.9.0`; `androidx.room:room-runtime` + `room-ktx` + `ksp`; `org.jetbrains.kotlinx:kotlinx-coroutines-android`.
- Manter `minSdk 23` (Media3 1.3 e Room 2.6 suportam) e `compileSdk 34`.
- Compilar e rodar os testes: a meta do passo é **zero mudança de comportamento**.

**E2 · Fachada com a conexão (1 dia) — FEITO 27/09, `2a591d1`, CI verde**
- Escopo real, diferente do rascunho original: **só a conexão**, não a sessão. Criar a
  `MediaSessionService` aqui mostraria um segundo item nos controles do sistema, vazio e sem
  som, ao lado da notificação atual — ou seja, mudança de comportamento, que é a única coisa
  que este passo não pode trazer. A sessão entra no E3, quando o `ExoPlayer` for o motor de
  verdade e a sessão tiver o que controlar.
- `playback/PlayerLink.kt`: o "PlayerHolder" do plano. `PlayerLink` é o contrato com o motor;
  `ServicePlayerLink` repassa 1:1 o `MediaPlayer` legado. No E3 entra a implementação sobre
  `MediaController` — e nem a fachada nem os 29 consumidores mudam.
- `playback/Playback.kt`: `Playback.service` **deixou de existir**. A fachada é o único lugar do
  app que sabe que existe um motor. Os 29 arquivos que já chamavam `Playback` não mudaram.
- `Playback.connect(context) { avisa quando ligou }` devolve um `Bind`; `Playback.release(bind)`
  desfaz. Substituiu os dois `ServiceConnection` duplicados (MainActivity e DjActivity) e
  corrigiu de passagem o flag `bound` do DjActivity, que só virava no callback e portanto
  vazava o bind quando a tela saía antes de a ligação fechar.
- Os três furos de §3.1 fecharam: `Playback.isReady` (no lugar de `Playback.service != null`,
  em `MainActivity:422` e no polling da Virgin depois do alarme), `Playback.reapplyDanceParams()`
  (no lugar de `applyDanceParamsForRefresh()`) e `Playback.currentArt()` (no lugar do `Bitmap`
  lido do serviço). O widget parou de conhecer o serviço: pede as intenções à fachada.
- **Preservado de propósito:** quem se anuncia na fachada é o próprio `PlaybackService` no
  `onCreate`, e não o `bindService`, e o motor continua valendo depois do `unbind`. É disso que
  o widget depende — um `BroadcastReceiver` não conecta nada e ainda assim desenha a faixa
  tocando. Refazer isso é tarefa do E3, quando a sessão passa a ser dona do player.
- **Validação:** 11 arquivos compilam, as 6 suítes passam (68 testes) e o CI da PR #1 ficou
  verde. A contagem de bind não tem teste unitário (precisa de `Context` e `bindService` de
  verdade) — por isso o checklist do E0 no aparelho é a rede deste passo.


**E3 · Música no ExoPlayer (2–3 dias) — o passo crítico**
- `MediaItem` por `Song`; `setMediaItems(items, index, positionMs)`.
- Traduzir: `prepareCurrent` → `setMediaItem`; `onPrepared` → `Player.STATE_READY`; `onTrackEnded` → `onMediaItemTransition` + `onPlaybackStateChanged(STATE_ENDED)`; `onTrackError` → `onPlayerError` mantendo a contagem `consecutiveErrors`.
- `applyDanceParams` → `PlaybackParameters(speed, pitch)`; 8D e ducking → `player.setVolume(left, right)` (ExoPlayer tem `setVolume(float, float)`) ou `player.volume` + `AudioProcessor` de pan.
- **Religar efeitos:** `AudioFx.apply(context, exoPlayer.audioSessionId, genre)` e `MusicVisualizer.attach(exoPlayer.audioSessionId)` em `onAudioSessionIdChanged`/`onTracksSelected`, não mais em `prepareCurrent`. Sem isso o EQ morre silenciosamente (ver §6).
- Loop A/B no `emitProgress` continua, lendo `player.currentPosition` (o cache local da facade).
- **Validação:** o checklist do E0 inteiro, com atenção especial a EQ/8D/crossfade.

> **E3 · Execução real (28/09) — E3a feito, E3b em andamento**
>
> **E3a — motor no serviço (FEITO, `6b9bfb1`, CI verde):** `PlaybackService` agora cria um
> `ExoPlayer` próprio no `onCreate` (com `audioAttributes` e `handleAudioFocus = false`), sem a
> sessão. Cada `prepareCurrent` enfileira a fila inteira em `setMediaItems`; efeitos (EQ,
> visualizador), A/B, sleep mix e 8D realocados no `Player.Listener`; a fachada e os consumidores
> não mudaram (o `LocalBinder` repassa os mesmos métodos).
>
> **E3b — sessão Media3 no lugar da `MediaSessionCompat` (em andamento):**
> - Descoberta-chave na tag 1.3.1 (`MediaSessionService.onBind`): com *action nula* — que é como
>   o `Playback.connect` faz `bindService` — o base devolve `null` e o sistema chama `onNullBinding`,
>   matando a conexão que mantém a fachada/estado vivos. Por isso o `onBind` foi sobrescrito:
>   action `null` → `LocalBinder()` de sempre; qualquer outra → `super.onBind(intent)` (que devolve
>   o `sessionBinder` de mídia). Assinatura precisa ser `IBinder?` (o base é nullable).
> - `class PlaybackService : MediaSessionService()`, `session` criado via
>   `MediaSession.Builder(this, player!!)`, `onGetSession` devolve a singleton. `onStartCommand`
>   delega ao `super` depois dos `START`/`STOP`/`NEXT`/`PREV` próprios (o base já trata
>   `ACTION_MEDIA_BUTTON` e devolve `START_STICKY` para intent nulo). `onDestroy` libera a sessão
>   antes do player.
> - **Comandos externos:** não há `COMMAND_PLAY`/`COMMAND_PAUSE` nesse Player — play/pause vêm
>   como `COMMAND_PLAY_PAUSE` e o default do `Media3` executa a operação certa para o botão
>   (idempotente). `onPlayerCommandRequest` bloqueia (`RESULT_ERROR_UNKNOWN`) `SEEK_TO_NEXT*`/
>   `SEEK_TO_PREVIOUS*` (que virariam segundo pulo na fila recriada por `prepareCurrent`) e
>   `COMMAND_STOP` (no-op, paridade com o legado). Bookkeeping da reprodução vem de
>   `onIsPlayingChanged` → `syncExternalPlaybackState` (idempotente, mesmo conjunto de sempre).
> - **Metadata:** não existe `MediaSession.setMediaMetadata`/`Player.setMediaItemMetadata` na
>   versão — o snapshot vem dos `MediaItem`. `prepareCurrent` embute `MediaMetadata`
>   (title/artist/album, sem `setDurationMs`) em cada item; `publishMetadata` vira
>   `player.replaceMediaItem(index, ...)`.
> - **Notificação:** o `MediaSessionService` exige provider (devolver `null` dá NPE) e sobe/desce
>   o foreground sozinho. O provider devolve a **nossa** notificação (mesma `NOTIFICATION_ID` 10,
>   mesmas actions, `MediaStyle().setMediaSession(session.getSessionCompatToken())`) e guarda o
>   `Callback` para reposto após o artwork (`refreshNotification`). `ensureForeground` e o
>   `notify` manual saíram.
> - **Cleanup previsto (próximo passo):** remover a `MediaSessionCompat`, o `PlaybackStateCompat`
>   escrito à mão e o `updateSessionState` — o snapshot agora sai direto do player. `exported`
>   continua `false` (SystemUI acessa via registro do compat; publicar é tarefa do E7).
> - **Validação do ponto atual:** `PlaybackService.kt` + `PlayerLink.kt` + `Playback.kt` +
>   `MicBackoff.kt` + `MicCycle.kt` + `DjCommandListener.kt` compilam localmente contra o
>   `media3-session-1.3.1.jar` (baixado do Google Maven; Maven Central dá 404 para a `androidx.media3`)
>   pelo caminho fake no cache do Gradle; `MicCycleTest` + `MicBackoffTest` → **66 testes OK**.

**E4 · Rádio no mesmo motor (1 dia)**
- `RadioActivity` passa a enfileirar `radio:<url>` no mesmo serviço (hoje tem `ExoPlayer` próprio em `RadioActivity.kt:281`).
- Adicionar `media3-exoplayer-hls` já resolve o bug de `.m3u8` do radio-browser que hoje falha calado.
- **Validação:** rádio continua funcionando com o app em background e a notificação passa a mostrar a estação (hoje não mostra).

**E5 · Vídeo no mesmo motor (1–2 dias)**
- `MediaItem` `video:<id>` com `MimeTypes` detectado; `VideoPlayerActivity` vira só uma tela de player, sem `VideoView`.
- Legendas: `media3-extractor` (SRT/VTT) reaproveitando o modelo de `sync/Lyrics.kt`.
- `VideoLibrary.kt` (54 linhas) entra na árvore de mídia.
- **Validação:** vídeo local toca com áudio junto, e a música para de invadir o vídeo.

**E6 · MediaLibraryService (1–2 dias)**
- `playback/PulsaLibraryService : MediaLibraryService` publicando a árvore (raiz "Pulsa", filhos "Músicas", "Rádio", "Vídeos", "Playlists", "Favoritas", "Adicionadas recentemente").
- No manifest: `<service android:exported="true">` com `MediaSessionService`/`MediaLibraryService` — sem `exported=true` o Auto/Wear/Assistant não enxergam.
- Consultas usam `Library` (MediaStore) e `PlaylistDb` como estão; o Room da E8 só entra depois.
- **Validação:** aparece em "Fontes de mídia" do sistema e busca por voz do Google Assistant acha uma faixa.

**E7 · Fone, carro e botões (meio dia)**
- Botão de fone: **não** precisa de `MediaButtonReceiver` — a sessão Media3 já assina `KEYCODE_MEDIA_*` via o `MediaSession.Callback`. Só declara `android:exported="true"` no serviço e testa.
- Testar: play/pause/next/prev no fone, pause por chamada (ducking da Virgin), botão no carro com cabo.
- **Validação:** hoje o botão do fone **não faz nada** (não há `MediaButtonReceiver` nem `onKeyDown` de mídia no `MainActivity`); depois disso funciona.

**E8 · Playlists e favoritas em Room (1–2 dias)**
- Entidades: `PlaylistEntity`, `PlaylistSongEntity`, `FavoriteEntity`, `SongMetaEntity` — espelham `PlaylistDb.kt:14-53` 1:1.
- Migrar do `pulsa.db` v6 para Room **preservando os dados** (ler o SQLite antigo, escrever no Room, marcar migrado em `SharedPreferences`) — a Virgin já "aprendeu" playlists e favoritas do usuário; perder isso é pior que demorar.
- `PlaylistDb` continua existindo como camada de leitura durante um release, para rollback.

**E9 · Fundo em WorkManager (1 dia)**
- `RemoteSync` (polling por `Handler`, `RemoteSync.kt:68`) → `WorkManager` com `PeriodicWorkRequest` de 15 min (limite do sistema) + `OneTimeWorkRequest` quando a Virgin mexe em algo.
- `UpdateService` (`Thread` na mão, linhas 46 e 57) → `Worker`; `UpdateChecker` dispara o worker.
- `ThreadPool` continua existindo para o resto; a ideia é **parar de criar `Thread` cru**, não reescrever tudo.

**E10 · Faxina (meio dia)**
- Remover `android.media.MediaPlayer`, `VideoView`, `androidx.media:media` (a `MediaSessionCompat` vira Media3), `ThreadPool` onde não restar uso, e o `LocalBinder` de `MainActivity`/`DjActivity`.
- Checar `proguard-rules.pro` (hoje `isMinifyEnabled = false`, então risco é baixo) e o tamanho do APK (medir antes/depois — Media3 adiciona módulos).

## 6. Armadilhas (o que já quebra se ninguém olhar)

1. **EQ e visualizador morrem com o `sessionId` novo.** `AudioFx` e `MusicVisualizer` se ligam ao `audioSessionId` do `MediaPlayer` (`PlaybackService.kt:536-537`). No ExoPlayer o `audioSessionId` só existe **depois** que há faixa tocando, e pode mudar a cada faixa. Sem `onAudioSessionIdChanged` religando, o EQ "some" e o usuário acha que o app quebrou. É o defeito nº1 dessa migração.
2. **`AudioFx.apply` é chamado por fora.** `SettingsActivity` chama `Playback.refreshFx()` — a fachada precisa continuar expondo isso, senão mexer no EQ nas Configurações deixa de funcionar.
3. **A Virgin fala em cima da música.** `setMicListening` abaixa o volume em 0,35 (`PlaybackService.kt:179-194`) e o `announceInBackground` fala entre faixas (`PlaybackService.kt:551-588`). Se o `setVolume` do ExoPlayer receber a mesma sessão, o panning 8D e o ducking precisam ser **empilhados** (ganho único = produto), não sobrescritos — senão o 8D reseta o ducking e a Virgin fica incompreensível.
4. **Foco de áudio.** Não usar `handleAudioFocus = true` do ExoPlayer: o comportamento atual (pause em perda *transitória*, duck em *can duck*) está em `PlaybackService.kt:108-145` e é exigido pelo hotword.
5. **`Playback.service` é API pública de fato.** `PulsaWidget` e `SettingsActivity` leem o service diretamente; se a fachada mudar, esses dois precisam mudar junto, no mesmo commit.
6. **Estado "tocando agora" do sistema.** Hoje a sessão morre com o processo (`START_NOT_STICKY`, `PlaybackService.kt:254`). Ao virar `MediaSessionService`, o Android pode **reconectar sozinho** e retomar com a fila vazia — precisa de `onGetSession` + `onTaskRemoved` bem pensados, e de um teste explícito: "matou o app com música tocando e reabriu".
7. **Mídia do sistema e `MediaItem`.** `MediaItem` não aceita caminho de arquivo cru sem `Uri`/`File` válido no Android 7+; `song.path` (`Library.kt:22`) precisa virar `Uri.fromFile`/`content://` corretamente, senão dá `IllegalStateException` no `prepare`.
8. **Crossfade com `MediaPlayer` compartilhando o foco.** Depois do E3 a sensação precisa ser igual: fade-out da atual, fade-in da nova, sem gap audível. Regra: a mesma constante `FADE_STEPS = 10` e o mesmo `crossfadeMs` (`PlaybackService.kt:50`, `214`).
9. **Rádio e `minSdk 23`.** `media3-datasource-okhttp` e HLS funcionam em 23, mas testar num aparelho antigo (API 23–26) antes de considerar o passo pronto.
10. **Android 14 / FGS.** `FOREGROUND_SERVICE_MEDIA_PLAYBACK` já está no manifest (linha 16) — bom. Mas `MediaSessionService` inicia foreground em `onGetSession`; se a sessão for criada sem tocar, pode aparecer notificação fantasma. **Resolvido no E3b:** o provider devolve `buildIdleNotification()` enquanto `currentSong == null` — o sistema sobe a notificação de transporte do próprio Media3 (que é obrigatória), não a nossa, e ela sai quando a música toca. Iniciar foreground só no primeiro `play()` ficou desnecessário: quem decide o texto é o provider, não o serviço.

## 7. Como testar a cada passo

```bash
cd /root/pulsa
./gradlew test                 # as 3 suítes unitárias (DJ + letras)
./gradlew assembleDebug        # build do APK de teste
./gradlew installDebug         # instalar no aparelho conectado
```

Além disso, o checklist do E0 rodado inteiro a cada passo, mais:
- **EQ:** cada preset, o "Automático (por gênero)", o custom 5 bandas e o karaokê — conferindo que as bandas do aparelho mudam de verdade.
- **Instruments:** `adb shell dumpsys media_session` e `dumpsys audio` para ver a sessão e o `audioSessionId` atuais; comparar antes/depois.
- **Teste de morte:** tocar → `adb shell am force-stop com.pulsa.player` → reabrir; e tocar → tirar da tela; e tocar com o app em background por 5 min.

## 8. Pronto quando...

- [ ] Um `ExoPlayer` só toca música, rádio e vídeo na mesma sessão e na mesma fila
- [ ] Notificação, tela de bloqueio, botão de fone e busca do sistema funcionam
- [ ] "Fontes de mídia" do Android lista a biblioteca; Assistant acha uma faixa por voz
- [ ] EQ, 8D, ducking da Virgin, crossfade, A/B e sleep mix com o mesmo comportamento do checklist do E0
- [ ] Virgin, hotword, "ouvir juntos", widget, Playlists e Favoritas sem regressão (dados do `pulsa.db` preservados)
- [ ] `.m3u8` de rádio toca
- [ ] `MediaPlayer`, `VideoView` e `androidx.media:media` fora do `build.gradle.kts`
- [ ] Testes unitários verdes a cada commit

## 9. Esforço estimado

| Passo | Esforço | Risco |
|---|---|---|
| E0 | 0,5 dia | — |
| E1 | 0,5 dia | baixo |
| E2 | 1 dia | médio (conexão/sessão) |
| E3 | 2–3 dias | **alto** (EQ, 8D, crossfade, DJ) |
| E4 | 1 dia | baixo |
| E5 | 1–2 dias | médio |
| E6 | 1–2 dias | médio |
| E7 | 0,5 dia | baixo |
| E8 | 1–2 dias | médio (perda de dados) |
| E9 | 1 dia | baixo |
| E10 | 0,5 dia | baixo |
| **Total** | **10–14 dias** | |

Dá para entregar o essencial (E0–E4, E6, E7) em **5–6 dias**: já sai Android Auto/Wear/Assistant, fone, HLS no rádio e um motor só. E8 e E9 podem ir depois, com calma.
