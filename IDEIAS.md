# Pulsa · Ideias para depois

> Lista de melhorias futuras (não implementadas ainda).
> Status: <dashboards/em progresso> etc.

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