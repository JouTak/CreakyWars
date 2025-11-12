package ru.joutak.creakywars.game

import org.bukkit.entity.Player
import ru.joutak.creakywars.arenas.Arena
import ru.joutak.creakywars.arenas.ArenaManager
import ru.joutak.creakywars.config.AdminConfig
import ru.joutak.creakywars.queue.QueueManager
import ru.joutak.creakywars.utils.PluginManager

object GameManager {
    private val activeGames = mutableListOf<Game>()
    
    fun init() {
        PluginManager.getLogger().info("GameManager инициализирован!")
    }
    
    fun getActiveGames(): List<Game> = activeGames.toList()
    
    fun getGame(player: Player): Game? {
        return activeGames.firstOrNull { it.players.contains(player) }
    }
    
    fun getGame(arena: Arena): Game? {
        return activeGames.firstOrNull { it.arena == arena }
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
        val teams = Team.createDefaultTeams(AdminConfig.teamsCount)

        if (spartakiadaMode) {
            assignPlayersToTeamsSpartakiada(players, teams)
        } else {
            assignPlayersToTeamsRandom(players, teams)
        }

        val game = Game(arena, teams)
        arena.game = game

        players.forEach { player ->
            val team = teams.firstOrNull { it.hasPlayer(player.uniqueId) }
            if (team != null) {
                game.addPlayer(player, team)
            }
        }
        
        activeGames.add(game)
        PluginManager.getLogger().info("Создана игра на арене #${arena.id} с ${players.size} игроками")
        
        return game
    }
    
    private fun assignPlayersToTeamsRandom(players: List<Player>, teams: List<Team>) {
        val shuffled = players.shuffled()
        shuffled.forEachIndexed { index, player ->
            val team = teams[index % teams.size]
            team.addPlayer(player.uniqueId)
        }
    }
    
    private fun assignPlayersToTeamsSpartakiada(players: List<Player>, teams: List<Team>) {
        // TODO: Загрузить распределение из файла спартакиады
        assignPlayersToTeamsRandom(players, teams)
    }
    
    fun onGameEnd(game: Game) {
        activeGames.remove(game)
        ArenaManager.deleteArena(game.arena)
        PluginManager.getLogger().info("Игра на арене #${game.arena.id} завершена")
        QueueManager.checkQueue()
    }
    
    fun shutdownAll() {
        activeGames.toList().forEach { game ->
            game.forceEnd()
        }
    }
}