# F1 · Checklist E0 — comportamento que não pode mudar

> Teste **manual** de aceitação da F1. É o que se roda depois de **cada** passo (E2, E3, E4…):
> se algum item quebra, o passo anterior está com defeito, não o próximo.
>
> Como o build local não roda neste aparelho (o daemon do Gradle é morto pelo sistema — ver
> `.termux-resume.md`), a verificação de cada passo é: compilar no CI + rodar este checklist.
> Nada de mexer no motor de playback sem ele.
>
> **Baseline 27/09:** 5.9.3, `versionCode` 124, 68 testes unitários (56 antigos + 12 novos),
> APK de referência = o que o CI publicou para 124.

## 1. Ligar e tocar (o básico que quebra primeiro)

- [ ] App abre em menos de 5 s e vai direto para a home (sem travar na tela da Virgin)
- [ ] Tocar uma música na biblioteca: começa em ~1 s
- [ ] Tela Now Playing mostra capa, título e artista **certos** (ordem do título, não invertida)
- [ ] Botões play/pause, próximo, anterior respondem na hora
- [ ] Arrastar a barra de progresso faz *seek* e a música continua no ponto
- [ ] Slider de volume mexe no volume do sistema
- [ ] Modo aleatório e modo repetir mudam o ícone e o comportamento
- [ ] "Repetir" cicla entre os três estados (todos → um → nenhum) no botão da tela
- [ ] Tirar o app do primeiro plano: **a música continua** e a notificação funciona
- [ ] A notificação tem capa, título, artista e os 3 botões; os botões controlam de verdade
- [ ] Travar a tela: o controle de tela de bloqueio aparece e funciona
- [ ] Fechar o app da lista de recentes e voltar: música continua ou retoma do ponto

## 2. Efeitos de áudio (o que morre em silêncio se o motor trocar)

- [ ] Equalizador: cada banda mexe e o som muda na hora
- [ ] Preset "Automático (por gênero)" aplica o preset certo numa faixa de Rock e numa de Jazz
- [ ] EQ personalizado: salvar, sair das Config, voltar, os 5 sliders continuam com o valor salvo
- [ ] Baixos/Reforçador (BassBoost) liga e desliga sem crash
- [ ] Karaokê: a voz principal some, a voz de fundo fica
- [ ] Desligar o EQ **com música tocando** religa o efeito (é o `refreshFx`)
- [ ] Equalizador com bandas reais do aparelho: o diálogo abre mostrando as bandas do aparelho
- [ ] Modo dança / 8D: velocidade e pitch mudam, e o pan anda

## 3. A Virgin (o diferencial — se ela quebra, o passo está errado)

- [ ] "Virgi, toca X" começa a faixa
- [ ] "Virgi, próxima" / "anterior" / "pausa" / "volta pra música" (retomar)
- [ ] Modo de humor: dormir / bombar / estudando muda a fila
- [ ] "Toca só X" e "mistura com Y" montam a fila certa
- [ ] **A Virgin fala a música na ordem certa** (regressão do 5.9.2, não volta atrás)
- [ ] "Virgi, me acorda às 7h" arma o alarme com chuva; "cancela o alarme" desarma
- [ ] "Virgi, para em 20 minutos" arma o sleep timer falado
- [ ] Sleep timer bate: a música **desliga gradualmente** e pausa (fade de ~9 s)
- [ ] Alarme tocando: o app abre e a música começa (caminho do `playWhenBound`)
- [ ] Botão "recomendar" da home responde (com IA e sem rede)
- [ ] Botão de saudação ("Bom dia/Boa tarde/Boa noite") fala na hora certa
- [ ] Avatar continua dançando no ritmo e piscando (nada de parar com música tocando)

## 4. Mãos-livres (5.9.3)

- [ ] Toggle das Config ligado + música tocando → notificação de microfone aparece
- [ ] Comando por voz no fundo funciona ("virgi, próxima")
- [ ] **O microfone não fica piscando sem parar** (o defeito da 5.9.1): com música tocando e
      ninguém falando, a espera entre uma tentativa e a outra cresce até ~15 s
- [ ] Falar de novo responde **na hora** (a espera cresce, mas some assim que você fala)
- [ ] Pausar a música → o serviço de microfone some da bandeja
- [ ] Tirar o toggle → o serviço some
- [ ] A Virgin da tela e o mãos-livres **não** abrem microfone ao mesmo tempo

## 5. Vídeo e rádio (existem hoje em motores separados)

- [ ] Vídeo local abre e toca **com o áudio junto**
- [ ] Ao voltar do vídeo, a música volta a tocar
- [ ] Rádio: busca, abre uma estação e toca (stream `.m3u8` incluído)
- [ ] Rádio em segundo plano continua e a notificação mostra a estação
- [ ] Troca rádio → música: não fica nenhum dos dois falando sozinho

## 6. Fora do app

- [ ] Botões do fone de ouvido: play/pause, próximo, anterior
- [ ] Pausar por ligação: música abaixa/pausa e volta
- [ ] Widget da tela inicial mostra capa e estado, e o botão dele funciona
- [ ] "Ouvir juntos": o segundo aparelho espelha faixa, posição e pause/play
- [ ] Web player (`/remote`): o estado chega e o comando do PC volta
- [ ] Scrobble Last.fm aparece no perfil
- [ ] `play_log` soma a reprodução (Perfil → Rewind muda)
- [ ] Atualização automática: com versão nova publicada, o app **oferece** de novo se a
      instalação falhar (regressão do 5.9.3)
- [ ] Login, espelho da biblioteca e servidor continuam funcionando

## 7. O que o build tem que dizer

- [ ] `:app:test` verde (68 testes na baseline) — o CI roda antes de publicar
- [ ] `:app:assembleRelease` verde e assinado
- [ ] `versionCode` do APK == `versionCode` do `app/build.gradle.kts` do commit
- [ ] Tamanho do APK anotado (antes/depois de cada passo) — Media3 adiciona módulos
