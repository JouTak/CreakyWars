package ru.joutak.creakywars.upgrades

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import ru.joutak.creakywars.config.GameConfig
import ru.joutak.creakywars.game.GameManager
import ru.joutak.creakywars.utils.MessageUtils
import ru.joutak.creakywars.utils.PluginManager
import org.bukkit.GameMode

@Suppress("DEPRECATION")
class UpgradeListener : Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return

        if (block.type == Material.STRUCTURE_BLOCK) {
            event.isCancelled = true

            val player = event.player
            val game = GameManager.getGame(player) ?: return
            val clickedLoc = block.location

            val isUpgradeBlock = game.arena.mapConfig.upgradeLocations.any { spawnLoc ->
                val target = spawnLoc.toLocation(game.arena.world)
                target.blockX == clickedLoc.blockX &&
                        target.blockY == clickedLoc.blockY &&
                        target.blockZ == clickedLoc.blockZ
            }

            if (isUpgradeBlock) {
                val baseTeamId = resolveUpgradeBaseTeamId(game, clickedLoc)
                if (baseTeamId != null) {
                    val team = game.getTeam(player)
                    if (team == null || team.id != baseTeamId) {
                        MessageUtils.sendMessage(player, "§cМОЗГ врос в дерево этой базы и не принимает врагов.")
                        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.7f)
                        return
                    }
                }

                Bukkit.getScheduler().runTask(PluginManager.getPlugin(), Runnable {
                    UpgradeGui.open(player, game)
                })
            }
        }
    }

    private fun resolveUpgradeBaseTeamId(game: ru.joutak.creakywars.game.Game, clickedLoc: org.bukkit.Location): Int? {
        // Bind an upgrade station to the closest team spawn. If it's too far from any base, treat as neutral.
        val world = game.arena.world
        var bestId: Int? = null
        var bestDist = Double.MAX_VALUE

        game.arena.mapConfig.teamSpawns.forEachIndexed { idx, spawnLoc ->
            val loc = spawnLoc.toLocation(world)
            val dx = (loc.blockX - clickedLoc.blockX).toDouble()
            val dz = (loc.blockZ - clickedLoc.blockZ).toDouble()
            val dist = dx * dx + dz * dz
            if (dist < bestDist) {
                bestDist = dist
                bestId = idx
            }
        }

        // 80 blocks from base is already suspicious; most bases are much closer.
        return if (bestId != null && bestDist <= 80.0 * 80.0) bestId else null
    }

    @EventHandler
    fun onMove(event: PlayerMoveEvent) {
        val player = event.player
        val game = GameManager.getGame(player) ?: return

        if (player.gameMode != GameMode.SURVIVAL) return

        if (event.from.blockX == event.to?.blockX &&
            event.from.blockZ == event.to?.blockZ &&
            event.from.blockY == event.to?.blockY) return

        val playerTeam = game.getTeam(player) ?: return
        val range = GameConfig.upgradeSettings["trap_range"] as? Int ?: 15
        val rangeSq = range * range

        game.teams.forEach { enemyTeam ->
            if (enemyTeam != playerTeam && enemyTeam.trapActive) {
                val spawnLoc = game.arena.mapConfig.teamSpawns.getOrNull(enemyTeam.id)?.toLocation(game.arena.world)

                if (spawnLoc != null && player.location.distanceSquared(spawnLoc) <= rangeSq) {
                    enemyTeam.trapActive = false

                    player.addPotionEffect(PotionEffect(PotionEffectType.BLINDNESS, 100, 0))
                    player.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 100, 1))
                    MessageUtils.sendMessage(player, "§cВы попали в ловушку!")

                    enemyTeam.getOnlinePlayers().forEach { member ->
                        member.sendTitle("§c§lЛОВУШКА СРАБОТАЛА!", "§eВраг на базе!", 10, 60, 20)
                        member.playSound(member.location, Sound.ENTITY_CREEPER_HURT, 1f, 0.5f)
                    }
                }
            }
        }
    }
}