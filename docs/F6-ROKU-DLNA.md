# F6 · Checklist — Tocar na TV (Roku/DLNA) + Podcasts no Android Auto

> Teste **manual** de aceitação da Frente A (Roku ECP + DLNA) e da Frente B (podcasts no
> Android Auto + fix Room-on-main). Rodar depois de cada passo: se algo quebra, o passo
> anterior está com defeito.
>
> Baseline 20/10: 5.12.0, versionCode 197. A árvore DLNA agora é hierárquica (pastas por
> Artista/Álbum, Playlists e Podcasts) e a descoberta /24 roda em ~2,5s. Testes de
> `media.tv` cobrem a árvore (ContentTreeTest 17) — o resto é só na TV de verdade.
>
> **Limite honesto:** o Roku Media Player é quem faz browse na TV — o ECP não permite
> deep-link de item específico. O fluxo real é: app sobe servidor DLNA → ECP lança o Roku
> Media Player → usuário escolhe servidor "Pulsa" no controle remoto → play/pause/±/volume
> via keypress ECP. Validação funcional do Auto é impossível nesta HyperOS (sem carro).

## 1. Tocar na TV — Roku ECP + DLNA (Frente A)

- [ ] Abrir Now Playing → botão TV (ícone de televisão) → diálogo de descoberta aparece
- [ ] Botão "Tocar na TV" na **home** (abaixo de Bombar/Relaxar) faz o mesmo fluxo
- [ ] Com Roku na mesma rede Wi-Fi: o aparelho aparece na lista em ~2,5 s (SSDP + subnet scan)
- [ ] Sem Roku na rede: mensagem honesta "Nenhum Roku encontrado" + botão "Buscar de novo"
- [ ] Tocar o Roku na lista: estado muda para "Conectando…" e depois "Conectado: <nome>"
- [ ] O Roku Media Player abre na TV sozinho (ECP launch do app id correto)
- [ ] Na TV, o servidor "Pulsa" aparece na lista de fontes do Roku Media Player

### 1.1 Árvore hierárquica (5.12.0)

- [ ] A raiz "Pulsa" mostra **Músicas | Artistas | Álbuns | Playlists | Podcasts | Vídeos**
      (só as pastas que têm conteúdo aparecem; TV sem playlist/podcast não mostra essas)
- [ ] **Artistas** → abre a lista de artistas → toca num artista → lista de álbuns →
      abre um álbum → lista de faixas
- [ ] **Álbuns** (lista plana) mostra os álbuns direto, sem passar pelo artista
- [ ] Música sem artista/álbum preenchido cai na pasta **"Desconhecido"** (não some)
- [ ] **Playlists** → pasta por playlist → faixas **na ordem da playlist** (não alfabética)
- [ ] **Podcasts** → pasta por feed → episódios **baixados**. Podcast só em streaming
      **não** aparece (a TV busca a URL direto; ver "limite honesto" abaixo)
- [ ] A **mesma faixa** aparece em Músicas, no álbum e na playlist — e toca igual nos 3
- [ ] Título com `&`, `<`, `>`, `"`, `'` (ex.: "Rock & Roll") aparece certo na TV, sem
      pasta vazia e sem "arquivo não encontrado"

### 1.2 Reprodução e controle

- [ ] Navegar até uma música na TV e tocar: o áudio sai na TV
- [ ] Seek funciona no meio da faixa (o `res` anuncia `DLNA.ORG_OP=01`)
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
- [ ] TV já pareada: ao abrir a reprodução, o servidor sobe sozinho em bg (a TV acha
      "Pulsa" sem o app ter tomado a tela da TV)


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
