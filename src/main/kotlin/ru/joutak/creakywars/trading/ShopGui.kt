package ru.joutak.creakywars.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.joutak.creakywars.config.GameConfig
import ru.joutak.creakywars.game.Game
import ru.joutak.creakywars.trading.Trade
import ru.joutak.creakywars.utils.MessageUtils
import ru.joutak.creakywars.utils.PluginManager
import java.util.logging.Level

object ShopGui : Listener {
    private val openInventories = mutableMapOf<Player, Pair<Game, String>>()

    private val TRADE_ID_KEY = NamespacedKey(PluginManager.getPlugin(), "trade_id")
    private val CATEGORY_KEY = NamespacedKey(PluginManager.getPlugin(), "category_id")

    private val categories = mapOf(
        "blocks" to "§aБлоки",
        "swords" to "§cМечи",
        "armor" to "§9Броня",
        "tools" to "§eИнструменты",
        "special" to "§dОсобое"
    )

    private val categoryItems = mapOf(
        "blocks" to Triple(Material.WHITE_WOOL, "§aБлоки", 45),
        "swords" to Triple(Material.IRON_SWORD, "§cМечи", 46),
        "armor" to Triple(Material.IRON_CHESTPLATE, "§9Броня", 47),
        "tools" to Triple(Material.IRON_PICKAXE, "§eИнструменты", 48),
        "special" to Triple(Material.GOLDEN_APPLE, "§dОсобое", 49)
    )

    private val tradesByCategory: Map<String, List<Trade>> by lazy {
        GameConfig.trades.groupBy { it.category }
    }

    init {
        Bukkit.getPluginManager().registerEvents(this, PluginManager.getPlugin())
    }

    fun open(player: Player, game: Game) {
        val category = "blocks"
        val inventory = Bukkit.createInventory(null, 54, "§6§lМагазин - ${categories[category]}")

        openInventories[player] = Pair(game, category)
        updateInventoryItems(inventory, player, game, category)

        player.openInventory(inventory)
        PluginManager.getPlugin().logger.log(Level.INFO, "[ShopGui] Инвентарь открыт для ${player.name}, категория: $category")
    }

    private fun updateInventoryItems(inventory: Inventory, player: Player, game: Game, category: String) {
        inventory.clear()

        for ((catId, catData) in categoryItems) {
            val (icon, name, slot) = catData
            val item = ItemStack(icon)
            val meta = item.itemMeta!!
            meta.setDisplayName(name)
            meta.persistentDataContainer.set(CATEGORY_KEY, PersistentDataType.STRING, catId)
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)

            if (catId == category) {
                meta.lore = listOf("§a§lТекущая вкладка")
                meta.addEnchant(Enchantment.UNBREAKING, 1, true)
            } else {
                meta.lore = listOf("§7Нажмите для перехода")
            }
            item.itemMeta = meta
            inventory.setItem(slot, item)
        }

        val separator = ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
            itemMeta = itemMeta!!.apply { setDisplayName(" ") }
        }
        (36..44).plus(50..53).forEach { inventory.setItem(it, separator) }

        tradesByCategory[category]?.forEachIndexed { index, trade ->
            if (index < 36) inventory.setItem(index, createTradeItem(trade, game, player))
        }
    }

    private fun createTradeItem(trade: Trade, game: Game, player: Player): ItemStack {
        val team = game.getTeam(player)
        val item = if (team != null && shouldUseTeamColor(trade.result.type)) {
            convertToTeamColor(trade.result, team.woolColor)
        } else {
            trade.result.clone()
        }

        val meta = item.itemMeta ?: Bukkit.getItemFactory().getItemMeta(item.type)!!
        meta.persistentDataContainer.set(TRADE_ID_KEY, PersistentDataType.STRING, trade.id)

        if (trade.displayName?.contains("Откидывающая палка") == true) {
            meta.addEnchant(Enchantment.KNOCKBACK, 2, true)
        }

        meta.setDisplayName("§e${trade.displayName}")

        val lore = mutableListOf<String>().apply {
            add("")
            GameConfig.resourceTypes[trade.cost.first]?.let { resourceType ->
                val has = countResources(player, resourceType.material)
                val need = trade.cost.second
                val color = if (has >= need) "§a" else "§c"
                add("§7Цена: $color${need}x ${resourceType.displayName}")
            }
            if (team != null && shouldUseTeamColor(trade.result.type)) {
                add("§7Цвет: ${team.color}${team.name}")
            }
            if (meta.hasEnchants()) {
                add("")
                add("§dЗачарования:")
                meta.enchants.forEach { (enchant, level) ->
                    add("§7- ${getEnchantmentName(enchant)} $level")
                }
            }
            add("")
            add("§eНажмите, чтобы купить!")
        }
        meta.lore = lore
        item.itemMeta = meta
        return item
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val shopData = openInventories[player] ?: return

        event.isCancelled = true

        val clickedInventory = event.clickedInventory ?: return
        if (clickedInventory !== player.openInventory.topInventory) return

        val clickedItem = event.currentItem ?: return
        if (clickedItem.type.isAir) return

        val meta = clickedItem.itemMeta ?: return
        val (game, currentCategory) = shopData

        meta.persistentDataContainer.get(CATEGORY_KEY, PersistentDataType.STRING)?.let { newCategory ->
            if (newCategory != currentCategory) {
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1f, 1f)
                openInventories[player] = Pair(game, newCategory)
                updateInventoryItems(clickedInventory, player, game, newCategory)
            }
            return
        }

        meta.persistentDataContainer.get(TRADE_ID_KEY, PersistentDataType.STRING)?.let { tradeId ->
            val trade = GameConfig.trades.find { it.id == tradeId } ?: return
            val resourceType = GameConfig.resourceTypes[trade.cost.first] ?: return
            val requiredAmount = trade.cost.second

            if (countResources(player, resourceType.material) < requiredAmount) {
                MessageUtils.sendMessage(player, "§cНедостаточно ${resourceType.displayName}!")
                player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
                return
            }

            val removed = removeResources(player, resourceType.material, requiredAmount)

            if (!removed) {
                MessageUtils.sendMessage(player, "§cОшибка при списании ресурсов!")
                player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
                PluginManager.getPlugin().logger.log(Level.WARNING, "[ShopGui] Не удалось удалить ${requiredAmount}x ${resourceType.material} у ${player.name}")
                return
            }

            val team = game.getTeam(player)
            val resultItem = createPurchasedItem(trade, team)

            val playerData = game.getPlayerData(player)
            val loadout = playerData?.loadout

            when {
                isArmor(resultItem.type) && loadout != null -> {
                    val armorType = getArmorType(resultItem.type)

                    loadout.upgradeArmor(armorType, resultItem, silent = false)

                    if (armorType == "leggings") {
                        val bootsItem = createMatchingBoots(resultItem)
                        loadout.upgradeArmor("boots", bootsItem, silent = true)
                    }
                }
                isSword(resultItem.type) && loadout != null -> {
                    loadout.upgradeSword(resultItem)
                }
                else -> {
                    val leftover = player.inventory.addItem(resultItem)
                    if (leftover.isNotEmpty()) {
                        leftover.values.forEach {
                            player.world.dropItemNaturally(player.location, it)
                        }
                        MessageUtils.sendMessage(player, "§eНекоторые предметы выпали на землю!")
                    }
                }
            }

            MessageUtils.sendMessage(player, "§aВы купили §e${trade.displayName}§a за ${requiredAmount}x ${resourceType.displayName}!")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_YES, 1f, 1f)

            playerData?.resourcesCollected = (playerData?.resourcesCollected ?: 0) + requiredAmount

            updateInventoryItems(clickedInventory, player, game, currentCategory)
        }
    }

    private fun createPurchasedItem(trade: Trade, team: ru.joutak.creakywars.game.Team?): ItemStack {
        val baseItem = if (team != null && shouldUseTeamColor(trade.result.type)) {
            convertToTeamColor(trade.result.clone(), team.woolColor)
        } else {
            trade.result.clone()
        }

        if (isBlock(baseItem.type)) {
            return ItemStack(baseItem.type, baseItem.amount)
        }

        val tradeMeta = trade.result.itemMeta
        if (tradeMeta != null) {
            val meta = baseItem.itemMeta!!
            tradeMeta.enchants.forEach { (enchant, level) ->
                meta.addEnchant(enchant, level, true)
            }
            tradeMeta.itemFlags.forEach { meta.addItemFlags(it) }
            meta.isUnbreakable = tradeMeta.isUnbreakable

            baseItem.itemMeta = meta
        }

        return baseItem
    }

    private fun createMatchingBoots(leggings: ItemStack): ItemStack {
        val bootsType = when {
            leggings.type.name.startsWith("LEATHER_") -> Material.LEATHER_BOOTS
            leggings.type.name.startsWith("CHAINMAIL_") -> Material.CHAINMAIL_BOOTS
            leggings.type.name.startsWith("IRON_") -> Material.IRON_BOOTS
            leggings.type.name.startsWith("GOLDEN_") -> Material.GOLDEN_BOOTS
            leggings.type.name.startsWith("DIAMOND_") -> Material.DIAMOND_BOOTS
            leggings.type.name.startsWith("NETHERITE_") -> Material.NETHERITE_BOOTS
            else -> Material.LEATHER_BOOTS
        }

        val boots = ItemStack(bootsType)
        val leggingsMeta = leggings.itemMeta

        if (leggingsMeta != null) {
            val bootsMeta = boots.itemMeta!!

            leggingsMeta.enchants.forEach { (enchant, level) ->
                bootsMeta.addEnchant(enchant, level, true)
            }

            bootsMeta.isUnbreakable = leggingsMeta.isUnbreakable
            leggingsMeta.itemFlags.forEach { bootsMeta.addItemFlags(it) }

            boots.itemMeta = bootsMeta
        }

        return boots
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        if (openInventories.remove(event.player) != null) {
            PluginManager.getPlugin().logger.log(Level.INFO, "[ShopGui] Инвентарь закрыт для ${event.player.name}. Удален из списка.")
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        openInventories.remove(event.player)
    }

    private fun countResources(player: Player, material: Material): Int =
        player.inventory.contents.filterNotNull()
            .filter { it.type == material }
            .sumOf { it.amount }

    private fun removeResources(player: Player, material: Material, amount: Int): Boolean {
        var remaining = amount
        val inventory = player.inventory

        for (i in 0 until inventory.size) {
            val item = inventory.getItem(i)
            if (item != null && item.type == material) {
                val itemAmount = item.amount
                if (itemAmount >= remaining) {
                    item.amount = itemAmount - remaining
                    inventory.setItem(i, if (item.amount <= 0) null else item)
                    return true
                } else {
                    remaining -= itemAmount
                    inventory.setItem(i, null)
                }
            }
        }

        return remaining == 0
    }

    private fun isArmor(material: Material): Boolean =
        material.name.endsWith("_HELMET") ||
                material.name.endsWith("_CHESTPLATE") ||
                material.name.endsWith("_LEGGINGS") ||
                material.name.endsWith("_BOOTS")

    private fun getArmorType(material: Material): String = when {
        material.name.endsWith("_HELMET") -> "helmet"
        material.name.endsWith("_CHESTPLATE") -> "chestplate"
        material.name.endsWith("_LEGGINGS") -> "leggings"
        material.name.endsWith("_BOOTS") -> "boots"
        else -> "unknown"
    }

    private fun isSword(material: Material): Boolean =
        material.name.endsWith("_SWORD")

    private fun isBlock(material: Material): Boolean =
        material.isBlock && !isArmor(material) && !isSword(material)

    private fun shouldUseTeamColor(material: Material): Boolean =
        material.name.contains("_WOOL") ||
                material.name.contains("_TERRACOTTA") ||
                material.name.contains("_CONCRETE") ||
                material.name.contains("_BED")

    private fun convertToTeamColor(item: ItemStack, teamWoolColor: Material): ItemStack {
        val teamColorName = when (teamWoolColor) {
            Material.ORANGE_TERRACOTTA -> "ORANGE"
            Material.BLUE_TERRACOTTA -> "BLUE"
            Material.PINK_TERRACOTTA -> "PINK"
            Material.GREEN_TERRACOTTA -> "GREEN"
            else -> "WHITE"
        }

        val newItemName = item.type.name.replace("WHITE", teamColorName)

        return try {
            ItemStack(Material.valueOf(newItemName), item.amount)
        } catch (e: IllegalArgumentException) {
            item
        }
    }

    private fun getEnchantmentName(enchant: Enchantment): String =
        enchant.key.key.split('_')
            .joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
}