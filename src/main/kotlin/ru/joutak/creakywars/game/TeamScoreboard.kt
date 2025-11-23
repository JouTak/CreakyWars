package ru.joutak.creakywars.game

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.Scoreboard

@Suppress("DEPRECATION")
class TeamScoreboard(private val game: Game) {
    private val scoreboard: Scoreboard = Bukkit.getScoreboardManager()!!.newScoreboard
    private val objective: Objective

    init {
        objective = scoreboard.registerNewObjective("teams", "dummy", "§6§lCREAKY WARS")
        objective.displaySlot = DisplaySlot.SIDEBAR
        update()
    }

    fun update() {
        scoreboard.entries.forEach { scoreboard.resetScores(it) }
        val activeTeams = game.getActiveTeams()
        var score = activeTeams.size

        activeTeams.forEach { team ->
            val status = when {
                team.isEliminated(game) -> "§c§m${team.name} §c✗"
                team.coreDestroyed -> "${team.color}${team.name} §c✗"
                else -> "${team.color}${team.name} §a✓"
            }

            objective.getScore(status).score = score
            score--
        }
    }

    fun addPlayer(player: Player) {
        player.scoreboard = scoreboard
    }

    fun removePlayer(player: Player) {
        player.scoreboard = Bukkit.getScoreboardManager()!!.mainScoreboard
    }
}