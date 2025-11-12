package ru.joutak.creakywars.core

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerRespawnEvent
import ru.joutak.creakywars.game.GameManager
import ru.joutak.creakywars.utils.PluginManager

class RespawnSystem : Listener {
    
    init {
        Bukkit.getPluginManager().registerEvents(this, PluginManager.getPlugin())
    }
    
    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.entity
        val game = GameManager.getGame(player) ?: return

        event.drops.clear()
        event.droppedExp = 0

        val killer = player.killer

        game.handlePlayerDeath(player, killer)
    }
    
    @EventHandler
    fun onPlayerRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        val game = GameManager.getGame(player) ?: return
    }
}