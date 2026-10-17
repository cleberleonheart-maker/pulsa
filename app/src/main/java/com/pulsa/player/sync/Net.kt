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
}
