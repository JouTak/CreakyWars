package ru.joutak.creakywars.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import ru.joutak.creakywars.game.Team
import ru.joutak.creakywars.utils.MessageUtils
import ru.joutak.creakywars.utils.PluginManager

object TeamSelectionGui : Listener {
    private val openInventories = mutableMapOf<Player, TeamSelectionData>()
    
    init {
        Bukkit.getPluginManager().registerEvents(this, PluginManager.getPlugin())
    }
    
    data class TeamSelectionData(
        val inventory: Inventory,
        val teams: List<Team>,
        val callback: (Player, Team) -> Unit
    )

    @Suppress("DEPRECATION")
    fun open(player: Player, teams: List<Team>, callback: (Player, Team) -> Unit) {
        val inventory = Bukkit.createInventory(null, 27, "§6§lВыбор команды")
        
        teams.forEachIndexed { index, team ->
            val slot = 10 + index
            if (slot >= 27) return@forEachIndexed
            
            val item = createTeamItem(team)
            inventory.setItem(slot, item)
        }
        
        openInventories[player] = TeamSelectionData(inventory, teams, callback)
        player.openInventory(inventory)
    }
    
    private fun createTeamItem(team: Team): ItemStack {
        val item = ItemStack(team.woolColor)
        val meta = item.itemMeta
        
        meta?.setDisplayName("${team.color}§l${team.name}")
        
        val lore = mutableListOf<String>()
        lore.add("§7Игроков: §f${team.players.size}")
        lore.add("")
        lore.add("§eНажмите, чтобы выбрать!")
        
        meta?.lore = lore
        item.itemMeta = meta
        
        return item
    }
    
    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val clickedInventory = event.clickedInventory ?: return
        
        val data = openInventories[player] ?: return
        
        if (clickedInventory != data.inventory) return
        
        event.isCancelled = true
        
        val slot = event.slot
        val teamIndex = when (slot) {
            10 -> 0
            11 -> 1
            12 -> 2
            13 -> 3
            else -> return
        }
        
        if (teamIndex >= data.teams.size) return
        
        val selectedTeam = data.teams[teamIndex]

        data.callback(player, selectedTeam)
        
        MessageUtils.sendMessage(player, "§aВы выбрали команду ${selectedTeam.color}${selectedTeam.name}§a!")
        player.closeInventory()
    }
    
    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        openInventories.remove(player)
    }
}