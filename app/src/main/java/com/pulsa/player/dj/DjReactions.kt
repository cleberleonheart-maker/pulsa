package com.pulsa.player.dj

import android.content.Context
import com.pulsa.player.R
import kotlin.random.Random

/**
 * Reações da Virgínia a eventos sociais (gostei, não gostei, pulada, próxima).
 * Alterna frases para não soar repetitiva e quebra o padrão com lampejo do
 * aprendizado (quando o usuário já reagiu muito à mesma música).
 */
object DjReactions {

    private val lastPick = HashMap<String, Int>()

    fun like(context: Context): String = pick(context, R.array.dj_reaction_like, "like")
    fun unliked(context: Context): String = pick(context, R.array.dj_reaction_unlike, "unlike")
    fun dislike(context: Context): String = pick(context, R.array.dj_reaction_dislike, "dislike")
    fun skip(context: Context): String = pick(context, R.array.dj_reaction_skip, "skip")
    fun next(context: Context): String = pick(context, R.array.dj_reaction_next, "next")

    private fun pick(context: Context, arrayRes: Int, key: String): String {
        val arr = context.resources.getStringArray(arrayRes)
        if (arr.isEmpty()) return ""
        val rand = Random.nextInt(arr.size)
        val last = lastPick[key]
        val idx = if (arr.size > 1 && rand == last) (rand + 1) % arr.size else rand
        lastPick[key] = idx
        return arr[idx]
    }
}