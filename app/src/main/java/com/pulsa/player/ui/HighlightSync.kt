package com.pulsa.player.ui

/**
 * Uma lista que mostra "tocando agora" sabe se reposicionar sozinha quando a faixa muda.
 *
 * Existe porque o [android.content.Context] só tem **um** slot de listener de playback
 * ([com.pulsa.player.playback.Playback.listener]), e ele já é da Activity. Sem este
 * contrato, cada tela preenchia o destaque uma vez no `load()` e nunca mais: trocar de faixa
 * pela mini player, pela notificação ou pelo botão de próximo deixava o fundo
 * `bg_song_selected` na música antiga até a lista ser recarregada — a mesma sensação do
 * "selecionei e o destaque não veio".
 *
 * A Activity quem chama ([MainActivity.syncListHighlight]), percorrendo também os fragmentos
 * filhos: as listas de álbum, artista, playlist e favoritas vivem dentro da Biblioteca, não
 * no container de topo.
 */
interface HighlightSync {
    fun syncHighlight()
}