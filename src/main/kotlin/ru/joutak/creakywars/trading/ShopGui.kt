@file:Suppress("DEPRECATION")

package ru.joutak.creakywars.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.joutak.creakywars.config.GameConfig
import ru.joutak.creakywars.game.Game
import ru.joutak.creakywars.game.PlayerLoadout
import ru.joutak.creakywars.game.Team
import ru.joutak.creakywars.trading.ShopCategory
import ru.joutak.creakywars.trading.Trade
import ru.joutak.creakywars.utils.MessageUtils
import ru.joutak.creakywars.utils.PluginManager
import java.util.*
import java.util.logging.Level

object ShopGui : Listener {
    private val openInventories = mutableMapOf<UUID, Pair<Game, String>>()
    private val switchingCategory = mutableSetOf<UUID>()

    private val TRADE_ID_KEY = NamespacedKey(PluginManager.getPlugin(), "trade_id")
    private val CATEGORY_KEY = NamespacedKey(PluginManager.getPlugin(), "category_id")

    private const val MENU_TITLE_PREFIX = "§6§lМагазин"

    private val categories: List<ShopCategory>
        get() = GameConfig.shopCategories.values.sortedBy { it.slot }

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

    fun init() {}

    private fun openWithCategory(player: Player, game: Game, category: String) {
        val categoryData = GameConfig.shopCategories[category]
        val title = "$MENU_TITLE_PREFIX - ${categoryData?.displayName ?: "§7?"}"
        val inventory = Bukkit.createInventory(null, 54, title)

        updateInventoryItems(inventory, player, game, category)

        val uuid = player.uniqueId
        switchingCategory.add(uuid)
        openInventories[uuid] = Pair(game, category)
        player.openInventory(inventory)

        // NOTE: openInventory closes previous view -> InventoryCloseEvent will fire.
        // Ensure mapping survives switching.
        Bukkit.getScheduler().runTask(PluginManager.getPlugin(), Runnable {
            switchingCategory.remove(uuid)
            openInventories[uuid] = Pair(game, category)
        })
    }

    fun open(player: Player, game: Game) {
        val firstCatefory = categories.firstOrNull()?.id ?: return
        openWithCategory(player, game, firstCatefory)
    }

    private fun updateInventoryItems(inventory: Inventory, player: Player, game: Game, category: String) {
        inventory.clear()

        for (categoryData in categories) {
            val item = ItemStack(categoryData.icon)
            val meta = item.itemMeta!!
            meta.setDisplayName(categoryData.displayName)
            meta.persistentDataContainer.set(CATEGORY_KEY, PersistentDataType.STRING, categoryData.id)

            if (categoryData.id == category) {
                meta.lore = listOf("§a§lТекущая вкладка")
                meta.addEnchant(Enchantment.UNBREAKING, 1, true)
            } else {
                meta.lore = listOf("§7Нажмите для перехода")
            }
            item.itemMeta = meta
            inventory.setItem(categoryData.slot, item)
        }

        val separator = ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
            itemMeta = itemMeta!!.apply { setDisplayName(" ") }
        }
        val categorySlots = categories.map {it.slot }.toSet()

        for (slot in 36..53) {
            if (slot !in categorySlots) {
                inventory.setItem(slot, separator)
            }
        }

        val loadout = game.getPlayerData(player)?.loadout
        var slotIndex = 0

        tradesByCategory[category]?.forEach { trade ->
            val tradeResultType = trade.result.type
            var showTrade = true
            if (loadout != null) {
                when {
                    tradeResultType == Material.ELYTRA -> {
                        // Hide if already equipped/in inventory? We only prevent duplicate purchases via click handler,
                        // but also avoid cluttering the shop for players who already have elytra equipped.
                        showTrade = player.inventory.chestplate?.type != Material.ELYTRA
                    }

                    isArmor(tradeResultType) -> {
                        val armorType = getArmorType(tradeResultType)
                        val currentItem = loadout.getStoredArmor(armorType)
                        val currentTier = currentItem?.type?.let { getArmorMaterialTier(it) } ?: 0
                        val newTier = getArmorMaterialTier(tradeResultType)

                        showTrade = newTier > currentTier
                    }

                    isSword(tradeResultType) -> {
                        val newTier = getToolMaterialTier(tradeResultType)
                        val currentTier = getCurrentSwordTier(player)
                        showTrade = newTier > currentTier
                    }
                }
            }

            if (showTrade && slotIndex < 36) {
                inventory.setItem(slotIndex, createTradeItem(trade, game, player))
                slotIndex++
            }
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

        if (trade.displayName.contains("Откидывающая палка")) {
            meta.addEnchant(Enchantment.KNOCKBACK, 2, true)
        }

        meta.setDisplayName("§e${trade.displayName}")

        val tradeResultType = trade.result.type
        val resourceType = GameConfig.resourceTypes[trade.cost.first]!!
        val requiredAmount = trade.cost.second
        val hasResources = countResources(player, resourceType.material) >= requiredAmount

        val lore = mutableListOf<String>().apply {
            add("")
            val color = if (hasResources) "§a" else "§c"
            add("§7Цена: $color${requiredAmount}x ${resourceType.displayName}")

            if (tradeResultType == Material.ELYTRA) {
                add("§cОсторожно: заменяют нагрудник")
            }

            if (team != null && shouldUseTeamColor(tradeResultType)) {
                add("§7Цвет: ${team.color}${team.name}")
            }

            val allEnchants = mutableMapOf<Enchantment, Int>()
            trade.result.itemMeta?.enchants?.forEach { (enchant, level) ->
                allEnchants[enchant] = level
            }

            val isArmorTrade = isArmor(tradeResultType)
            val isToolTrade = isTool(tradeResultType)
            val isSwordTrade = isSword(tradeResultType)

            if (team != null) {
                if (isArmorTrade && team.protectionLevel > 0) {
                    allEnchants[Enchantment.PROTECTION] = team.protectionLevel
                }
                if (isSwordTrade && team.sharpnessLevel > 0) {
                    allEnchants[Enchantment.SHARPNESS] = team.sharpnessLevel
                }
                if (isToolTrade && team.efficiencyLevel > 0) {
                    allEnchants[Enchantment.EFFICIENCY] = team.efficiencyLevel
                }
            }

            if (allEnchants.isNotEmpty()) {
                add("")
                add("§dЗачарования:")
                allEnchants.forEach { (enchant, level) ->
                    add("§7- ${getEnchantmentName(enchant)} $level")
                }
            }

            add("")
            when {
                !hasResources -> add("§cНедостаточно ресурсов!")
                else -> add("§eНажмите, чтобы купить!")
            }
        }
        meta.lore = lore
        item.itemMeta = meta
        return item
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (event.view.title.startsWith(MENU_TITLE_PREFIX)) {
            if (event.rawSlots.any { it < event.view.topInventory.size }) {
                event.isCancelled = true
            }
        }
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return

        if (event.view.title.startsWith(MENU_TITLE_PREFIX)) {
            if (event.action == InventoryAction.COLLECT_TO_CURSOR) {
                event.isCancelled = true
                return
            }

            if (event.clickedInventory === event.view.topInventory) {
                event.isCancelled = true
            } else if (event.isShiftClick && event.clickedInventory === event.view.bottomInventory) {
                event.isCancelled = true
            }
        } else {
            return
        }

        val shopData = openInventories[player.uniqueId] ?: return
        val clickedInventory = event.clickedInventory ?: return
        val clickedItem = event.currentItem ?: return
        if (clickedItem.type.isAir) return

        if (clickedInventory !== event.view.topInventory) return

        val meta = clickedItem.itemMeta ?: return
        val (game, currentCategory) = shopData

        meta.persistentDataContainer.get(CATEGORY_KEY, PersistentDataType.STRING)?.let { newCategory ->
            if (newCategory != currentCategory) {
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1f, 1f)
                openWithCategory(player, game, newCategory)
            }
            return
        }

        meta.persistentDataContainer.get(TRADE_ID_KEY, PersistentDataType.STRING)?.let { tradeId ->
            val trade = GameConfig.trades.find { it.id == tradeId } ?: return
            val resourceType = GameConfig.resourceTypes[trade.cost.first] ?: return
            val requiredAmount = trade.cost.second
            val team = game.getTeam(player)
            val resultItem = createPurchasedItem(trade, team)
            val playerData = game.getPlayerData(player)
            val loadout = playerData?.loadout
            val tradeResultType = trade.result.type

            if (tradeResultType == Material.ELYTRA && player.inventory.chestplate?.type == Material.ELYTRA) {
                MessageUtils.sendMessage(player, "§cУ вас уже экипированы элитры!")
                player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
                updateInventoryItems(clickedInventory, player, game, currentCategory)
                return
            }

            val isArmorTrade = isArmor(tradeResultType)
            val isSwordTrade = isSword(tradeResultType)

            if (loadout != null) {
                val currentTier = when {
                    isArmorTrade -> loadout.getStoredArmor(getArmorType(tradeResultType))?.type?.let {
                        getArmorMaterialTier(
                            it
                        )
                    } ?: 0

                    isSwordTrade -> getCurrentSwordTier(player)
                    else -> 0
                }
                val newTier = when {
                    isArmorTrade -> getArmorMaterialTier(tradeResultType)
                    isSwordTrade -> getToolMaterialTier(tradeResultType)
                    else -> 0
                }

                if (newTier <= currentTier && (isArmorTrade || isSwordTrade)) {
                    MessageUtils.sendMessage(player, "§cУ вас уже есть предмет такого же или лучшего уровня!")
                    player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
                    updateInventoryItems(clickedInventory, player, game, currentCategory)
                    return
                }
            }


            if (countResources(player, resourceType.material) < requiredAmount) {
                MessageUtils.sendMessage(player, "§cНедостаточно ${resourceType.displayName}!")
                player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
                return
            }

            val removed = removeResources(player, resourceType.material, requiredAmount)

            if (!removed) {
                MessageUtils.sendMessage(player, "§cОшибка при списании ресурсов!")
                player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
                PluginManager.getPlugin().logger.log(
                    Level.WARNING,
                    "[ShopGui] Не удалось удалить ${requiredAmount}x ${resourceType.material} у ${player.name}"
                )
                return
            }

            when {
                tradeResultType == Material.ELYTRA && loadout != null -> {
                    loadout.equipElytra(resultItem, silent = false)
                }

                isArmorTrade && loadout != null -> {
                    val armorType = getArmorType(resultItem.type)

                    loadout.upgradeArmor(armorType, resultItem, silent = false)

                    if (armorType == "leggings") {
                        val bootsItem = createMatchingBoots(resultItem)
                        loadout.upgradeArmor("boots", bootsItem, silent = true)
                    }
                }

                isSwordTrade && loadout != null -> {
                    loadout.upgradeSword(resultItem)
                }

                tradeResultType == Material.ELYTRA -> {
                    // Fallback for cases where loadout is not available (shouldn't normally happen in a game).
                    val oldChest = player.inventory.chestplate
                    player.inventory.chestplate = resultItem
                    if (oldChest != null && !oldChest.type.isAir) {
                        val leftover = player.inventory.addItem(oldChest)
                        if (leftover.isNotEmpty()) {
                            leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
                        }
                    }
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

            MessageUtils.sendMessage(
                player,
                "§aВы купили §e${trade.displayName}§a за ${requiredAmount}x ${resourceType.displayName}!"
            )
            player.playSound(player.location, Sound.ENTITY_VILLAGER_YES, 1f, 1f)

            playerData?.resourcesCollected = (playerData?.resourcesCollected ?: 0) + requiredAmount

            updateInventoryItems(clickedInventory, player, game, currentCategory)
        }
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val uuid = event.player.uniqueId
        if (switchingCategory.contains(uuid)) return
        openInventories.remove(uuid)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        switchingCategory.remove(uuid)
        openInventories.remove(uuid)
    }

    private fun createPurchasedItem(trade: Trade, team: Team?): ItemStack {
        val item = if (team != null && shouldUseTeamColor(trade.result.type)) {
            convertToTeamColor(trade.result.clone(), team.woolColor)
        } else {
            trade.result.clone()
        }

        val isToolOrWeaponOrArmor = when {
            item.type.name.endsWith("_SWORD") -> true
            item.type.name.endsWith("_PICKAXE") -> true
            item.type.name.endsWith("_AXE") -> true
            item.type.name.endsWith("_SHOVEL") -> true
            item.type.name.endsWith("_HOE") -> true
            item.type.name.endsWith("BOW") -> true
            item.type.name.endsWith("CROSSBOW") -> true
            item.type.name.endsWith("TRIDENT") -> true
            isArmor(item.type) -> true
            item.type == Material.SHEARS -> true
            else -> false
        }

        if (isToolOrWeaponOrArmor) {
            val meta = item.itemMeta!!

            trade.result.itemMeta?.enchants?.forEach { (ench, lvl) ->
                meta.addEnchant(ench, lvl, true)
            }
            trade.result.itemMeta?.itemFlags?.forEach { meta.addItemFlags(it) }

            if (team != null) {
                if (isArmor(item.type) && team.protectionLevel > 0) {
                    meta.addEnchant(Enchantment.PROTECTION, team.protectionLevel, true)
                }
                if (isSword(item.type) && team.sharpnessLevel > 0) {
                    meta.addEnchant(Enchantment.SHARPNESS, team.sharpnessLevel, true)
                }
                if (isTool(item.type) && team.efficiencyLevel > 0) {
                    meta.addEnchant(Enchantment.EFFICIENCY, team.efficiencyLevel, true)
                }
            }

            meta.isUnbreakable = true

            if (isArmor(item.type)) {
                meta.addEnchant(Enchantment.BINDING_CURSE, 1, true)
                @Suppress("DEPRECATION")
                meta.setDisplayName("${team?.color ?: "§7"}Не снимаемая броня")
            }

            item.itemMeta = meta
        } else {
            trade.result.itemMeta?.let { originalMeta ->
                val meta = item.itemMeta!!
                originalMeta.enchants.forEach { (e, l) -> meta.addEnchant(e, l, true) }
                originalMeta.itemFlags.forEach { meta.addItemFlags(it) }
                item.itemMeta = meta
            }
        }

        if (item.type == Material.ELYTRA) {
            PlayerLoadout.markSwapElytra(item)
            val meta = item.itemMeta
            if (meta != null) {
                @Suppress("DEPRECATION")
                meta.setDisplayName("§e${trade.displayName}")

                val lore = (meta.lore ?: emptyList()).toMutableList()
                if (lore.none { it.contains("заменяют нагрудник") }) {
                    lore.add("§cОсторожно: заменяют нагрудник")
                }
                lore.add("§7ПКМ: надеть (снимет кирасу)")
                lore.add("§7Чтобы снять — надень кирасу обратно")
                meta.lore = lore
                item.itemMeta = meta
            }
        }

        return item
    }

    private fun createMatchingBoots(leggings: ItemStack): ItemStack {
        val bootsType = when {
            leggings.type.name.startsWith("LEATHER_") -> Material.LEATHER_BOOTS
            leggings.type.name.startsWith("COPPER_") -> materialByNameOrNull("COPPER_BOOTS") ?: Material.CHAINMAIL_BOOTS
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

    private fun isTool(material: Material): Boolean =
        material.name.endsWith("_PICKAXE") || material.name.endsWith("_AXE") ||
                material.name.endsWith("_SHOVEL") || material.name.endsWith("_HOE") ||
                material == Material.SHEARS

    private fun getToolType(material: Material): String = when {
        material.name.endsWith("_PICKAXE") -> "pickaxe"
        material.name.endsWith("_AXE") -> "axe"
        material.name.endsWith("_SHOVEL") -> "shovel"
        material.name.endsWith("_HOE") -> "hoe"
        material == Material.SHEARS -> "shears"
        material.name.endsWith("_SWORD") -> "sword"
        else -> "unknown"
    }

    private fun getCurrentSwordTier(player: Player): Int {
        val bestSword = player.inventory.contents
            .filterNotNull()
            .filter { isSword(it.type) }
            .maxByOrNull { getToolMaterialTier(it.type) }

        return bestSword?.type?.let { getToolMaterialTier(it) } ?: 0
    }

    private fun getArmorMaterialTier(material: Material): Int = when {
        material.name.startsWith("LEATHER_") -> 1
        // Copper armor (newer versions). Treat as chainmail-tier by default.
        material.name.startsWith("COPPER_") -> 2
        material.name.startsWith("CHAINMAIL_") -> 2
        material.name.startsWith("GOLDEN_") -> 3
        material.name.startsWith("IRON_") -> 4
        material.name.startsWith("DIAMOND_") -> 5
        material.name.startsWith("NETHERITE_") -> 6
        else -> 0
    }

    private fun materialByNameOrNull(name: String): Material? {
        return try {
            Material.valueOf(name)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun getToolMaterialTier(material: Material): Int = when {
        material.name.startsWith("WOODEN_") -> 1
        material.name.startsWith("STONE_") -> 2
        // Copper tools/weapons (newer versions). Place between stone and iron.
        material.name.startsWith("COPPER_") -> 3
        material.name.startsWith("IRON_") -> 4
        material.name.startsWith("GOLDEN_") -> 5
        material.name.startsWith("DIAMOND_") -> 6
        material.name.startsWith("NETHERITE_") -> 7
        material == Material.SHEARS -> 8
        else -> 0
    }

    private fun shouldUseTeamColor(material: Material): Boolean =
        material.name.contains("_WOOL") ||
                material.name.contains("_TERRACOTTA") ||
                material.name.contains("_CONCRETE") ||
                material.name.contains("_BED")

    private fun convertToTeamColor(item: ItemStack, teamWoolColor: Material): ItemStack {
        val teamColorName = teamWoolColor.name.substringBeforeLast('_', missingDelimiterValue = "WHITE")

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