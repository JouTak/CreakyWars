package ru.joutak.creakywars.listeners

import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import ru.joutak.creakywars.arenas.ArenaManager
import ru.joutak.creakywars.arenas.ArenaState
import ru.joutak.creakywars.config.GameConfig
import ru.joutak.creakywars.game.GameManager

@Suppress("DEPRECATION")
class GameListener : Listener {

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

        if (!GameConfig.allowedBlocks.contains(event.block.type)) {
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

        if (player.gameMode == GameMode.SURVIVAL && player.location.y < GameConfig.voidKillHeight) {
            player.health = 0.0
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
            GameConfig.infiniteFood) {

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