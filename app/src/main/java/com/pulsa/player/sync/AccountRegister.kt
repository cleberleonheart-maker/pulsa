package com.pulsa.player.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.pulsa.player.core.Account
import com.pulsa.player.core.Settings
import com.pulsa.player.core.ThreadPool
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Registra a conta no servidor (POST /register) depois do cadastro local.
 *
 * O registro e best-effort: a conta local ja existe e o app funciona offline, entao
 * falha de rede nao desfaz o cadastro -- so loga. O servidor usa o `account_id` (UUID)
 * como chave e o `user_id` como rotulo unico: se o ID ja pertencer a outra conta,
 * responde `user_taken` e o usuario e avisado.
 */
object AccountRegister {

    /** Resultado do registro no servidor. */
    enum class Result { OK, USER_TAKEN, FAILED }

    private val main = Handler(Looper.getMainLooper())

    fun send(
        context: Context,
        accountId: String,
        userId: String,
        identifier: String,
        onResult: (Result) -> Unit = {}
    ) {
        ThreadPool.postNetwork {
            val result = sendSync(context, accountId, userId, identifier)
            Telemetry.log(context, "REGISTER result=$result user=$userId")
            if (result == Result.OK) Account.markRegistered(context)
            main.post { onResult(result) }
        }
    }

    /**
     * Reenvia o registro pendente na abertura do app: se a conta existe mas o servidor
     * nunca confirmou (cadastro com servidor fora), tenta de novo em background.
     */
    fun retryPending(context: Context) {
        if (!Account.needsServerRegister(context)) return
        val userId = Account.userId(context) ?: return
        val identifier = Account.identifier(context) ?: return
        Telemetry.log(context, "REGISTER retry pendente user=$userId")
        send(context, Account.accountId(context), userId, identifier)
    }

    private fun sendSync(
        context: Context,
        accountId: String,
        userId: String,
        identifier: String
    ): Result {
        val payload = JSONObject()
            .put("account_id", accountId)
            .put("user_id", userId)
            .put("identifier", identifier)
            .toString().toByteArray(Charsets.UTF_8)
        for (base in Settings.serverCandidates(context)) {
            try {
                val conn = URL("$base/register").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("X-Pulsa-Token", Settings.telemetryToken(context))
                conn.setRequestProperty("X-Pulsa-Device", Settings.deviceId(context))
                conn.setRequestProperty("X-Pulsa-User", userId)
                conn.setRequestProperty("X-Pulsa-Account", accountId)
                conn.outputStream.use { it.write(payload) }
                val code = conn.responseCode
                val text = runCatching {
                    (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                }.getOrDefault("")
                val body = runCatching { JSONObject(text) }.getOrNull()
                return when {
                    code == 409 || body?.optString("error") == "user_taken" -> Result.USER_TAKEN
                    code in 200..299 && body?.optBoolean("ok", true) != false -> Result.OK
                    else -> Result.FAILED
                }
            } catch (t: Throwable) {
            }
        }
        return Result.FAILED
    }
}
