package ru.joutak.creakywars.upgrades

import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.ArmorStand
import org.bukkit.persistence.PersistentDataType
import ru.joutak.creakywars.game.Game
import ru.joutak.creakywars.utils.PluginManager
import java.util.UUID

object BrainStationManager {

    private val brainKey by lazy { NamespacedKey(PluginManager.getPlugin(), "cw_brain") }

    // worldName -> armorstand uuids
    private val spawned = mutableMapOf<String, MutableList<UUID>>()

    fun spawn(game: Game) {
        val world = game.arena.world
        val list = spawned.getOrPut(world.name) { mutableListOf() }
        if (list.isNotEmpty()) return

        game.arena.mapConfig.upgradeLocations.forEach { locDef ->
            val base = locDef.toLocation(world)
            val labelLoc = base.clone().add(0.5, 1.2, 0.5) // 1 block lower than before; upgrade block is 1-block tall

            val stand = world.spawn(labelLoc, ArmorStand::class.java) { asd ->
                asd.isVisible = false
                asd.setGravity(false)
                asd.isInvulnerable = true
                asd.isCustomNameVisible = true
                asd.customName = "§d§lМОЗГ"
                asd.isSmall = true
                asd.isMarker = true
                asd.persistentDataContainer.set(brainKey, PersistentDataType.BYTE, 1)
            }

            list.add(stand.uniqueId)
        }
    }

    fun remove(game: Game) {
        removeWorld(game.arena.world.name)
    }

    fun removeWorld(worldName: String) {
        val ids = spawned.remove(worldName) ?: return
        val world = PluginManager.getPlugin().server.getWorld(worldName) ?: return
        ids.forEach { id ->
            val e = world.getEntity(id)
            if (e is ArmorStand) {
                e.remove()
            } else {
                e?.remove()
            }
        }
    }

    fun isBrainStand(entity: org.bukkit.entity.Entity): Boolean {
        val pdc = entity.persistentDataContainer
        return pdc.has(brainKey, PersistentDataType.BYTE)
    }
}
