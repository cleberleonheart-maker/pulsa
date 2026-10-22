package com.pulsa.player.sync

import android.content.Context
import com.pulsa.player.core.Account
import java.net.HttpURLConnection

/**
 * Cabecalhos comuns das chamadas ao servidor.
 *
 * O `X-Pulsa-User` e o rotulo humano escolhido no cadastro (obrigatorio desde a versao
 * que passou a exigir ID). O `X-Pulsa-Account` e o UUID da conta, gerado no cadastro e
 * independente do aparelho -- e a identidade estavel da conta no servidor. O
 * `X-Pulsa-Device` continua sendo o UUID do aparelho (nuvem e controle remoto sao
 * chaveados por ele).
 */
object Net {
    fun user(ctx: Context, conn: HttpURLConnection) {
        val id = Account.userId(ctx)
        if (!id.isNullOrBlank()) conn.setRequestProperty("X-Pulsa-User", id)
        // Visitante nao tem conta: sem hash, o UUID de conta nao existe e nao deve ir.
        if (Account.hasAccount(ctx)) {
            conn.setRequestProperty("X-Pulsa-Account", Account.accountId(ctx))
        }
    }

    /**
     * Libera a conexao de forma segura em **qualquer** resposta.
     *
     * O erro classico: chamar so `conn.inputStream.close()` depois de `getInputStream()`
     * funciona para 2xx, mas para 4xx/5xx o proprio `getInputStream()` ja lanca — o stream de
     * resposta nunca e lido, o `errorStream` (e o socket por tras dele) fica aberto e o
     * `catch` engole a excecao. Como o sync bate no servidor a cada 3 s, um endpoint que
     * responda 403/500 de forma continua acumula um descritor por tentativa ate "too many
     * open files". Aqui drenamos o stream certo (response **ou** error) e sempre damos
     * `disconnect()` para devolver o socket ao pool.
     */
    fun close(conn: HttpURLConnection) {
        val stream = runCatching { conn.inputStream }.getOrNull()
            ?: runCatching { conn.errorStream }.getOrNull()
        runCatching { stream?.close() }
        // `bufferedReader`/`close` acima ja consomem; o disconnect garante a devolucao mesmo
        // quando o corpo nao foi lido por completo.
        runCatching { conn.disconnect() }
    }
}
