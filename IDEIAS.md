# Pulsa · Ideias para depois

> Lista de melhorias futuras (não implementadas ainda).
> Status: <dashboards/em progresso> etc.


## ✓ Feito (2026)

(sem reescrever nada abaixo — itens entregues nesta rodada)

- [x] **Delete por voz com confirmação**: a Virgínia pergunta "quer apagar?" e aceita "sim", "sim.", ", sim", "pode apagar"/"pode excluir" diretamente (ignora pontuação/enj do reconhecedor — `DjCommander.affirm`, via `DjVoice.kt`)
- [x] **Som ambiente audível**: geradores (noite/chuva/oceano/via) agora com ganho de saída (`OUTPUT_GAIN` em `Ambient.kt`) + volume padrão maior; deixou de sair quase mudo
## Feito em 2026
- [x] Delete por voz com confirmação: a Virgínia pergunta "quer apagar?" e aceita "sim", "sim, pode", "pode apagar" (ignora pontuação do reconhecedor — `DjCommander.affirm`)
- [x] Som ambiente (noite/chuva/oceano/via-mar…): agora com ganho de saída (`OUTPUT_GAIN`) + volume padrão maior, deixou de sair quase inaudível (`Ambient.kt`)
- [x] Normalização de ganho no reconhecimento do DJ (audD): áudio do microfone amplificado de forma consistente (`DjRecognizer`)
- [x] Letras: busca recursiva por pasta + por título + fallback online (LRCLIB), com cache local (`Lyrics.kt`)

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
- [x] Dashboard de telemetria: músicas mais tocadas, horários, sugestões aceitas/rejeitadas
      → PRONTO (página /dashboard + /stats no telemetry_server.py; executa em http://192.168.100.7:8081/dashboard)
- [ ] Espelhar DjLearn (skips/likes) pra nuvem → Virgin aprende entre sessões/dispositivos

## Engenharia
- [ ] CI publicando release automática no push (hoje build só; APK publicado manualmente)
- [ ] Assinatura via GitHub Actions (secrets) pra publicar sem depender do PC
- [ ] Migrar/canaleta de update para v4.x
- [ ] Verificar status SMTP (sendmail) — autenticação Gmail estava falhando (536/535); testar senha de app