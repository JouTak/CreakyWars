package ru.joutak.creakywars.listeners

import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.*
import ru.joutak.creakywars.game.GameManager
import ru.joutak.creakywars.game.PlayerLoadout
import ru.joutak.creakywars.utils.MessageUtils
import ru.joutak.minigames.managers.MatchmakingManager

class PlayerListener : Listener {

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

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player

        try {
            MatchmakingManager.removePlayer(player)
        } catch (_: Exception) {
        }

        val game = GameManager.getGame(player)
        if (game != null) {
            game.removePlayer(player)
        } else {
            // Admin spectate mode: restore and clean up.
            val spectateGame = GameManager.getSpectatingGame(player)
            if (spectateGame != null) {
                try {
                    spectateGame.removeSpectator(player, silent = true, forceLobby = true)
                } catch (_: Exception) {
                }
            }
        }

        event.quitMessage = "§e${player.name} §cпокинул сервер!"
    }

    @EventHandler
    fun onPlayerKick(event: PlayerKickEvent) {
        val player = event.player

        try {
            MatchmakingManager.removePlayer(player)
        } catch (_: Exception) {
        }

        val game = GameManager.getGame(player)
        if (game != null) {
            game.removePlayer(player)
        } else {
            val spectateGame = GameManager.getSpectatingGame(player)
            if (spectateGame != null) {
                try {
                    spectateGame.removeSpectator(player, silent = true, forceLobby = true)
                } catch (_: Exception) {
                }
            }
        }
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
                type.endsWith("_PICKAXE") ||
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

    

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onSwapElytraUse(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return

        val player = event.player
        val game = GameManager.getGame(player) ?: return
        if (game.isSpectator(player.uniqueId)) return

        val item = event.item ?: return
        if (!PlayerLoadout.isSwapElytra(item)) return

        event.isCancelled = true

        val loadout = game.getPlayerData(player)?.loadout ?: return
        loadout.equipElytraFromHand(EquipmentSlot.HAND, silent = false)
        player.updateInventory()
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onSwapElytraInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val game = GameManager.getGame(player) ?: return
        if (game.isSpectator(player.uniqueId)) return

        val clickedInv = event.clickedInventory ?: return
        if (clickedInv != player.inventory) return

        val item = event.currentItem ?: return
        if (!PlayerLoadout.isSwapElytra(item)) return

        // Right-clicking an elytra in the inventory should equip it even if the current chestplate has binding curse.
        if (event.click != ClickType.RIGHT) return

        val loadout = game.getPlayerData(player)?.loadout ?: return
        if (player.inventory.chestplate?.type == Material.ELYTRA) return

        event.isCancelled = true

        val slot = event.slot
        val stack = player.inventory.getItem(slot) ?: return
        if (stack.type != Material.ELYTRA || stack.amount <= 0) return

        val one = stack.clone()
        one.amount = 1

        if (stack.amount <= 1) {
            player.inventory.setItem(slot, null)
        } else {
            stack.amount = stack.amount - 1
            player.inventory.setItem(slot, stack)
        }

        loadout.equipElytra(one, silent = false)
        player.updateInventory()
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
        GameManager.getGame(player) ?: return
        event.respawnLocation = player.location
    }
}