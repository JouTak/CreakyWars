package ru.joutak.creakywars.listeners

import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.player.PlayerInteractEvent
import ru.joutak.creakywars.arenas.ArenaManager
import ru.joutak.creakywars.arenas.ArenaState
import ru.joutak.creakywars.game.GameManager
import ru.joutak.creakywars.utils.MessageUtils

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

        val allowedBlocks = listOf(
            Material.OAK_PLANKS,
            Material.CLAY,
            Material.END_STONE,
            Material.OBSIDIAN,
            Material.WHITE_TERRACOTTA,
            Material.RED_TERRACOTTA,
            Material.BLUE_TERRACOTTA,
            Material.GREEN_TERRACOTTA,
            Material.YELLOW_TERRACOTTA,
            Material.ORANGE_TERRACOTTA,
            Material.MAGENTA_TERRACOTTA,
            Material.LIGHT_BLUE_TERRACOTTA,
            Material.LIME_TERRACOTTA,
            Material.PINK_TERRACOTTA,
            Material.GRAY_TERRACOTTA,
            Material.LIGHT_GRAY_TERRACOTTA,
            Material.CYAN_TERRACOTTA,
            Material.PURPLE_TERRACOTTA,
            Material.BROWN_TERRACOTTA,
            Material.BLACK_TERRACOTTA,

            Material.WHITE_WOOL,
            Material.RED_WOOL,
            Material.BLUE_WOOL,
            Material.GREEN_WOOL,
            Material.YELLOW_WOOL,
            Material.ORANGE_WOOL,
            Material.MAGENTA_WOOL,
            Material.LIGHT_BLUE_WOOL,
            Material.LIME_WOOL,
            Material.PINK_WOOL,
            Material.GRAY_WOOL,
            Material.LIGHT_GRAY_WOOL,
            Material.CYAN_WOOL,
            Material.PURPLE_WOOL,
            Material.BROWN_WOOL,
            Material.BLACK_WOOL
        )
        
        if (!allowedBlocks.contains(event.block.type)) {
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

        if (event.block.y > game.arena.world.maxHeight - 10) {
            event.isCancelled = true
            MessageUtils.sendMessage(player, "§cНельзя строить так высоко!")
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
            MessageUtils.sendMessage(damager, "§cНельзя атаковать союзников!")
        }
    }
    
    @EventHandler
    fun onEntityDamage(event: EntityDamageEvent) {
        val entity = event.entity as? Player ?: return
        val game = GameManager.getGame(entity) ?: return

        if (game.arena.state == ArenaState.WAITING || game.arena.state == ArenaState.STARTING) {
            event.isCancelled = true
        }
        if (entity.gameMode == GameMode.SPECTATOR) {
            event.isCancelled = true
        }
    }
    
    @EventHandler
    fun onFoodLevelChange(event: FoodLevelChangeEvent) {
        val player = event.entity as? Player ?: return
        val game = GameManager.getGame(player)

        if (game == null) {
            event.isCancelled = true
            player.foodLevel = 20
            return
        }

        if (game.arena.state == ArenaState.WAITING || game.arena.state == ArenaState.STARTING) {
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