package ru.joutak.creakywars.game

import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.UUID

data class Team(
    val id: Int,
    val name: String,
    val color: ChatColor,
    val woolColor: Material,
    val players: MutableSet<UUID> = mutableSetOf(),
    var coreDestroyed: Boolean = false,
    var livesRemaining: Int = -1
) {
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

    fun isEliminated(): Boolean {
        return coreDestroyed && getOnlinePlayers().isEmpty()
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
                Triple(ChatColor.RED, "Красные", Material.RED_TERRACOTTA),
                Triple(ChatColor.BLUE, "Синие", Material.BLUE_TERRACOTTA),
                Triple(ChatColor.GREEN, "Зелёные", Material.GREEN_TERRACOTTA),
                Triple(ChatColor.YELLOW, "Жёлтые", Material.YELLOW_TERRACOTTA),
                Triple(ChatColor.AQUA, "Голубые", Material.LIGHT_BLUE_TERRACOTTA),
                Triple(ChatColor.LIGHT_PURPLE, "Розовые", Material.PINK_TERRACOTTA),
                Triple(ChatColor.WHITE, "Белые", Material.WHITE_TERRACOTTA),
                Triple(ChatColor.GRAY, "Серые", Material.GRAY_TERRACOTTA)
            )

            for (i in 0 until minOf(count, colors.size)) {
                val (color, name, wool) = colors[i]
                teams.add(Team(i, name, color, wool))
            }

            return teams
        }
    }
}