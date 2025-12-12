package ru.joutak.creakywars.game

import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Player
import ru.joutak.creakywars.arenas.Arena
import ru.joutak.creakywars.arenas.ArenaManager
import ru.joutak.creakywars.arenas.ArenaState
import ru.joutak.creakywars.config.AdminConfig
import ru.joutak.creakywars.utils.PluginManager
import ru.joutak.minigames.domain.GameInstance
import ru.joutak.minigames.managers.MatchmakingManager

object GameManager {
    private val activeGames = mutableMapOf<Arena, Game>()

    fun init() {}

    fun createGame(instance: GameInstance) {
        val mapName = instance.config.meta["mapName"] as? String ?: "default"

        val arena = ArenaManager.createPhysicalArena(mapName)
        arena.state = ArenaState.STARTING

        val cwTeams = Team.createDefaultTeams(AdminConfig.teamsCount)

        val game = Game(arena, cwTeams)
        arena.game = game
        activeGames[arena] = game

        instance.teams.toList().forEachIndexed { teamIndex, apiTeamPlayers ->
            val cwTeam = cwTeams.getOrNull(teamIndex) ?: return@forEachIndexed

            apiTeamPlayers.toList().forEach { apiPlayer ->
                val player = Bukkit.getPlayer(apiPlayer.uniqueId)
                if (player != null) {
                    game.addPlayer(player, cwTeam)
                    MatchmakingManager.removePlayer(player)
                }
            }
        }

        PluginManager.getLogger().info("Запуск игры на карте $mapName (ID арены: ${arena.id})")
        game.startCountdown()
    }

    fun getGame(player: Player): Game? = activeGames.values.firstOrNull { it.players.contains(player) }

    fun getGame(world: World): Game? {
        return activeGames.values.firstOrNull { it.arena.world.name == world.name }
    }

    fun isInGame(player: Player): Boolean = getGame(player) != null

    fun getActiveGames(): List<Game> = activeGames.values.toList()

    fun onGameEnd(game: Game) {
        activeGames.remove(game.arena)
        ArenaManager.deleteArena(game.arena)
    }

    fun shutdownAll() {
        activeGames.values.toList().forEach { it.forceEnd() }
    }
}