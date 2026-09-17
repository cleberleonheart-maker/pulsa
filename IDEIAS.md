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

## DJ Virgin (mais natural)
- [ ] Relembrar contexto: guardar última conversa e responder com telefone/WhatsApp/Bluetooth (hoje ela só fala sozinha)
- [ ] Perfis de humor: "toca uma pra eu dormir" → fila menor, playlists calmas
- [ ] Comandos faltantes: "toca só X", "mistura com Y", "repete essa" (hoje só tem next/prev/pause/vol/fav)

## Áudio/qualidade de som
- [ ] Equalizador + presets no app (MediaPlayer tem 2 bandas básicas)

## Web player (pulsaweb)
- [ ] Sincronizar fila entre app e web via telemetria
- [ ] Remote control pelo PC (play/pause/volume do celular)

## Uso/dados
- [ ] Espelhar DjLearn (skips/likes) pra nuvem → Virgin aprende entre sessões/dispositivos

## Engenharia
- [ ] Assinatura via GitHub Actions (secrets) pra publicar sem depender do PC
- [ ] Migrar/canaleta de update para v4.x
- [ ] Verificar status SMTP (sendmail) — autenticação Gmail estava falhando (536/535); testar senha de app
