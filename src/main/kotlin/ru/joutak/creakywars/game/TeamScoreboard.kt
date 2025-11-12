package ru.joutak.creakywars.game

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.Scoreboard

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

        var score = game.teams.size

        game.teams.forEach { team ->
            val status = when {
                team.isEliminated() -> "§c§m${team.color}${team.name} §c✗"
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