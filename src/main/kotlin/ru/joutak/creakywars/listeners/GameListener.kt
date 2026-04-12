package ru.joutak.creakywars.listeners

import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.entity.Silverfish
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.plugin.Plugin
import ru.joutak.creakywars.arenas.ArenaManager
import ru.joutak.creakywars.arenas.ArenaState
import ru.joutak.creakywars.config.GameConfig
import ru.joutak.creakywars.config.ScenarioConfig
import ru.joutak.creakywars.game.GameManager
import kotlin.random.Random

@Suppress("DEPRECATION")
class GameListener(val plugin: Plugin) : Listener {

    private fun isAllowedReplaceable(type: Material): Boolean {
        return when (type) {
            // Decorative plants that players should be able to place blocks into.
            Material.SHORT_GRASS,
            Material.TALL_GRASS,
            Material.FERN,
            Material.LARGE_FERN,
            Material.DEAD_BUSH -> true

            else -> false
        }
    }

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val game = GameManager.getGame(player)

        if (game == null) {
            if (ArenaManager.isArena(player.world)) {
                event.isCancelled = true
            }
            return
        }

        val type = event.block.type

        // Allow players to extinguish fire created by explosions (e.g. fireballs)
        if (type == Material.FIRE || type == Material.SOUL_FIRE) {
            return
        }

        val loc = event.block.location

        if (game.infestedBlocks.contains(loc)) {
            game.infestedBlocks.remove(loc)
            spawnInfested(game.arena.world, loc)
            event.isDropItems = false
            return
        }

        if (!GameConfig.allowedBlocks.contains(type)) {
            event.isCancelled = true
        }
    }

    private fun spawnInfested(w: World, loc: Location) {
        var bufferChance = 1.0

        for (i in 0..GameConfig.maxInfested) {
            if (Random.nextDouble() <= bufferChance) {
                w.spawnEntity(loc.add(Random.nextDouble(0.1), 0.0, Random.nextDouble(0.1)), GameConfig.infestedEntity)
            }

            bufferChance *= GameConfig.infestedSpawnChance
        }
    }

    @EventHandler
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        if (event.entity is Silverfish) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        val player = event.player
        val game = GameManager.getGame(player)

        if (game == null) {
            if (ArenaManager.isArena(player.world)) {
                event.isCancelled = true
            }
            return
        }

        val blockLocation = event.block.location
        val blockType = event.block.type

        // Prevent replacing protected map blocks by placing into them,
        // otherwise players can destroy protected blocks without breaking.
        // But allow replacing decorative plants (grass/fern) so building feels natural.
        val replacedType = event.blockReplacedState.type
        if (!replacedType.isAir && !isAllowedReplaceable(replacedType)) {
            event.isCancelled = true
            return
        }

        if (event.itemInHand.itemMeta.persistentDataContainer.has(NamespacedKey(plugin, "infested-block"))) {
            game.infestedBlocks.add(event.block.location)
            return
        }

        if (!GameConfig.allowedBlocks.contains(blockType)) {
            event.isCancelled = true
            return
        }

        if (event.block.y > game.arena.world.maxHeight - 10) {
            event.isCancelled = true
            return
        }

        val protectionRadius = GameConfig.protectionRadius

        for ((_, locations) in game.arena.mapConfig.resourceSpawners) {
            for (spawnLoc in locations) {
                val loc = spawnLoc.toLocation(game.arena.world)
                if (blockLocation.distance(loc) < protectionRadius) {
                    event.isCancelled = true
                    return
                }
            }
        }

        for (traderLoc in game.arena.mapConfig.traderLocations) {
            val loc = traderLoc.toLocation(game.arena.world)
            if (blockLocation.distance(loc) < protectionRadius) {
                event.isCancelled = true
                return
            }
        }

        for (teamSpawn in game.arena.mapConfig.teamSpawns) {
            val loc = teamSpawn.toLocation(game.arena.world)
            if (blockLocation.distance(loc) < protectionRadius) {
                event.isCancelled = true
                return
            }
        }
    }

    @EventHandler
    fun onCraftItem(event: CraftItemEvent) {
        val player = event.whoClicked as? Player ?: return
        val game = GameManager.getGame(player)

        if (game != null) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onPlayerMove(event: PlayerMoveEvent) {
        val player = event.player
        val game = GameManager.getGame(player) ?: return

        if (game.arena.state != ArenaState.IN_GAME) return

        if (player.gameMode == GameMode.SURVIVAL) {
            if (player.location.y < GameConfig.voidKillHeight) {
                player.health = 0.0
                return
            }

            val phaseIndex = game.getCurrentPhaseIndex()
            if (phaseIndex >= 0 && phaseIndex < ScenarioConfig.phases.size) {
                val phase = ScenarioConfig.phases[phaseIndex]
                if (phase.badWeatherEnabled && player.location.y > phase.badWeatherKillHeight.toDouble()) {
                    player.health = 0.0
                }
            }
        }
    }

    @EventHandler
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val damager = event.damager as? Player ?: return
        val victim = event.entity as? Player ?: return
        val game = GameManager.getGame(damager) ?: return

        if (GameManager.getGame(victim) != game) {
            event.isCancelled = true
            return
        }
        if (game.arena.state != ArenaState.IN_GAME) {
            event.isCancelled = true
            return
        }

        val damagerTeam = game.getTeam(damager)
        val victimTeam = game.getTeam(victim)

        if (damagerTeam == victimTeam) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onFoodLevelChange(event: FoodLevelChangeEvent) {
        val player = event.entity as? Player ?: return
        val game = GameManager.getGame(player)

        if (game == null) {
            if (ArenaManager.isArena(player.world)) {
                event.isCancelled = true
                player.foodLevel = 20
            }
            return
        }

        if (game.arena.state == ArenaState.WAITING ||
            game.arena.state == ArenaState.STARTING ||
            GameConfig.infiniteFood
        ) {

            event.isCancelled = true
            player.foodLevel = 20
        }
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        val game = GameManager.getGame(player)

        if (game == null && ArenaManager.isArena(player.world)) {
            event.isCancelled = true
        }
    }
}