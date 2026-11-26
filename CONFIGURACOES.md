# Configurações do Pulsa

Documentação organizada de todas as preferências e configurações do projeto.

## 1) Android (app/)

### 1.1 Ponto central
- **`app/src/main/java/com/pulsa/player/core/Settings.kt`** — `object Settings`. Único ponto central, organizado em seções comentadas (avatares, infraestrutura, sincronização, secrets, aparência, áudio, alarmes, DJ/IA, mídia, downloads/kids, TV, gestos/EQ, Last.fm, estado, cache). API inalterada.
- **Persistência não sensível:** um único `DataStore<Preferences>` em `files/datastore`, com namespaces por nome legado. `core/DataStorePreferences.kt` expõe a compatibilidade `SharedPreferences`, mantém cache síncrono em memória e serializa as escritas com `updateData`.
- **Namespaces DataStore:** `pulsa_settings`, `pulsa_profile`, `pulsa_blacklist`, `pulsa_radio_stations`, `pulsa_stream_history`, `pulsa_dj_memory`, `subtitle_config`, `pulsa_update`, `pulsa_last_changelog` e `pulsa_last_installed`.
- **Exceções:** `pulsa_account` permanece em `SharedPreferences` normal; `pulsa_secrets` permanece em `EncryptedSharedPreferences` via Android Keystore (AES256-SIV/GCM). Todo o domínio `sharedpref` fica fora do backup; só o DataStore, no domínio `files`, viaja.
- **Backup:** `res/xml/backup_rules.xml` (Android 11 e menores) e `res/xml/data_extraction_rules.xml` (Android 12+) excluem `domain="sharedpref" path="."` no `cloud-backup` e no `device-transfer`. A seção `cross-platform-transfer` (Android 16 QPR2, exige `platform-specific-params` com `bundleId`/`teamId` de um app iOS pareado) não é declarada: o Pulsa não tem app iOS, e um XML rejeitado pelo parser do sistema faria o backup voltar ao padrão "inclui tudo". Se um dia existir app iOS, essa seção precisa ser criada junto com a migração dos formatos.
- **Migração:** a migração de dados v3 copia os XMLs legados, exclui `Settings.secretKeys` e grava o estado no DataStore. Os XMLs ficam apenas como cópia local de recuperação, fora do backup; se o DataStore falhar, as escritas vão para eles e são reconciliadas no arranque seguinte.
- Sem PreferenceFragmentCompat.

### 1.2 Chaves principais (Settings.kt)

#### Gerais / Dispositivo / Sincronização
| Chave | Tipo | Getter/Setter | Default | Obs |
|---|---|---|---|---|
| `device_id` | String | `deviceId()` | UUID gerado | |
| `mirror_code` / `mirror_host` | String/Bool | `mirrorCode()`, `mirrorHost()` / `setMirrorCode()`, `setMirrorHost()` | `""` / `false` | Ouvir juntos |
| `remote_control_code` | String | `remoteCode()` / `setRemoteCode()` | `""` | Controle remoto |
| `handoff_auto_assume` | Bool | `handoffAutoAssume()` / `setHandoffAutoAssume()` | `false` | |
| `server_base` | String | `serverBase()` / `setServerBase()` | `""` | Modo LAN/Online/Custom |
| `tunnel_url` | String | (const declarada) | — | **Não usado** |

#### Aparência
| Chave | Tipo | Getter/Setter | Default | Obs |
|---|---|---|---|---|
| `accent` | String | `accent()` / `setAccent()` | `purple` | Temas em `res/values/themes.xml` |
| `animated_bg` | Bool | `animatedBg()` / `setAnimatedBg()` | `true` | |
| `stars_on` | Bool | `starsOn()` / `setStarsOn()` | `true` | |
| `sort_alpha` | Bool | `sortAlphabetical()` / `setSortAlphabetical()` | `false` | |
| `language` | String | `language()` / `setLanguage()` | `pt` | pt/en/es/pt-PT |
| `player_skin` | String | `skin()` / `setSkin()` | `neon` | off/neon/aurora/particles/nebula |

> **Teclado Pulsa (IME):** a linha "Teclado Pulsa" em Aparência não é uma preferência — ela mostra o estado do teclado (`Não ativado` / `Ativado` / `Em uso`, via `InputMethodManager`) e abre um diálogo com as duas ações de ativação: as configurações de teclado do sistema e o seletor de teclado. O teclado em si (`com.pulsa.player.ime`) roda em processo próprio (`:ime`) e lê `language`/`accent` do espelho legado do DataStore, porque o DataStore Preferences não é multi-processo.

#### Áudio / EQ / Espacial
| Chave | Tipo | Getter/Setter | Default | Obs |
|---|---|---|---|---|
| `equalizer_on` | Bool | `equalizerOn()` / `setEqualizerOn()` | `false` | |
| `custom_eq_on` / `custom_eq_bands` | Bool/String | `customEqOn()` / `setCustomEqOn()`, `customEqBands()` CSV | `false` / `"0,0,0,0,0"` | |
| `audio_quality` | String | `audioQuality()` / `setAudioQuality()` | `default` | Presets + "automático por gênero" |
| `audio_8d` | Bool | `audio8d()` / `setAudio8d()` | `false` | |
| `spatial_3d` / `spatial_3d_depth` | Bool/Int | `spatial3d()`, `spatial3dDepth()` | `false` / `60` | 0..100 |
| `spatial_surround` / `spatial_surround_intensity` | Bool/Int | `surround()`, `surroundIntensity()` | `false` / `50` | 0..100 |
| `crossfade` | String | `crossfade()` / `setCrossfade()` | `off` | off/short/medium/long |
| `ambient_tone` | Float | `ambientTone()` / `setAmbientTone()` | `0.5` | 0..1 |
| `karaoke_on` | Bool | `karaokeOn()` / `setKaraokeOn()` | `false` | |
| `video_speed` / `podcast_speed` | Float | `videoSpeed()`, `podcastSpeed()` | `1f` | 0.5..2.0 |
| `data_saver` | Bool | `dataSaver()` / `setDataSaver()` | `true` | |
| `skip_silence` | Bool | `skipSilence()` / `setSkipSilence()` | `true` | Pular silêncios no podcast |

#### DJ / IA / Voz
| Chave | Tipo | Getter/Setter | Default | Obs |
|---|---|---|---|---|
| `dj_voice` | Bool | `djVoice()` / `setDjVoice()` | `true` | |
| `dj_radio` / `tami_radio` | Bool | `djRadio()`, `tamiRadio()` | `true` / `false` | |
| `avatar_style` | Int | `avatarStyle()` / `setAvatarStyle()` | 0-2 | Virgin/Victor/Vera (compat com `avatar_masculino`) |
| `dj_source` / `dj_intensity` | String | `djSource()`, `djIntensity()` | `all` / `balanced` | all/favorites, calm/balanced/wild |
| `gestures_on` | Bool | `gesturesOn()` / `setGesturesOn()` | `false` | |
| `hotword` | Bool | `hotword()` / `setHotword()` | `false` | Mãos-livres "virgi" |
| `gemini_on` / `gemini_key` (secret) | Bool/String | `geminiOn()`, `geminiKey()` | `false` / `""` | |
| `podcast_summary` | Bool | `podcastSummary()` / `setPodcastSummary()` | `false` | Resumo falado ao terminar episódio |
| `podcast_prefetch` | Bool | `podcastPrefetch()` / `setPodcastPrefetch()` | `true` | Adiantar próximo episódio |
| `rec_token` | String | `recToken()` / `setRecToken()` | `""` | Reconhecimento |

#### Download / Offline / Kids
| Chave | Tipo | Getter/Setter | Default | Obs |
|---|---|---|---|---|
| `download_wifi_only` | Bool | `downloadWifiOnly()` / `setDownloadWifiOnly()` | `true` | |
| `download_limit_mb` | Int | `downloadLimitMb()` / `setDownloadLimitMb()` | `0` | 0=sem limite |
| `offline_cleanup_days` | Int | `offlineCleanupDays()` / `setOfflineCleanupDays()` | `0` | 0=desligado (7/30/60) |
| `kids_mode` | Bool | `kidsMode()` / `setKidsMode()` | `false` | Protegido por PIN |
| `kids_pin` | String | `kidsPin()` / `setKidsPin()` | `""` | SHA-256 hash |

#### Secrets (EncryptedSharedPreferences - `pulsa_secrets`)
| Chave | Getter/Setter |
|---|---|
| `telemetry_token` | `telemetryToken()`, `hasCustomTelemetryToken()`, `setTelemetryToken()` |
| `admin_token` | `adminToken()`, `setAdminToken()` |
| `gemini_key` | `geminiKey()`, `setGeminiKey()` |
| `lastfm_key`, `lastfm_secret`, `lastfm_session` | `lastFmKey/Secret/Session()`, `setLastFm*()` |

#### Estado / Fila / Alarme
| Chave | Tipo | Getter/Setter | Obs |
|---|---|---|---|
| `resume_song_id/position/title/artist` | Long/String | `resumeSongId()`, `resumeSongTitle()`, `resumeSongArtist()`, `setResumeState()` | Retomada |
| `queue_ids/queue_index/queue_position/queue_saved_at/queue_extras` | String/Int/Long | `queueState()`, `setQueueState()`, `queueExtras()`, `setQueueExtras()` | Fila persistida |
| `alarm_hour/minute/ambient/gentle` | Int/String/Bool | `alarmHour()`, `alarmMinute()`, `alarmAmbient()`, `alarmGentle()` | Despertador progressivo |
| `pendrive_tree_uri` | String | `pendriveTreeUri()` / `setPendriveTreeUri()` | Pendrive |
| `peertube_instance` | String | `peerTubeInstance()` / `setPeerTubeInstance()` | Vídeos PeerTube |
| `visualizer_on` | Bool | `visualizerOn()` / `setVisualizerOn()` | |
| `tv_host/tv_name/tv_rmp_id` | String | `tvDevice()`, `tvRmpId()` | TV/Cast |

### 1.3 Outros namespaces e exceções
| Namespace/arquivo | Persistência | Chaves |
|---|---|---|
| `pulsa_account` | SharedPreferences, fora do backup | `hash`, `identifier`, `user_id`, `account_id`, `salt`, `registered`, `logged_in`, `guest`, `provider` |
| `pulsa_secrets` | EncryptedSharedPreferences, fora do backup | chaves listadas em `Settings.secretKeys` |
| `pulsa_profile` | DataStore | `nick`, `photo_b64` |
| `pulsa_blacklist` | DataStore | `blacklist_warned_once` |
| `pulsa_last_changelog` / `pulsa_last_installed` | DataStore | `last_shown_code` / `last_installed_code` |
| `pulsa_radio_stations` | DataStore | `locals`, `state`, `stations` |
| `pulsa_stream_history` | DataStore | `entries` (JSON, máx 30) |
| `pulsa_dj_memory` | DataStore | facts + history (JSON) |
| `subtitle_config` | DataStore | `sub_<mediaId>` (JSON) |
| `pulsa_update` | DataStore | `attempted_name`, `attempted_code`, `attempted_at` |

### 1.4 UI
- **Tela principal:** `SettingsActivity.kt` + `res/layout/activity_settings.xml`
  - `onCreate` apenas orquestra; cada seção é ligada em `ui/settings/SettingsSections.kt`
  - Navegação por `TabLayout` + swipe entre seções em `ui/settings/SettingsSectionNav.kt` (a tela segue uma coluna única; a aba rola até o cabeçalho). Abas: Aparência, Áudio, Sincronização, DJ/IA, Download, Manutenção e Conta — `Sincronização` reúne Ouvir juntos/controle remoto/servidor/backup, fora do bloco de Áudio
  - Seções: `bindAppearance`, `bindAudio`, `bindSync`, `bindDj`, `bindDownloads`, `bindMaintenance`, `bindAccount`
  - Diálogos/blocos extraídos (extensions de `SettingsActivity`): `SettingsAppearanceSection.kt`, `SettingsAudioSection.kt`, `SettingsSessionSection.kt`, `SettingsAiSection.kt`, `SettingsDownloadsSection.kt`, `SettingsMaintenanceSection.kt`, `SettingsBackupSection.kt`
- **Painel rápido:** `PerfilActivity.kt` + `res/layout/activity_perfil.xml`
- **Seções em strings:** `res/values/strings.xml` (base), `values-en/`, `values-es/`, `values-pt-rPT/`
- **Temas de destaque:** `Theme.Pulsa.*` em `res/values/themes.xml`
- **Entrada:** menu `menu_main.xml` → `action_settings` abre `SettingsActivity`

## 2) Web (`web/`)

**Não existe tela "Configurações" dedicada.** Configurações são fragmentadas em modais + inline.

### 2.1 IndexedDB (`pulsa-web`, versão 1, store `kv`)
| Chave | Tipo | Leitura | Escrita | Obs |
|---|---|---|---|---|
| `theme` | `'dark'\|'light'` | init:3025-3026 | `toggleTheme()` 2379 | Aplicado via `html.light` |
| `nick` | string | init:3023-3024 | `saveNick()` 2390 | Apelido, iniciais no avatar |
| `amb` | `{id, vol}` | init:3027-3028 | `persistAmb()` 1295 | Som ambiente (playing resetado no boot) |
| `fx` | `{on, preset, bands[10], eightD, eightDDepth, eightDRate}` | init:3029-3037 | `persistFx()` 1464 | Equalizador + 8D |
| `favs` | `string[]` | init:3019-3020 | `toggleFav()`, `deleteDupes()`, `delSong()` | Favoritos |
| `learn` | `Record<id,{plays,skips,liked}>` | init:3021-3022 | `djLearnSet()` (debounce 400ms), etc | Aprendizado da DJ |
| `lastNotified` | string (versão) | `checkNovidades()` 2450 | 2452 | Controle de "Novidades" |
| `lastSong` | string (id) | init:3038 (leitura morta) | — | **Nunca escrito** |

Helpers: `putKV(k,v)`, `getKV(k)`, `getAll()`, `clearStore()` (não usado)

### 2.2 localStorage
| Chave | Uso |
|---|---|
| `pulsa_web_id` | ID único do navegador/sessão |
| `pulsa_web_code` | Código de pareamento com app Android |
| `pulsa_web_token` | Token do servidor (`X-Pulsa-Token`). Pode vir de `?token=`/`#token=` |
| `pulsa.tv` | Modo TV manual (`1`/`0`); a detecção automática também cobre Titan OS |

### 2.3 Modais / Controles relacionados a "configurações"
| Elemento | Função | Arquivo/Linhas |
|---|---|---|
| Modal **Perfil** | Nickname, tema claro/escuro, download APK | `showProfile()` 2353-2362 |
| Modal **Equalizador** | Presets, 10 bandas, 8D (profundidade/velocidade) | `fxOpen()` 1466-1523, `fxSetPreset()`/`fxToggle8D()`/`persistFx()` |
| Banner token servidor | `#token-banner` | `updateTokenBanner()`, `saveBannerToken()` 2888-2902 |
| Player: `#p-eq`, `#p-speed` | Acessam EQ/velocidade | 554, 562 |

**Arquitetura web:** tudo inline em `index.html`, `sw.js` (cache `pulsa-web-v7`, network-first p/ navegação), `manifest.json` (PWA standalone em paisagem, ícone PNG 512 px). O modo TV cobre navegadores de TV e Philips Titan OS (`TitanOS`/`SmartTvA`/`TV_NT`/`NETTV`), com keycodes do controle, foco por D-pad e integração do controle remoto com o estado do celular Android. Na TV o foco inicial nunca cai em campo de busca (evita o teclado na tela); a saída usa `SmartTvA_API.exit()` quando o Titan OS oferece a API.

**Publicação no Titan OS:** URL `https://cleberleonheart-maker.github.io/pulsaweb/?tv=1`. Os assets do portal ficam em `web/titan-os/` (`main-icon.jpg` 1024x1024, `store-icon.png` 1920x1080, `logo.png` 512x512 transparente, `background.png` 3840x2160, `screenshot-1.png`/`screenshot-2.png` 1920x1080) e os metadados em `web/titan-os/submission.json`. O token do servidor é informado no formulário do Partner Portal, nunca no repositório.

## 3) Arquivos de configuração do projeto
| Arquivo | Função |
|---|---|
| `settings.gradle.kts` | Configuração Gradle multi-módulo |
| `build.gradle.kts` (raiz) | Configuração build |
| `gradle.properties` | Propriedades Gradle |
| `local.properties` | Paths locais (SDK etc.) |
| `app/build.gradle.kts` | Módulo app Android |
| `web/manifest.json` | PWA manifest |
| `web/sw.js` | Service Worker |

## 4) Notas importantes
- **Android:** Preferências não sensíveis no DataStore Preferences, com namespaces legados e compatibilidade `SharedPreferences`; `core/Settings.kt` centraliza as chaves (organizado por banners de seção). A conta permanece em SharedPreferences e os secrets em EncryptedSharedPreferences. Uma cópia local dos XMLs migrados protege contra corrupção do DataStore, mas não entra no backup. `SettingsActivity` só orquestra e delega diálogos para `ui/settings/`. Muitas configs também mudáveis por voz/DJ.
- **Web:** Sem tela "Configurações" — usar modal Perfil ou criar nova aba/modal seguindo padrão `.mrow`.
- **Sincronização:** Servidor configurável (`server_base`/token). Handoff/RemoteControl/MirrorSync usam essas configs.
- **Backup/Restore:** `SettingsActivity.buildBackup()`/`applyRestore()` (em `ui/settings/SettingsBackupSection.kt`) exporta preferências + playlists/favoritos via SAF.
