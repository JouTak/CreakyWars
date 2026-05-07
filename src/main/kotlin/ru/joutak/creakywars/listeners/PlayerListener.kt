package ru.joutak.creakywars.listeners

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.*
import org.bukkit.inventory.EquipmentSlot
import ru.joutak.creakywars.ceremony.CeremonyController
import ru.joutak.creakywars.game.GameManager
import ru.joutak.creakywars.game.PlayerLoadout
import ru.joutak.creakywars.utils.MessageUtils
import ru.joutak.creakywars.utils.PluginManager
import ru.joutak.creakywars.utils.UseSuppressor
import ru.joutak.minigames.managers.MatchmakingManager

class PlayerListener : Listener {

    private fun isSwordItem(item: org.bukkit.inventory.ItemStack?): Boolean {
        if (item == null) return false
        if (item.type.isAir) return false
        return item.type.name.endsWith("_SWORD")
    }

    private fun isProtectedLoadoutItem(item: org.bukkit.inventory.ItemStack?): Boolean {
        if (item == null) return false
        if (item.type.isAir) return false

        val t = item.type
        val name = t.name
        val isToolOrWeapon = name.endsWith("_SWORD")
        if (!isToolOrWeapon) return false
        val meta = item.itemMeta ?: return false
        return meta.isUnbreakable
    }

    private fun scheduleEnsureWoodenSword(player: Player) {
        Bukkit.getScheduler().runTask(PluginManager.getPlugin(), Runnable {
            if (!player.isOnline) return@Runnable
            val game = GameManager.getGame(player) ?: return@Runnable
            if (game.isSpectator(player.uniqueId)) return@Runnable
            game.getPlayerData(player)?.loadout?.ensureHasSwordInInventory()
        })
    }

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

        UseSuppressor.clear(player.uniqueId)

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

        UseSuppressor.clear(player.uniqueId)

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

        val isForbidden = type.endsWith("_SWORD")

        if (isForbidden) {
            event.isCancelled = true
            MessageUtils.sendMessage(player, "§cВы не можете выбросить этот предмет!")
        }
    }


    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onSuppressItemUseWhileTraderClick(event: PlayerInteractEvent) {
        val player = event.player
        if (!UseSuppressor.isSuppressed(player.uniqueId)) return

        // Suppress "use" actions on the same click that opens the trader GUI.
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return

        event.isCancelled = true
        // Deny the item use explicitly, otherwise Paper may still trigger "use" items.
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY)
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY)
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInventoryClickPreventStoringBoundItems(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val game = GameManager.getGame(player) ?: return
        if (game.isSpectator(player.uniqueId)) return
        if (player.gameMode != GameMode.SURVIVAL) return

        val topSize = event.view.topInventory.size
        val raw = event.rawSlot

        // Placing a bound item into the top inventory (chest, etc.) via cursor click or number-key swap.
        if (raw >= 0 && raw < topSize) {
            if (isProtectedLoadoutItem(event.cursor)) {
                event.isCancelled = true
                player.updateInventory()
                return
            }

            if (event.hotbarButton >= 0) {
                val hotbar = player.inventory.getItem(event.hotbarButton)
                if (isProtectedLoadoutItem(hotbar)) {
                    event.isCancelled = true
                    player.updateInventory()
                    return
                }
            }
        }

        // Shift-click move from player inventory to the top inventory.
        val clickedInv = event.clickedInventory ?: return
        if (clickedInv == player.inventory) {
            val moving = event.isShiftClick || event.action == InventoryAction.MOVE_TO_OTHER_INVENTORY
            if (moving && isProtectedLoadoutItem(event.currentItem)) {
                event.isCancelled = true
                player.updateInventory()
                return
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInventoryDragPreventStoringBoundItems(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        val game = GameManager.getGame(player) ?: return
        if (game.isSpectator(player.uniqueId)) return
        if (player.gameMode != GameMode.SURVIVAL) return

        val cursor = event.oldCursor
        if (!isProtectedLoadoutItem(cursor)) return

        val topSize = event.view.topInventory.size
        if (event.rawSlots.any { it < topSize }) {
            event.isCancelled = true
            player.updateInventory()
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryClickEnsureSword(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val game = GameManager.getGame(player) ?: return
        if (game.isSpectator(player.uniqueId)) return
        if (player.gameMode != GameMode.SURVIVAL) return

        val cursorSword = isSwordItem(event.cursor)
        val currentSword = isSwordItem(event.currentItem)
        val hotbarSword =
            if (event.hotbarButton >= 0) isSwordItem(player.inventory.getItem(event.hotbarButton)) else false
        if (!cursorSword && !currentSword && !hotbarSword) return

        scheduleEnsureWoodenSword(player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryDragEnsureSword(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        val game = GameManager.getGame(player) ?: return
        if (game.isSpectator(player.uniqueId)) return
        if (player.gameMode != GameMode.SURVIVAL) return

        val affectsSword = isSwordItem(event.oldCursor) || event.newItems.values.any { isSwordItem(it) }
        if (!affectsSword) return

        scheduleEnsureWoodenSword(player)
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

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onAdminSpectateGamemodeChange(event: PlayerGameModeChangeEvent) {
        val player = event.player
        val spectateGame = GameManager.getSpectatingGame(player) ?: return
        if (!spectateGame.isSpectator(player.uniqueId)) return

        // Some world-management plugins (e.g. Multiverse) may force a world-specific gamemode.
        // Admin spectators must remain in SPECTATOR.
        if (event.newGameMode != GameMode.SPECTATOR) {
            event.isCancelled = true
            Bukkit.getScheduler().runTask(PluginManager.getPlugin(), Runnable {
                val p = Bukkit.getPlayer(player.uniqueId) ?: return@Runnable
                val g = GameManager.getSpectatingGame(p) ?: return@Runnable
                if (g.isSpectator(p.uniqueId) && p.gameMode != GameMode.SPECTATOR) {
                    p.gameMode = GameMode.SPECTATOR
                }
                try {
                    p.isCollidable = false
                } catch (_: Exception) {
                }
            })
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onAdminSpectateWorldChanged(event: PlayerChangedWorldEvent) {
        val player = event.player
        val spectateGame = GameManager.getSpectatingGame(player) ?: return
        if (!spectateGame.isSpectator(player.uniqueId)) return

        // Re-apply spectator after world change to override any world-enforced GM.
        Bukkit.getScheduler().runTask(PluginManager.getPlugin(), Runnable {
            val p = Bukkit.getPlayer(player.uniqueId) ?: return@Runnable
            val g = GameManager.getSpectatingGame(p) ?: return@Runnable
            if (!g.isSpectator(p.uniqueId)) return@Runnable
            try {
                if (p.gameMode != GameMode.SPECTATOR) p.gameMode = GameMode.SPECTATOR
                p.isCollidable = false
            } catch (_: Exception) {
            }
        })
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSpectatorCrossWorldTeleport(event: PlayerTeleportEvent) {
        val player = event.player
        if (player.gameMode != GameMode.SPECTATOR) return

        val game = GameManager.getGame(player) ?: GameManager.getSpectatingGame(player) ?: return

        val to = event.to ?: return
        val toWorld = to.world?.name ?: return

        val allowedWorlds = hashSetOf(game.arena.world.name)
        CeremonyController.getPlayerWorldName(player.uniqueId)?.let { allowedWorlds.add(it) }

        if (allowedWorlds.contains(toWorld)) return

        // Only allow cross-world teleports that are initiated by the game itself.
        if (event.cause == PlayerTeleportEvent.TeleportCause.PLUGIN) return

        event.isCancelled = true
    }
}