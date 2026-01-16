package ru.joutak.creakywars.game

data class GamePhase(
    val name: String,
    val durationSeconds: Long,
    val endAtTick: Long?,
    val resourceMultiplier: Double,
    val respawnEnabled: Boolean,
    val borderShrink: Boolean,
    val borderShrinkSpeed: Double,
    val borderFinalSize: Double,
    val creakingSpeedAmplifier: Int,
    /**
     * Bad weather ceiling: if enabled, players above [badWeatherKillHeight] will be killed.
     */
    val badWeatherEnabled: Boolean,
    val badWeatherKillHeight: Int,
    val glowPlayers: Boolean,
    val startMessage: String,
    val endMessage: String
)
