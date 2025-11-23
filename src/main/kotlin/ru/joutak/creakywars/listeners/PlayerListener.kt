package ru.joutak.creakywars.listeners

import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.*
import ru.joutak.creakywars.game.GameManager
import ru.joutak.creakywars.queue.QueueManager
import ru.joutak.creakywars.utils.MessageUtils

@Suppress("DEPRECATION")
class PlayerListener : Listener {

    @Suppress("DEPRECATION")
    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        player.teleport(player.world.spawnLocation)
        player.gameMode = GameMode.ADVENTURE
        player.health = 20.0
        player.foodLevel = 20
        player.inventory.clear()

        event.joinMessage = "§e${player.name} §aприсоединился к серверу!"
    }

    @Suppress("DEPRECATION")
    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        QueueManager.removePlayer(player)

        val game = GameManager.getGame(player)
        if (game != null) {
            GameManager.removePlayerFromGame(player)
        }

        event.quitMessage = "§e${player.name} §cпокинул сервер!"
    }

    @EventHandler
    fun onPlayerDropItem(event: PlayerDropItemEvent) {
        val player = event.player
        val game = GameManager.getGame(player)

        if (game == null) {
            event.isCancelled = true
            return
        }

        val item = event.itemDrop.itemStack
        val type = item.type.name

        val isForbidden = type.endsWith("_SWORD") ||
                type.endsWith("_AXE") ||
                type.endsWith("_SHOVEL") ||
                type.endsWith("_HOE") ||
                type == "BOW" ||
                type == "CROSSBOW" ||
                type == "TRIDENT" ||
                type == "SHEARS" ||
                type == "MACE"

        if (isForbidden) {
            event.isCancelled = true
            MessageUtils.sendMessage(player, "§cВы не можете выбросить этот предмет!")
        }
    }

    @EventHandler
    fun onPlayerPickupArrow(event: PlayerPickupArrowEvent) {
        val player = event.player
        val game = GameManager.getGame(player)

        if (game == null) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.entity

        event.deathMessage = null

        val game = GameManager.getGame(player) ?: return

        val killer = player.killer

        game.handlePlayerDeath(player, killer)

        event.drops.clear()
        event.keepLevel = false
        event.droppedExp = 0
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        val game = GameManager.getGame(player) ?: return
        event.respawnLocation = player.location
    }
}