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
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.*
import org.bukkit.Bukkit
import ru.joutak.creakywars.ceremony.CeremonyController
import ru.joutak.creakywars.game.GameManager
import ru.joutak.creakywars.game.PlayerLoadout
import ru.joutak.creakywars.utils.MessageUtils
import ru.joutak.creakywars.utils.PluginManager
import ru.joutak.minigames.managers.MatchmakingManager

class PlayerListener : Listener {

    private fun isSwordItem(item: org.bukkit.inventory.ItemStack?): Boolean {
        if (item == null) return false
        if (item.type.isAir) return false
        return item.type.name.endsWith("_SWORD")
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryClickEnsureSword(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val game = GameManager.getGame(player) ?: return
        if (game.isSpectator(player.uniqueId)) return
        if (player.gameMode != GameMode.SURVIVAL) return

        val cursorSword = isSwordItem(event.cursor)
        val currentSword = isSwordItem(event.currentItem)
        val hotbarSword = if (event.hotbarButton >= 0) isSwordItem(player.inventory.getItem(event.hotbarButton)) else false
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