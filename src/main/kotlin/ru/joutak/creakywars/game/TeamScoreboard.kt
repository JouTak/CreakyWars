package ru.joutak.creakywars.game

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.Scoreboard
import java.util.UUID

class TeamScoreboard(private val game: Game) {

    private data class BoardState(
        val scoreboard: Scoreboard,
        val objective: Objective,
        val previous: Scoreboard,
        var lastEntries: Set<String> = emptySet(),
    )

    private val states = mutableMapOf<UUID, BoardState>()

    private val uniqueTails: List<String> = ChatColor.values()
        .filter { it != ChatColor.MAGIC }
        .map { it.toString() }
        .distinct()

    fun addPlayer(player: Player) {
        ensure(player)
    }

    fun removePlayer(player: Player) {
        val state = states.remove(player.uniqueId) ?: return
        if (player.scoreboard == state.scoreboard) {
            player.scoreboard = state.previous
        }
    }

    fun update() {
        // Update only online viewers we know about.
        val toRemove = mutableListOf<UUID>()
        for ((uuid, _) in states) {
            val player = Bukkit.getPlayer(uuid)
            if (player == null || !player.isOnline) {
                toRemove += uuid
                continue
            }
            update(player)
        }
        toRemove.forEach { states.remove(it) }
    }

    private fun ensure(player: Player) {
        val uuid = player.uniqueId

        val existing = states[uuid]
        if (existing != null) {
            if (player.scoreboard != existing.scoreboard) {
                player.scoreboard = existing.scoreboard
            }
            return
        }

        val previous = player.scoreboard
        val scoreboard = Bukkit.getScoreboardManager().newScoreboard
        val objective = scoreboard.registerNewObjective("cw_teams", "dummy", "§6§lCREAKY WARS")
        objective.displaySlot = DisplaySlot.SIDEBAR

        states[uuid] = BoardState(scoreboard, objective, previous)
        player.scoreboard = scoreboard
    }

    private fun update(player: Player) {
        val state = states[player.uniqueId] ?: return

        // wipe previous
        state.lastEntries.forEach { state.scoreboard.resetScores(it) }

        val lines = buildLines(player)

        val entries = ArrayList<String>(lines.size)
        for ((i, line) in lines.withIndex()) {
            val safe = if (line.isBlank()) {
                // blank, but must be non-empty and unique
                ChatColor.values()[i % ChatColor.values().size].toString()
            } else {
                line.limitVisible(32)
            }

            val tail = uniqueTails[i % uniqueTails.size]
            val entry = safe + ChatColor.RESET + tail
            entries += entry
        }

        var score = entries.size
        for (entry in entries) {
            state.objective.getScore(entry).score = score
            score--
        }

        state.lastEntries = entries.toSet()
    }

    private fun buildLines(viewer: Player): List<String> {
        val lines = mutableListOf<String>()

        lines += "§7Матч: §e#${game.arena.id}"
        lines += "§7Ник: §f${viewer.name}"

        val data = game.getPlayerData(viewer)
        val team = data?.team
        if (team != null) {
            lines += "§7Команда: ${team.color}§l${team.name}§r"
        } else {
            lines += "§7Команда: §b§lSPECTATE§r"
        }

        lines += " "
        lines += "§eКоманды:"

        val viewerTeamId = team?.id
        for (t in game.getActiveTeams()) {
            lines += formatTeamLine(t, viewerTeamId != null && t.id == viewerTeamId)
        }

        return lines
    }

    private fun formatTeamLine(team: Team, isViewerTeam: Boolean): String {
        val eliminated = team.isEliminated(game)
        val status = when {
            eliminated -> "§c✗"
            team.coreDestroyed -> "§e⚠"
            else -> "§a✓"
        }

        val marker = if (isViewerTeam) "§6▶" else "§7•"

        val alive = team.getAlivePlayers(game).size
        val online = team.getOnlinePlayers().size

        val info = if (eliminated) {
            "§7выбыла"
        } else {
            "§f$alive/$online"
        }

        return "$marker $status ${team.color}${team.name} §8| $info"
    }

    /**
     * Cuts a string by *visible* length (ignoring \u00A7 color codes).
     */
    private fun String.limitVisible(maxVisible: Int): String {
        var visible = 0
        val sb = StringBuilder()
        var i = 0
        while (i < this.length) {
            val c = this[i]
            if (c == ChatColor.COLOR_CHAR && i + 1 < this.length) {
                sb.append(c).append(this[i + 1])
                i += 2
                continue
            }
            if (visible >= maxVisible) break
            sb.append(c)
            visible++
            i++
        }
        if (i < this.length) sb.append("…")
        return sb.toString()
    }
}
