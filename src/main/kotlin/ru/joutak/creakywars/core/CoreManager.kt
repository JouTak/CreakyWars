package ru.joutak.creakywars.core

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.scheduler.BukkitTask
import ru.joutak.creakywars.game.Game
import ru.joutak.creakywars.game.GameManager
import ru.joutak.creakywars.game.Team
import ru.joutak.creakywars.utils.PluginManager
import ru.joutak.creakywars.utils.SpawnLocation

object CoreManager : Listener {
    private val activeCores = mutableMapOf<Game, MutableMap<Team, Core>>()
    private val coreCheckTasks = mutableMapOf<Game, BukkitTask>()

    fun init() {
        Bukkit.getPluginManager().registerEvents(this, PluginManager.getPlugin())
        PluginManager.getLogger().info("CoreManager инициализирован!")
    }

    fun initializeCores(game: Game, teamsToInit: List<Team>) {
        val cores = mutableMapOf<Team, Core>()

        teamsToInit.forEach { team ->
            val teamIndex = game.teams.indexOf(team)
            val coreLocation = game.arena.mapConfig.coreLocations.getOrNull(teamIndex)

            if (coreLocation != null) {
                val core = Core(game, team, coreLocation)
                core.spawn()
                cores[team] = core
            } else {
                PluginManager.getLogger().warning("Нет локации ядра для команды ${team.name}")
            }
        }

        activeCores[game] = cores

        startCoreCheck(game)

        PluginManager.getLogger().info("Инициализировано ${cores.size} ядер для игры #${game.arena.id}")
    }

    private fun startCoreCheck(game: Game) {
        val task = Bukkit.getScheduler().runTaskTimer(
            PluginManager.getPlugin(),
            Runnable {
                val cores = activeCores[game] ?: return@Runnable

                cores.values.forEach { core ->
                    if (!core.isDestroyed) {
                        core.checkAndFix()
                    }
                }
            },
            20L,
            20L
        )

        coreCheckTasks[game] = task
    }

    fun clearCores(game: Game) {
        coreCheckTasks[game]?.cancel()
        coreCheckTasks.remove(game)

        activeCores[game]?.values?.forEach { it.remove() }
        activeCores.remove(game)
    }

    fun getCore(block: Block): Core? {
        for (cores in activeCores.values) {
            for (core in cores.values) {
                if (core.isCore(block)) {
                    return core
                }
            }
        }
        return null
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val block = event.block

        val core = getCore(block) ?: return

        val game = GameManager.getGame(player) ?: return

        if (core.isDestroyed) {
            event.isCancelled = true
            return
        }

        if (core.team.players.isEmpty()) {
            event.isCancelled = true
            return
        }

        val playerTeam = game.getTeam(player)
        if (playerTeam == core.team) {
            event.isCancelled = true
            return
        }

        core.destroy(player)
        event.isCancelled = true

        game.handleCoreDestroyed(core.team, player)
    }

    class Core(
        private val game: Game,
        val team: Team,
        private val location: SpawnLocation
    ) {
        private var coreBlock: Block? = null
        private var hologram: ArmorStand? = null
        var isDestroyed: Boolean = false
            private set

        fun spawn() {
            val loc = location.toLocation(game.arena.world)
            coreBlock = loc.block

            coreBlock?.type = Material.CREAKING_HEART
            setActive(true)

            val hologramLoc = loc.clone().add(0.5, 1.5, 0.5)
//            hologram = game.arena.world.spawn(hologramLoc, ArmorStand::class.java).apply {
//                setGravity(false)
//                isVisible = false
//                isCustomNameVisible = true
//                @Suppress("DEPRECATION")
//                customName = "${team.color}${team.name} - Сердце"
//                isMarker = true
//                setAI(false)
//            }
        }

        fun checkAndFix() {
            val block = coreBlock ?: return

            if (block.type != Material.CREAKING_HEART) {
                PluginManager.getLogger().warning("Ядро команды ${team.name} было заменено! Восстанавливаем...")
                block.type = Material.CREAKING_HEART
            }

            val requiredActivity = !isDestroyed && team.players.isNotEmpty()

            if (requiredActivity && !isActive()) {
                setActive(true)
            } else if (!requiredActivity && isActive()) {
                setActive(false)
            }
        }

        private fun isActive(): Boolean {
            val block = coreBlock ?: return false
            if (block.type != Material.CREAKING_HEART) return false

            val blockData = block.blockData
            if (blockData is org.bukkit.block.data.type.CreakingHeart) {
                return blockData.isActive
            }
            return false
        }

        private fun setActive(active: Boolean) {
            val block = coreBlock ?: return
            if (block.type != Material.CREAKING_HEART) return

            val blockData = block.blockData
            if (blockData is org.bukkit.block.data.type.CreakingHeart) {
                blockData.isActive = active
                block.blockData = blockData
            }
        }

        fun isCore(block: Block): Boolean {
            return coreBlock == block
        }

        fun destroy(destroyer: Player?) {
            isDestroyed = true
            setActive(false)
            hologram?.remove()
        }

        fun remove() {
            isDestroyed = true
            coreBlock?.type = Material.AIR
            hologram?.remove()
        }
    }
}