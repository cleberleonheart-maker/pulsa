package com.pulsa.player.dj

/**
 * Ponte entre o serviço de mãos-livres (que roda em segundo plano) e a
 * Virgínia da tela principal. Mantém a instância ativa da MainVirgin para
 * o HotwordService entregar comandos mesmo com o app ao fundo.
 */
object HotwordBridge {

    @Volatile
    private var virg: MainVirgin? = null

    fun bind(mainVirgin: MainVirgin) {
        virg = mainVirgin
    }

    fun unbind(mainVirgin: MainVirgin) {
        if (virg === mainVirgin) virg = null
    }

    /** A Virgínia da tela está com o microfone ativo (listener da própria activity). */
    fun virginActive(): Boolean = virg?.isActive == true

    /** Há uma Virgínia viva para receber os comandos. */
    fun hasTarget(): Boolean = virg != null

    /** Entrega o texto reconhecido ao mãos-livres. Retorna false se não há ninguém para tratar. */
    fun deliver(text: String): Boolean {
        val target = virg ?: return false
        target.onHandsFree(text)
        return true
    }
}