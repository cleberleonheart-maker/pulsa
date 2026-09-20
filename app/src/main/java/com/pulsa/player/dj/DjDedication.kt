package com.pulsa.player.dj

/**
 * Dedicação pedida por voz ("Virgin, dedica a próxima pra Maria").
 * O nome fica pendente aqui até a próxima faixa começar; quem anuncia a música
 * consome com [take] e fala a dedicatória antes do nome da faixa.
 */
object DjDedication {
    @Volatile
    var pending: String? = null

    @Synchronized
    fun take(): String? {
        val value = pending
        pending = null
        return value
    }
}