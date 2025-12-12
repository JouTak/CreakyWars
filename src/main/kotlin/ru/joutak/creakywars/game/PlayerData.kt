package ru.joutak.creakywars.game

import java.util.UUID

data class PlayerData(
    val uuid: UUID,
    var team: Team?,
    var isAlive: Boolean = true,
    var kills: Int = 0,
    var deaths: Int = 0,
    var finalKills: Int = 0,
    var resourcesCollected: Int = 0,
    var loadout: PlayerLoadout? = null
) {
    fun addKill() {
        kills++
    }

    fun addDeath() {
        deaths++
    }

    fun addFinalKill() {
        finalKills++
    }

    fun reset() {
        kills = 0
        deaths = 0
        finalKills = 0
        resourcesCollected = 0
        isAlive = true
    }
}