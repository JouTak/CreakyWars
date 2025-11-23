package ru.joutak.creakywars.listeners

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Creaking
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.scheduler.BukkitTask
import ru.joutak.creakywars.config.GameConfig
import ru.joutak.creakywars.game.Game
import ru.joutak.creakywars.game.GameManager
import ru.joutak.creakywars.arenas.ArenaState
import ru.joutak.creakywars.utils.PluginManager
import org.bukkit.entity.Player

class CreakingListener : Listener {

    private fun isTerracotta(mat: Material): Boolean {
        return mat.name.endsWith("TERRACOTTA")
    }

    private fun isWool(mat: Material): Boolean {
        return mat.name.endsWith("WOOL")
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

    companion object {
        private var creakingAITask: BukkitTask? = null

        fun init() {
            if (creakingAITask == null) {
                creakingAITask = Bukkit.getScheduler().runTaskTimer(PluginManager.getPlugin(), Runnable {
                    GameManager.getActiveGames().forEach { game ->
                        if (game.arena.state == ArenaState.IN_GAME) {
                            game.arena.world.entities
                                .filterIsInstance<Creaking>()
                                .forEach { creaking ->
                                    updateCreakingAI(creaking, game)
                                }
                        }
                    }
                }, 0L, 10L)
                PluginManager.getLogger().info("✓ Creaking AI Task started.")
            }
        }

        private fun updateCreakingAI(creaking: Creaking, game: Game) {
            val aggroRadius = GameConfig.creakingAggroRadius

            val targetPlayer = game.activePlayers
                .filter { it.world == creaking.world && it.location.distance(creaking.location) <= aggroRadius }
                .minByOrNull { it.location.distanceSquared(creaking.location) }

            if (targetPlayer != null) {
                creaking.target = targetPlayer

                val blockInFront = getBlockInFront(creaking)

                if (blockInFront != null && GameConfig.allowedBlocks.contains(blockInFront.type)) {

                    if (blockInFront.location.distanceSquared(creaking.location) < 4.0) {

                        val breakTime = CreakingListener().calculateBreakTime(blockInFront.type)

                        if (!creaking.hasMetadata("breaking")) {
                            creaking.setMetadata("breaking", org.bukkit.metadata.FixedMetadataValue(PluginManager.getPlugin(), true))

                            Bukkit.getScheduler().runTaskLater(PluginManager.getPlugin(), Runnable {
                                if (blockInFront.type != Material.AIR) {
                                    blockInFront.type = Material.AIR
                                    creaking.world.playSound(blockInFront.location, Sound.BLOCK_STONE_BREAK, 1.0f, 1.0f)
                                }
                                creaking.removeMetadata("breaking", PluginManager.getPlugin())
                            }, breakTime)
                        }
                    }
                }
            } else {
                creaking.target = null
            }
        }

        private fun getBlockInFront(creaking: Creaking): org.bukkit.block.Block? {
            val forwardVec = creaking.location.direction.setY(0).normalize()
            val blockLoc = creaking.location.add(forwardVec.multiply(1.0))

            val blockBelow = blockLoc.block
            val blockAbove = blockLoc.clone().add(0.0, 1.0, 0.0).block

            if (blockBelow.type.isSolid && GameConfig.allowedBlocks.contains(blockBelow.type)) {
                return blockBelow
            }
            if (blockAbove.type.isSolid && GameConfig.allowedBlocks.contains(blockAbove.type)) {
                return blockAbove
            }
            return null
        }
    }

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

        val block = event.block

        if (GameConfig.allowedBlocks.contains(block.type)) {
            event.isCancelled = true
        } else {
            event.isCancelled = true
        }
    }
}