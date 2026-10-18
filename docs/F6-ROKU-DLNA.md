# F6 · Checklist — Tocar na TV (Roku/DLNA) + Podcasts no Android Auto

> Teste **manual** de aceitação da Frente A (Roku ECP + DLNA) e da Frente B (podcasts no
> Android Auto + fix Room-on-main). Rodar depois de cada passo: se algo quebra, o passo
> anterior está com defeito.
>
> Baseline 18/10: 5.10.57, versionCode 188, 45 testes novos (tv/). APK de referência = o que
> o CI publicar para 189.
>
> **Limite honesto:** o Roku Media Player é quem faz browse na TV — o ECP não permite
> deep-link de item específico. O fluxo real é: app sobe servidor DLNA → ECP lança o Roku
> Media Player → usuário escolhe servidor "Pulsa" no controle remoto → play/pause/±/volume
> via keypress ECP. Validação funcional do Auto é impossível nesta HyperOS (sem carro).

## 1. Tocar na TV — Roku ECP + DLNA (Frente A)

- [ ] Abrir Now Playing → botão TV (ícone de televisão) → diálogo de descoberta aparece
- [ ] Com Roku na mesma rede Wi-Fi: o aparelho aparece na lista em ~3 s (SSDP + subnet scan)
- [ ] Sem Roku na rede: mensagem honesta "Nenhum Roku encontrado" + botão "Buscar de novo"
- [ ] Tocar o Roku na lista: estado muda para "Conectando…" e depois "Conectado: <nome>"
- [ ] O Roku Media Player abre na TV sozinho (ECP launch do app id correto)
- [ ] Na TV, o servidor "Pulsa" aparece na lista de fontes do Roku Media Player
- [ ] Navegar até "Músicas"/"Vídeos"/"Podcasts" na TV e tocar um item: o áudio sai na TV
- [ ] No app, play/pause funciona (keypress ECP) e a TV responde
- [ ] Próximo/anterior funcionam (keypress ECP)
- [ ] Volume +/− funcionam (keypress ECP)
- [ ] Botão "Abrir player" re-lança o Roku Media Player (relaunch)
- [ ] Botão "Desconectar" derruba o servidor DLNA e volta ao estado Idle
- [ ] Roku desligado depois de conectado: app detecta (mediaPlayer vazio) e mostra reconectar
- [ ] Vídeo local: item na TV usa `content://` (FileChannel no fd), não caminho cru
- [ ] Episódio de podcast baixado: toca da TV (arquivo local via servidor DLNA)
- [ ] Fechar o app com TV conectada: servidor DLNA morre (não fica porta aberta)
- [ ] Árvore da biblioteca com 3 mil faixas: descoberta/conexão não trava a UI (tudo em bg)

## 2. Podcasts no Android Auto (Frente B)

- [ ] Raiz da árvore no Auto mostra a pasta "Podcasts" (junto de Músicas/Favoritas/etc.)
- [ ] Abrir "Podcasts": lista de feeds assinados com título certo
- [ ] Abrir um feed: lista de episódios com título e artista (nome do podcast)
- [ ] Tocar um episódio: cai na mesma fila do app (não numa sessão separada)
- [ ] Episódio baixado toca offline pelo Auto (URI do PodcastCache prefere arquivo)
- [ ] Busca do sistema ("tocar podcast X") acha episódios e toca
- [ ] Episódio em vídeo aparece como vídeo (mediaType correto)
- [ ] Feed sem episódios: pasta abre vazia, sem crash

## 3. Fix Room-on-main (Frente B — o bug latente)

- [ ] Navegar no Auto/Wear até playlists: **não** crasha com IllegalStateException
- [ ] Navegar até favoritas: idem
- [ ] Navegar até podcasts: idem
- [ ] Busca do Assistant com Room: idem
- [ ] Botão do carro ("tocar isto"): `onSetMediaItems` resolve em bg e toca na main
- [ ] App não morre ao receber pedido de browser externo com permissão negada

## 4. Regressões (o que não pode ter quebrado)

- [ ] App abre, toca música, notificação funciona (baseline F1)
- [ ] Podcast no app (tela normal): play/pause/next/prev seguem iguais
- [ ] Download Center segue funcionando
- [ ] Modo família não deixa podcast bloqueado aparecer no Auto
- [ ] Rádio continua tocando normalmente
- [ ] Vídeo local continua abrindo na tela do app
