package ru.joutak.creakywars.game

data class GamePhase(
    val name: String,
    val durationSeconds: Long,
    val resourceMultiplier: Double,
    val respawnEnabled: Boolean,
    val borderShrink: Boolean,
    val borderShrinkSpeed: Double,
    val borderFinalSize: Double,
    val startMessage: String,
    val endMessage: String
)