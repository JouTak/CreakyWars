package ru.joutak.creakywars.game

import org.bukkit.entity.Player
import ru.joutak.creakywars.arenas.Arena
import ru.joutak.creakywars.arenas.ArenaManager
import ru.joutak.creakywars.arenas.ArenaState
import ru.joutak.creakywars.config.AdminConfig
import ru.joutak.creakywars.queue.QueueManager
import ru.joutak.creakywars.spartakiada.SpartakiadaManager
import ru.joutak.creakywars.utils.MessageUtils
import ru.joutak.creakywars.utils.PluginManager

object GameManager {
    private val activeGames = mutableMapOf<Arena, Game>()

    fun init() {
        PluginManager.getLogger().info("GameManager инициализирован!")
    }

    fun getActiveGames(): List<Game> = activeGames.values.toList()

    fun getGame(player: Player): Game? {
        return activeGames.values.firstOrNull { it.players.contains(player) }
    }

    fun getGame(arena: Arena): Game? {
        return activeGames[arena]
    }

    fun isInGame(player: Player): Boolean {
        return getGame(player) != null
    }

    fun canStartNewGame(): Boolean {
        return activeGames.size < AdminConfig.maxParallelGames
    }

    fun createGame(players: List<Player>, mapName: String, spartakiadaMode: Boolean): Game? {
        if (!canStartNewGame()) {
            PluginManager.getLogger().warning("Достигнут лимит параллельных игр!")
            return null
        }

        val arena = ArenaManager.createArena(mapName)
        arena.state = ArenaState.STARTING

        val teams = Team.createDefaultTeams(AdminConfig.teamsCount)

        if (spartakiadaMode) {
            assignPlayersToTeamsSpartakiada(players, teams)
        } else {
            assignPlayersToTeamsNormal(players, teams)
        }

        val game = Game(arena, teams)
        arena.game = game

        players.forEach { player ->
            val team = teams.firstOrNull { it.hasPlayer(player.uniqueId) }
            if (team != null) {
                game.addPlayer(player, team)
            }
        }

        activeGames[arena] = game
        PluginManager.getLogger().info("✓ Создана игра на арене #${arena.id} с ${players.size} игроками")

        return game
    }

    private fun assignPlayersToTeamsNormal(players: List<Player>, teams: List<Team>) {
        if (AdminConfig.debugMode) {
            val shuffled = players.shuffled()
            shuffled.forEachIndexed { index, player ->
                val team = teams[index % teams.size]
                team.addPlayer(player.uniqueId)
            }
        } else {
            val teamAssignments = mutableMapOf<Int, MutableList<Player>>()

            players.forEach { player ->
                val preferredTeamId = QueueManager.getPlayerTeamPreference(player) ?: (players.indexOf(player) % teams.size)
                teamAssignments.getOrPut(preferredTeamId) { mutableListOf() }.add(player)
            }

            teamAssignments.forEach { (teamId, teamPlayers) ->
                val team = teams.getOrNull(teamId) ?: teams[0]
                teamPlayers.forEach { player ->
                    team.addPlayer(player.uniqueId)
                }
            }
        }
    }

    private fun assignPlayersToTeamsSpartakiada(players: List<Player>, teams: List<Team>) {
        val teamGroups = players.groupBy { player ->
            SpartakiadaManager.getTeamAssignment(player)
        }

        teamGroups.entries.forEachIndexed { index, (spartakiadaTeamId, teamPlayers) ->
            val gameTeam = teams.getOrNull(index) ?: return@forEachIndexed
            teamPlayers.forEach { player ->
                gameTeam.addPlayer(player.uniqueId)
            }
        }
    }

    fun removePlayerFromGame(player: Player): Boolean {
        val game = getGame(player) ?: return false

        if (AdminConfig.debugMode) {
            PluginManager.getLogger().info("[DEBUG] Удаление игрока ${player.name} из игры")
        }

        game.removePlayer(player)
        MessageUtils.sendMessage(player, "§cВы покинули игру")

        return true
    }

    fun onGameEnd(game: Game) {
        if (AdminConfig.debugMode) {
            PluginManager.getLogger().info("[DEBUG] Завершение игры на арене #${game.arena.id}")
        }

        activeGames.remove(game.arena)
        ArenaManager.deleteArena(game.arena)
        PluginManager.getLogger().info("Игра на арене #${game.arena.id} завершена")
        QueueManager.checkQueue()
    }

    fun shutdownAll() {
        activeGames.values.toList().forEach { game ->
            game.forceEnd()
        }
    }
}