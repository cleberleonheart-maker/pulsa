package com.pulsa.player.dj

/**
 * Memória de sessão: músicas que já tocaram enquanto o app está aberto.
 * Mantida em memória (morre com o processo) e usada para evitar repetições
 * dentro da mesma sessão de uso, sem mexer no aprendizado persistente.
 */
object DjSessionMemory {

    private val lock = Any()
    private val recent = LinkedHashMap<Long, Boolean>()
    private const val CAP = 200

    fun notePlayed(id: Long) {
        if (id < 0L) return
        synchronized(lock) {
            recent[id] = true
            while (recent.size > CAP) recent.remove(recent.keys.first())
        }
    }

    fun notePlayed(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        ids.forEach { notePlayed(it) }
    }

    fun recentIds(): Set<Long> = synchronized(lock) { recent.keys.toSet() }

    fun clear() = synchronized(lock) { recent.clear() }
}