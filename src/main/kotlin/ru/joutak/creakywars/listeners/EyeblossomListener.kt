package ru.joutak.creakywars.listeners

import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import ru.joutak.creakywars.config.GameConfig
import ru.joutak.creakywars.game.GameManager
import ru.joutak.creakywars.utils.MessageUtils

class EyeblossomListener : Listener {

    @EventHandler
    fun onEyeblossomClick(event: PlayerInteractEvent) {
        if (event.action != Action.LEFT_CLICK_BLOCK) return

        val block = event.clickedBlock ?: return
        val player = event.player

        if (block.type != Material.OPEN_EYEBLOSSOM) return

        val game = GameManager.getGame(player) ?: return
        val cycle = game.getDayNightCycle()

        if (!cycle.isNightTime()) {
            MessageUtils.sendMessage(player, "§cЦветок можно собрать только ночью!")
            event.isCancelled = true
            return
        }

        val eyeblossomLoc = cycle.getEyeblossomLocation(block)
        if (eyeblossomLoc == null) {
            event.isCancelled = true
            return
        }

        val harvested = cycle.tryHarvestEyeblossom(eyeblossomLoc)
        if (harvested) {
            val resourceType = GameConfig.resourceTypes["eyeblossom"]
            if (resourceType != null) {
                val item = resourceType.createItemStack(1)
                val leftover = player.inventory.addItem(item)

                if (leftover.isNotEmpty()) {
                    leftover.values.forEach { leftoverItem ->
                        player.world.dropItemNaturally(player.location, leftoverItem)
                    }
                    MessageUtils.sendMessage(player, "§d§l+1 ${resourceType.displayName} §7(выпал на землю)")
                } else {
                    MessageUtils.sendMessage(player, "§d§l+1 ${resourceType.displayName}")
                }

                player.playSound(player.location, org.bukkit.Sound.BLOCK_AMETHYST_BLOCK_BREAK, 1.0f, 1.5f)

                val playerData = game.getPlayerData(player)
                playerData?.resourcesCollected = (playerData?.resourcesCollected ?: 0) + 1
            }
        }

        event.isCancelled = true
    }
}