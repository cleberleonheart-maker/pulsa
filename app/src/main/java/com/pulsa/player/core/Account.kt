package com.pulsa.player.core

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

object Account {
    private const val PREFS = "pulsa_account"

    fun hasAccount(context: Context): Boolean {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString("hash", null) != null
    }

    fun identifier(context: Context): String? {
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("identifier", null)
    }

    fun create(context: Context, identifier: String, password: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val saltHex = salt.joinToString("") { "%02x".format(it) }
        val hash = sha256Hex(saltHex + password)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("identifier", identifier)
            .putString("salt", saltHex)
            .putString("hash", hash)
            .putBoolean("logged_in", false)
            .apply()
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
