package com.pulsa.player.core

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Duas filas, de propósito.
 *
 * [post] é para **trabalho local**: ler o MediaStore, consultar o `PlaylistDb`, montar
 * adapter. É o que faz a biblioteca aparecer, e precisa ser previsível.
 *
 * [postNetwork] é para **rede**: sync remoto, mirror, letras, PeerTube, download,
 * telemetria. Rede é lenta por definição — o `RemoteSync` faz quatro chamadas
 * sequenciais num ciclo, cada uma com até 1,5 s de timeout, e o `MirrorSync` faz o
 * mesmo em paralelo.
 *
 * Antes isto era um pool só de 3 threads para as duas coisas. Com o sync segurando
 * duas delas em I/O, sobrava **uma** thread para as 108 chamadas de [post] do app —
 * e a última delas, `Library.allSongs()`, podia ficar na fila indefinidamente atrás de
 * uma espera de socket. O sintoma era o pior possível: o app vivo, o mini player
 * respondendo, e a lista de músicas e vídeos permanentemente vazia, porque a linha que
 * carregaria a lista nunca era executada.
 *
 * Rede não deve poder roubar a fila da biblioteca. Por isso [postNetwork] tem pool
 * próprio, e o número de threads foi maiorado (6): são tarefas bloqueantes esperando
 * servidor, não CPU.
 */
object ThreadPool {
    /** Trabalho local: MediaStore, banco, adapters. Curto e previsível. */
    private val local = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "pulsa-local").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    /** Rede: sync, letras, downloads. Não compete com a biblioteca. */
    private val network = Executors.newFixedThreadPool(6) { r ->
        Thread(r, "pulsa-net").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    private val main = Handler(Looper.getMainLooper())

    /** Trabalho local. Não bloqueie isto com rede — use [postNetwork]. */
    fun post(block: () -> Unit) {
        executar(local, block)
    }

    /**
     * Trabalho de rede. Fica num pool separado justamente para não atrasar a
     * biblioteca, que é o que o usuário está esperando ver na tela.
     */
    fun postNetwork(block: () -> Unit) {
        executar(network, block)
    }

    /**
     * Rejeição é descartada em vez de estourar: o `UncaughtExceptionHandler` global
     * transformava uma fila cheia em **queda do processo**, e foi assim que um ciclo
     * de sync lotado derrubou o app no meio da navegação.
     */
    private fun executar(executor: ExecutorService, block: () -> Unit) {
        try {
            executor.execute(block)
        } catch (e: RejectedExecutionException) {
            // pool saturated/shutdown: melhor perder este trabalho do que matar o app
        }
    }

    fun onUi(block: () -> Unit) {
        main.post(block)
    }
}