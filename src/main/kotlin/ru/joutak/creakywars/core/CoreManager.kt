package ru.joutak.creakywars.core

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import ru.joutak.creakywars.game.Game
import ru.joutak.creakywars.game.GameManager
import ru.joutak.creakywars.game.Team
import ru.joutak.creakywars.utils.PluginManager
import ru.joutak.creakywars.utils.SpawnLocation
import java.util.UUID

object CoreManager : Listener {
    private val activeCores = mutableMapOf<Game, MutableMap<Team, Core>>()
    
    fun init() {
        Bukkit.getPluginManager().registerEvents(this, PluginManager.getPlugin())
        PluginManager.getLogger().info("CoreManager инициализирован!")
    }
    
    fun initializeCores(game: Game) {
        val cores = mutableMapOf<Team, Core>()
        
        game.teams.forEachIndexed { index, team ->
            val coreLocation = game.arena.mapConfig.coreLocations.getOrNull(index)
            if (coreLocation != null) {
                val core = Core(game, team, coreLocation)
                core.spawn()
                cores[team] = core
            } else {
                PluginManager.getLogger().warning("Нет локации ядра для команды ${team.name}")
            }
        }
        
        activeCores[game] = cores
        PluginManager.getLogger().info("Инициализировано ${cores.size} ядер для игры #${game.arena.id}")
    }
    
    fun clearCores(game: Game) {
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
    
    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val block = event.block
        
        val core = getCore(block) ?: return

        val game = GameManager.getGame(player) ?: return

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

        fun spawn() {
            val loc = location.toLocation(game.arena.world)
            coreBlock = loc.block

            coreBlock?.type = Material.CREAKING_HEART

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
        
        fun isCore(block: Block): Boolean {
            return coreBlock == block
        }
        
        fun destroy(destroyer: Player?) {
            coreBlock?.type = Material.AIR
            hologram?.remove()
        }
        
        fun remove() {
            coreBlock?.type = Material.AIR
            hologram?.remove()
        }
    }
}