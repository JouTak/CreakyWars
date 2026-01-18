package ru.joutak.creakywars.ceremony

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import ru.joutak.creakywars.utils.PluginManager
import java.util.UUID

object CeremonyController : Listener {
    private data class PlayerRegion(val worldName: String, val bounds: Bounds)

    private val regions = mutableMapOf<UUID, PlayerRegion>()
    private val teleporting = mutableSetOf<UUID>()

    fun setPlayerRegion(player: Player, worldName: String, bounds: Bounds) {
        regions[player.uniqueId] = PlayerRegion(worldName, bounds)
    }

    fun clearPlayer(player: Player) {
        regions.remove(player.uniqueId)
        teleporting.remove(player.uniqueId)
    }

    fun clearWorld(worldName: String) {
        val it = regions.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.value.worldName == worldName) {
                it.remove()
            }
        }
    }

    fun getActiveWorldNames(): Set<String> {
        return regions.values.map { it.worldName }.toSet()
    }

    private fun handleOutOfBounds(player: Player, safe: org.bukkit.Location) {
        val uuid = player.uniqueId
        if (teleporting.contains(uuid)) return

        teleporting.add(uuid)
        player.teleport(safe, PlayerTeleportEvent.TeleportCause.PLUGIN)
        val plugin = PluginManager.getPlugin()
        plugin.server.scheduler.runTask(plugin, Runnable {
            teleporting.remove(uuid)
        })
    }

    @EventHandler(ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val player = event.player
        val reg = regions[player.uniqueId] ?: return
        if (player.world.name != reg.worldName) return

        val to = event.to ?: return
        if (reg.bounds.contains(to)) return

        val from = event.from
        val safe = if (reg.bounds.contains(from)) from else reg.bounds.clamp(to)
        handleOutOfBounds(player, safe)
    }

    @EventHandler(ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        val player = event.player
        val reg = regions[player.uniqueId] ?: return
        if (player.world.name != reg.worldName) return

        // Allow our own teleports.
        if (event.cause == PlayerTeleportEvent.TeleportCause.PLUGIN) return

        val to = event.to ?: return
        if (!reg.bounds.contains(to)) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        clearPlayer(event.player)
    }
}
