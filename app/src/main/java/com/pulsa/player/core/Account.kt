package com.pulsa.player.core

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

object Account {
    private const val PREFS = "pulsa_account"
    private const val USER_ID_MAX = 24

    fun hasAccount(context: Context): Boolean {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString("hash", null) != null
    }

    fun identifier(context: Context): String? {
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("identifier", null)
    }

    /** ID escolhido pela pessoa no cadastro (ex.: "cleber"). Vazio = sem ID. */
    fun userId(context: Context): String? {
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("user_id", null)
    }

    /**
     * UUID unico da conta, gerado no cadastro e persistido. Diferente do `user_id` (rotulo
     * humano, opcional e trocavel), este nao muda e nao depende do aparelho -- e a chave
     * usada para registrar a conta no servidor.
     */
    fun accountId(context: Context): String {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString("account_id", null)?.let { return it }
        val id = UUID.randomUUID().toString()
        p.edit().putString("account_id", id).apply()
        return id
    }

    fun setUserId(context: Context, userId: String?) {
        val clean = sanitizeUserId(userId)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().apply {
                if (clean.isEmpty()) remove("user_id") else putString("user_id", clean)
            }.apply()
    }

    /** Marca que o servidor confirmou o registro desta conta (posto quando /register responde ok). */
    fun markRegistered(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("registered", true).apply()
    }

    /**
     * Conta local existe mas o servidor nunca confirmou o registro: acontece quando o
     * cadastro foi feito com o servidor fora. Na proxima abertura do app o registro e
     * reenviado.
     */
    fun needsServerRegister(context: Context): Boolean {
        if (!hasAccount(context) || isGuest(context)) return false
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return !p.getBoolean("registered", false)
    }

    /** Normaliza o ID: sem espaco, sem "@" na frente, limitado ao tamanho maximo. */
    fun sanitizeUserId(raw: String?): String {
        return (raw ?: "").trim().removePrefix("@").trim().take(USER_ID_MAX)
    }

    /**
     * ID valido: 3 a 24 caracteres, comeca por letra/numero e aceita ponto, hifen e underline.
     * Obrigatorio no cadastro.
     */
    fun isUserIdValid(userId: String): Boolean {
        return sanitizeUserId(userId).matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{2,23}$"))
    }

    /**
     * Cria a conta local e devolve o UUID da conta. O `userId` e obrigatorio: sem rotulo
     * humano a conta fica identificada so pelo aparelho, o que quebra o painel do admin.
     */
    fun create(context: Context, identifier: String, password: String, userId: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val saltHex = salt.joinToString("") { "%02x".format(it) }
        val hash = sha256Hex(saltHex + password)
        val clean = sanitizeUserId(userId)
        require(clean.isNotEmpty()) { "userId obrigatorio no cadastro" }
        val accountId = UUID.randomUUID().toString()
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("identifier", identifier)
            .putString("salt", saltHex)
            .putString("hash", hash)
            .putString("user_id", clean)
            .putString("account_id", accountId)
            .putBoolean("logged_in", false)
            .apply()
        return accountId
    }

    /**
     * Rotulo de fallback para fluxos sem campo de ID (ex.: login com Google): usa a parte
     * local do e-mail, sanitizada; se sobrar menos que o minimo, completa com aleatorio.
     */
    fun defaultUserId(identifier: String): String {
        val base = sanitizeUserId(identifier.substringBefore('@').substringBefore(' '))
        val filled = if (base.length < 3) {
            base + UUID.randomUUID().toString().replace("-", "").take(3)
        } else base
        return sanitizeUserId(filled)
    }

    fun verify(context: Context, identifier: String, password: String): Boolean {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val storedIdentifier = p.getString("identifier", null) ?: return false
        if (!storedIdentifier.equals(identifier, ignoreCase = true)) return false
        val saltHex = p.getString("salt", "") ?: ""
        val expected = p.getString("hash", null) ?: return false
        return sha256Hex(saltHex + password) == expected
    }

    fun setLoggedIn(context: Context, value: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("logged_in", value).apply()
    }

    fun loggedIn(context: Context): Boolean {
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("logged_in", false)
    }

    fun enterGuest(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("logged_in", true)
            .putBoolean("guest", true)
            .apply()
    }

    fun enterAccount(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("guest", false).apply()
    }

    fun isGuest(context: Context): Boolean {
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("guest", false)
    }

    fun reset(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    fun looksLikeEmail(identifier: String): Boolean {
        return identifier.matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
    }

    fun setProvider(context: Context, provider: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("provider", provider).apply()
    }

    fun provider(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("provider", "") ?: ""

    fun looksLikePhone(identifier: String): Boolean {
        return identifier.length in 7..15 && identifier.all { it.isDigit() }
    }

    fun isIdentifierValid(identifier: String): Boolean {
        return looksLikeEmail(identifier) || looksLikePhone(identifier)
    }

    private fun sha256Hex(input: String): String {
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
