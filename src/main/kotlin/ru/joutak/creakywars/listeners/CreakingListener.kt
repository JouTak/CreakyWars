package ru.joutak.creakywars.listeners

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Creaking
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import ru.joutak.creakywars.config.GameConfig
import ru.joutak.creakywars.game.GameManager
import ru.joutak.creakywars.utils.PluginManager

class CreakingListener : Listener {

    @EventHandler
    fun onCreakingDamage(event: EntityDamageByEntityEvent) {
        val creaking = event.damager as? Creaking ?: return

        val game = GameManager.getActiveGames().firstOrNull {
            it.arena.world == creaking.world
        } ?: return

        event.isCancelled = false
    }

    @EventHandler
    fun onCreakingChangeBlock(event: EntityChangeBlockEvent) {
        val creaking = event.entity as? Creaking ?: return

        val game = GameManager.getActiveGames().firstOrNull {
            it.arena.world == creaking.world
        } ?: return

        val block = event.block

        if (GameConfig.allowedBlocks.contains(block.type)) {
            event.isCancelled = false

            val breakTime = calculateBreakTime(block.type)

            Bukkit.getScheduler().runTaskLater(PluginManager.getPlugin(), Runnable {
                if (block.type != Material.AIR) {
                    block.type = Material.AIR
                }
            }, breakTime)
        } else {
            event.isCancelled = true
        }
    }

    private fun calculateBreakTime(material: Material): Long {
        val baseHardness = when {
            material == Material.OBSIDIAN -> 100L
            material == Material.END_STONE -> 60L
            material == Material.CLAY -> 40L
            material == Material.OAK_PLANKS -> 30L

            isTerracotta(material) -> 25L
            isWool(material) -> 10L

            else -> 20L
        }

        return (baseHardness / GameConfig.creakingBreakSpeed).toLong().coerceAtLeast(1L)
    }

    private fun isTerracotta(mat: Material): Boolean {
        return mat.name.endsWith("TERRACOTTA")
    }

    private fun isWool(mat: Material): Boolean {
        return mat.name.endsWith("WOOL")
    }
}