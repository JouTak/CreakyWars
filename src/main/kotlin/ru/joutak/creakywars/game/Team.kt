@file:Suppress("DEPRECATION")

package ru.joutak.creakywars.game

import org.bukkit.ChatColor
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.*

data class Team(
    val id: Int,
    val name: String,
    val color: ChatColor,
    val woolColor: Material,
    val players: MutableSet<UUID> = mutableSetOf(),
    var coreDestroyed: Boolean = false,
    var livesRemaining: Int = -1
) {
    var forgeTier: Int = 0
    var protectionLevel: Int = 0
    var sharpnessLevel: Int = 0
    var efficiencyLevel: Int = 0
    var hasFastRespawn: Boolean = false
    var trapActive: Boolean = false

    fun addPlayer(player: UUID) {
        players.add(player)
    }

    fun removePlayer(player: UUID) {
        players.remove(player)
    }

    fun hasPlayer(player: UUID): Boolean {
        return players.contains(player)
    }

    fun getOnlinePlayers(): List<Player> {
        return players.mapNotNull { org.bukkit.Bukkit.getPlayer(it) }
    }

    fun getAlivePlayers(game: Game): List<Player> {
        return getOnlinePlayers().filter { player ->
            val playerData = game.getPlayerData(player)
            playerData?.isAlive == true && player.gameMode != GameMode.SPECTATOR
        }
    }

    fun isEliminated(game: Game): Boolean {
        val onlinePlayers = getOnlinePlayers()
        if (onlinePlayers.isEmpty()) {
            return true
        }

        if (coreDestroyed) {
            val alivePlayers = getAlivePlayers(game)
            if (alivePlayers.isNotEmpty()) {
                return false
            }

            // "Last chance" respawn: if players are currently waiting to respawn when the core gets destroyed,
            // the next respawn must still happen. Do not eliminate the team until those respawns resolve.
            val hasPendingRespawn = players.any { uuid -> game.hasPendingLastChanceRespawn(uuid) }
            return !hasPendingRespawn
        }

        return false
    }

    fun canRespawn(): Boolean {
        return !coreDestroyed || livesRemaining > 0
    }

    fun decrementLives() {
        if (livesRemaining > 0) {
            livesRemaining--
        }
    }

    companion object {
        @Suppress("DEPRECATION")
        fun createDefaultTeams(count: Int): List<Team> {
            val teams = mutableListOf<Team>()
            val colors = listOf(
                Triple(ChatColor.GOLD, "Оранжевые", Material.ORANGE_TERRACOTTA),
                Triple(ChatColor.BLUE, "Синие", Material.BLUE_TERRACOTTA),
                Triple(ChatColor.LIGHT_PURPLE, "Розовые", Material.PINK_TERRACOTTA),
                Triple(ChatColor.GREEN, "Зелёные", Material.GREEN_TERRACOTTA),
            )

            for (i in 0 until minOf(count, colors.size)) {
                val (color, name, wool) = colors[i]
                teams.add(Team(i, name, color, wool))
            }

            return teams
        }
    }
}