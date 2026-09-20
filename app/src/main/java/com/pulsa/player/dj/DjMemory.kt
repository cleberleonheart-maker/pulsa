package com.pulsa.player.dj

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Memória da Virgin: guarda fatos que o usuário conta (número de telefone,
 * WhatsApp, dispositivo Bluetooth) e o histórico curto das últimas conversas,
 * para ela lembrar em sessões futuras.
 */
object DjMemory {

    private const val PREFS = "pulsa_dj_memory"
    private const val KEY_FACTS = "facts"
    private const val KEY_HISTORY = "history"
    private const val MAX_HISTORY = 12

    const val PHONE = "phone"
    const val WHATSAPP = "whatsapp"
    const val BLUETOOTH = "bluetooth"

    private val facts = HashMap<String, String>()
    private val history = ArrayList<Pair<String, String>>()
    private var loaded = false

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(context: Context) {
        if (loaded) return
        runCatching {
            val fs = prefs(context).getString(KEY_FACTS, null)
            if (!fs.isNullOrBlank()) {
                val arr = JSONArray(fs)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    facts[o.optString("k")] = o.optString("v")
                }
            }
            val hs = prefs(context).getString(KEY_HISTORY, null)
            if (!hs.isNullOrBlank()) {
                val arr = JSONArray(hs)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    history.add(o.optString("u") to o.optString("v"))
                }
            }
        }
        loaded = true
    }

    fun log(context: Context, user: String, reply: String) {
        load(context)
        history.add(user to reply)
        while (history.size > MAX_HISTORY) history.removeAt(0)
        val arr = JSONArray()
        history.forEach { (u, v) ->
            arr.put(JSONObject().put("u", u).put("v", v))
        }
        prefs(context).edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    /** Tenta guardar um fato dito pelo usuário. Retorna (tipo, valor) ou null. */
    fun save(context: Context, norm: String): Pair<String, String>? {
        load(context)
        val hasKeyword = listOf("numero", "telefone", "whats", "zap", "watss", "bluetooth", "bitu", "fone", "contato", "ligar").any { norm.contains(it) }
        if (!hasKeyword) return null
        val key = when {
            norm.contains("whats") || norm.contains("zap") || norm.contains("watss") -> WHATSAPP
            norm.contains("bluetooth") || norm.contains("bitu") || norm.contains("fone") -> BLUETOOTH
            else -> PHONE
        }
        val digits = findDigits(norm)
        val value = if (key == BLUETOOTH) {
            digits ?: bluetoothName(norm)?.take(24)
        } else {
            digits
        } ?: return null
        facts[key] = value
        persistFacts(prefs(context))
        return key to value
    }

    /** Recupera um fato já guardado, se a frase perguntar por ele. */
    fun recall(context: Context, norm: String): Pair<String, String>? {
        load(context)
        if (findDigits(norm) != null) return null
        val asks = norm.contains("qual") || norm.contains("meu") || norm.contains("minha") || norm.contains("lembra")
        if (!asks) return null
        return when {
            norm.contains("whats") || norm.contains("zap") ->
                facts[WHATSAPP]?.let { WHATSAPP to it }
            norm.contains("bluetooth") || norm.contains("bitu") ||
                (norm.contains("fone") && !norm.contains("numero")) ->
                facts[BLUETOOTH]?.let { BLUETOOTH to it }
            norm.contains("numero") || norm.contains("telefone") || norm.contains("fone") ->
                facts[PHONE]?.let { PHONE to it }
            else -> null
        }
    }

    private fun findDigits(norm: String): String? {
        val compact = norm.filter { it.isDigit() }
        if (compact.length in 8..15) return compact
        val runs = Regex("""[0-9]+(?:\s*-?\s*[0-9]+)*""")
            .findAll(norm)
            .map { it.value.filter { c -> c.isDigit() } }
            .filter { it.length in 8..13 }
        return runs.firstOrNull()
    }

    private fun bluetoothName(norm: String): String? {
        val markers = listOf(
            "meu fone e", "fone e", "o fone e", "de ouvido e",
            "bluetooth e", "o bluetooth e", "se chama", "chama"
        )
        for (m in markers) {
            val i = norm.indexOf(m)
            if (i >= 0) {
                val rest = norm.substring(i + m.length).trim(' ', ',', '.', '!', '?')
                if (rest.isNotEmpty()) return rest.split(' ').take(4).joinToString(" ")
            }
        }
        return null
    }

    private fun factsToJson(): String {
        val arr = JSONArray()
        facts.forEach { (k, v) -> arr.put(JSONObject().put("k", k).put("v", v)) }
        return arr.toString()
    }

    private fun persistFacts(p: android.content.SharedPreferences) {
        p.edit().putString(KEY_FACTS, factsToJson()).apply()
    }
}
