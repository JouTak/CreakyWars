package ru.joutak.creakywars.game

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.Scoreboard
import java.util.*
import org.bukkit.scoreboard.Team as BukkitTeam

class TeamScoreboard(private val game: Game) {

    private val locatorTeamPrefix = "cw_loc_"
    private val locatorSpectatorTeamName = "cw_loc_spec"

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

    fun update(remaining: Long) {
        // Update only online viewers we know about.
        val toRemove = mutableListOf<UUID>()
        for ((uuid, _) in states) {
            val player = Bukkit.getPlayer(uuid)
            if (player == null || !player.isOnline) {
                toRemove += uuid
                continue
            }
            update(player, remaining)
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

        // Locator bar uses scoreboard teams as an override for player indicator colors.
        ensureLocatorTeams(scoreboard)

        states[uuid] = BoardState(scoreboard, objective, previous)
        player.scoreboard = scoreboard
    }

    private fun update(player: Player, remaining: Long) {
        val state = states[player.uniqueId] ?: return

        // wipe previous
        state.lastEntries.forEach { state.scoreboard.resetScores(it) }

        val lines = buildLines(player, remaining)

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

        // Keep locator-team membership in sync for this viewer.
        updateLocatorTeams(state.scoreboard)

        state.lastEntries = entries.toSet()
    }

    private fun ensureLocatorTeams(scoreboard: Scoreboard) {
        for (team in game.teams) {
            val name = "$locatorTeamPrefix${team.id}"
            val t = scoreboard.getTeam(name) ?: scoreboard.registerNewTeam(name)
            t.color = team.color
            t.setOption(BukkitTeam.Option.NAME_TAG_VISIBILITY, BukkitTeam.OptionStatus.ALWAYS)
            t.setOption(BukkitTeam.Option.COLLISION_RULE, BukkitTeam.OptionStatus.ALWAYS)
        }

        val spec = scoreboard.getTeam(locatorSpectatorTeamName) ?: scoreboard.registerNewTeam(locatorSpectatorTeamName)
        spec.color = ChatColor.AQUA
        spec.setOption(BukkitTeam.Option.NAME_TAG_VISIBILITY, BukkitTeam.OptionStatus.ALWAYS)
        spec.setOption(BukkitTeam.Option.COLLISION_RULE, BukkitTeam.OptionStatus.ALWAYS)
    }

    private fun updateLocatorTeams(scoreboard: Scoreboard) {
        ensureLocatorTeams(scoreboard)

        // Desired entries per locator team for this viewer.
        val desiredByTeamId = HashMap<Int, MutableSet<String>>(4)
        for (t in game.teams) desiredByTeamId[t.id] = LinkedHashSet()

        for (p in game.players) {
            val data = game.getPlayerData(p) ?: continue
            val teamId = data.team?.id ?: continue
            desiredByTeamId[teamId]?.add(p.name)
        }

        // Spectators also get a stable color (matches the scoreboard "SPECTATE" label).
        val desiredSpectators = LinkedHashSet<String>()
        for (s in game.spectatorsOnline) {
            desiredSpectators.add(s.name)
        }

        // Remove stale entries first.
        for (t in game.teams) {
            val bukkitTeam = scoreboard.getTeam("$locatorTeamPrefix${t.id}") ?: continue
            val desired = desiredByTeamId[t.id] ?: emptySet()
            val toRemove = bukkitTeam.entries.filter { it !in desired }
            toRemove.forEach { bukkitTeam.removeEntry(it) }
        }
        scoreboard.getTeam(locatorSpectatorTeamName)?.let { bukkitTeam ->
            val toRemove = bukkitTeam.entries.filter { it !in desiredSpectators }
            toRemove.forEach { bukkitTeam.removeEntry(it) }
        }

        // Add / move entries to the correct team.
        for ((teamId, desired) in desiredByTeamId) {
            val bukkitTeam = scoreboard.getTeam("$locatorTeamPrefix$teamId") ?: continue
            desired.forEach { bukkitTeam.addEntry(it) }
        }
        scoreboard.getTeam(locatorSpectatorTeamName)?.let { bukkitTeam ->
            desiredSpectators.forEach { bukkitTeam.addEntry(it) }
        }
    }

    private fun buildLines(viewer: Player, remaining: Long): List<String> {
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

        lines += " "

        lines += "§7До смены фаз: ${formatTime(remaining)}"

        return lines
    }

    private fun formatTime(ticks: Long): String {
        var seconds = ticks / 20

        val minutes = seconds / 60

        seconds -= minutes * 60

        val nil = if (seconds < 10) {
            "0"
        } else {
            ""
        }

        return "§r$minutes:$nil$seconds"
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
