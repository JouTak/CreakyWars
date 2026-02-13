package ru.joutak.creakywars.utils

import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * A tiny tick-level guard used to suppress item usage for players.
 *
 * We use it to prevent right-click "use" items (Wind Charge / Fire Charge, etc.)
 * from activating when the player intended to interact with a Trader.
 */
object UseSuppressor {

    // We only need to suppress the "same click" that opened a GUI. Keep it short to avoid breaking normal usage.
    private const val DEFAULT_DURATION_MS: Long = 80
    private val untilMs = ConcurrentHashMap<UUID, Long>()

    fun mark(playerId: UUID, durationMs: Long = DEFAULT_DURATION_MS) {
        untilMs[playerId] = System.currentTimeMillis() + durationMs
    }

    fun clear(playerId: UUID) {
        untilMs.remove(playerId)
    }

    fun isSuppressed(playerId: UUID): Boolean {
        val until = untilMs[playerId] ?: return false
        val now = System.currentTimeMillis()
        if (now <= until) return true
        untilMs.remove(playerId, until)
        return false
    }
}
