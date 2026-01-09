package ru.joutak.creakywars.game

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.World
import org.bukkit.entity.Player
import ru.joutak.creakywars.arenas.Arena
import ru.joutak.creakywars.arenas.ArenaManager
import ru.joutak.creakywars.arenas.ArenaState
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

        // Team count must follow the instance config (API), not hardcoded admin-config.
        val cwTeams = Team.createDefaultTeams(instance.config.teamCount)

        val game = Game(arena, cwTeams)
        arena.game = game
        activeGames[arena] = game

        // Snapshot waiting teams and move players into the game.
        // IMPORTANT: do NOT call MatchmakingManager.removePlayer() here – it would remove them from
        // active match participants and instantly free the instance. Only clear waiting-team lists.
        val playersToRemoveFromWaitingTeams = mutableListOf<Player>()
        val teamsSnapshot = instance.teams.map { it.toList() }

        teamsSnapshot.forEachIndexed { teamIndex, teamPlayers ->
            val cwTeam = cwTeams.getOrNull(teamIndex) ?: return@forEachIndexed

            teamPlayers.forEach { apiPlayer ->
                val player = Bukkit.getPlayer(apiPlayer.uniqueId) ?: return@forEach
                game.addPlayer(player, cwTeam)
                playersToRemoveFromWaitingTeams.add(player)
            }
        }

        playersToRemoveFromWaitingTeams.forEach { p ->
            try {
                instance.removePlayer(p)
            } catch (_: Exception) {
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
        // Plugin shutdown path: delayed tasks from endGame() won't run reliably.
        // We must immediately release players from the API "started match" state,
        // otherwise they can get stuck and won't be able to queue again.
        val mainWorld = Bukkit.getWorlds().firstOrNull()
        activeGames.values.toList().forEach { game ->
            val playersSnapshot = game.players.toList()
            playersSnapshot.forEach { player ->
                try {
                    MatchmakingManager.removePlayer(player)
                } catch (_: Exception) {
                }

                // Minimal safe reset; ArenaManager.deleteAllArenas() will handle world cleanup.
                player.scoreboard = Bukkit.getScoreboardManager()?.mainScoreboard
                    ?: Bukkit.getScoreboardManager()!!.newScoreboard
                player.inventory.clear()
                player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
                player.health = player.maxHealth
                player.foodLevel = 20
                player.saturation = 20f
                player.fireTicks = 0
                player.gameMode = GameMode.ADVENTURE
                if (mainWorld != null) {
                    player.teleport(mainWorld.spawnLocation)
                }
            }
            game.disableListeners()
        }

        activeGames.clear()
    }
}