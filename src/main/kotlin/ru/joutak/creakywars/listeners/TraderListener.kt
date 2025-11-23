package ru.joutak.creakywars.listeners

import org.bukkit.Bukkit
import org.bukkit.entity.Illusioner
import org.bukkit.event.EventHandler
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import ru.joutak.creakywars.game.GameManager
import ru.joutak.creakywars.gui.ShopGui
import ru.joutak.creakywars.utils.PluginManager

@Suppress("DEPRECATION")
class TraderListener : Listener {

    @EventHandler
    fun onTraderDamage(event: EntityDamageEvent) {
        val entity = event.entity
        if (entity !is Illusioner) return

        val name = entity.customName ?: return
        if (!name.contains("§6§lТорговец кирпичами")) return

        event.isCancelled = true
        entity.health = entity.maxHealth
    }

    @EventHandler
    fun onTraderClick(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) {
            return
        }

        val player = event.player
        val entity = event.rightClicked

        if (entity !is Illusioner) return
        val name = entity.customName ?: return
        if (!name.contains("§6§lТорговец кирпичами")) return

        event.isCancelled = true

        val game = GameManager.getGame(player) ?: return

        Bukkit.getScheduler().runTask(PluginManager.getPlugin(), Runnable {
            ShopGui.open(player, game)
        })
    }
}