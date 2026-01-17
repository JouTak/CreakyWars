package ru.joutak.creakywars.game

import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.LeatherArmorMeta
import ru.joutak.creakywars.utils.MessageUtils
import ru.joutak.creakywars.utils.PluginManager

data class PlayerLoadout(
    val player: Player,
    val team: Team
) {
    private var helmet: ItemStack = makeUnbreakableArmor(createLeatherArmor(Material.LEATHER_HELMET))
    private var chestplate: ItemStack = makeUnbreakableArmor(createLeatherArmor(Material.LEATHER_CHESTPLATE))
    private var leggings: ItemStack = makeUnbreakableArmor(createLeatherArmor(Material.LEATHER_LEGGINGS))
    private var boots: ItemStack = makeUnbreakableArmor(createLeatherArmor(Material.LEATHER_BOOTS))
    var sword: ItemStack = makeUnbreakable(ItemStack(Material.WOODEN_SWORD))
    private val permanentArmorUpgrades = mutableSetOf<String>()

    fun getStoredArmor(type: String): ItemStack? {
        return when (type.lowercase()) {
            "helmet" -> helmet
            "chestplate" -> chestplate
            "leggings" -> leggings
            "boots" -> boots
            else -> null
        }
    }

    private fun createLeatherArmor(material: Material): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta as? LeatherArmorMeta ?: return item
        val color = when (team.woolColor) {
            Material.ORANGE_TERRACOTTA -> Color.ORANGE
            Material.BLUE_TERRACOTTA -> Color.BLUE
            Material.PINK_TERRACOTTA -> Color.FUCHSIA
            Material.GREEN_TERRACOTTA -> Color.GREEN
            else -> Color.WHITE
        }
        meta.setColor(color)

        @Suppress("DEPRECATION")
        meta.setDisplayName("${team.color}Броня команды ${team.name}")
        meta.isUnbreakable = true
        meta.addEnchant(Enchantment.BINDING_CURSE, 1, true)

        item.itemMeta = meta
        return item
    }

    private fun makeUnbreakable(item: ItemStack): ItemStack {
        val meta = item.itemMeta ?: return item
        meta.isUnbreakable = true
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE)
        item.itemMeta = meta
        return item
    }

    private fun makeUnbreakableArmor(item: ItemStack): ItemStack {
        val meta = item.itemMeta
        meta?.isUnbreakable = true
        meta?.addItemFlags(ItemFlag.HIDE_UNBREAKABLE)
        item.itemMeta = meta
        return item
    }

    fun giveDefaultLoadout() {
        player.inventory.clear()

        applyTeamEnchants(helmet)
        applyTeamEnchants(chestplate)
        applyTeamEnchants(leggings)
        applyTeamEnchants(boots)
        applyTeamEnchants(sword)

        player.inventory.helmet = helmet
        player.inventory.chestplate = chestplate
        player.inventory.leggings = leggings
        player.inventory.boots = boots

        player.inventory.addItem(sword)
    }

    private fun applyTeamEnchants(item: ItemStack) {
        val meta = item.itemMeta ?: return

        if (isArmor(item.type)) {
            if (team.protectionLevel > 0) {
                meta.addEnchant(Enchantment.PROTECTION, team.protectionLevel, true)
            }
        }

        if (isSword(item.type)) {
            if (team.sharpnessLevel > 0) {
                meta.addEnchant(Enchantment.SHARPNESS, team.sharpnessLevel, true)
            }
        }

        if (isTool(item.type)) {
            if (team.efficiencyLevel > 0) {
                meta.addEnchant(Enchantment.EFFICIENCY, team.efficiencyLevel, true)
            }
        }

        item.itemMeta = meta
    }

    fun refreshLoadout() {
        val inv = player.inventory

        listOf(inv.helmet, inv.chestplate, inv.leggings, inv.boots).forEach { item ->
            if (item != null) applyTeamEnchants(item)
        }

        for (item in inv.contents) {
            if (item != null && (isSword(item.type) || isTool(item.type))) {
                applyTeamEnchants(item)
            }
        }
    }

    fun upgradeSword(newSword: ItemStack) {
        val unbreakableSword = makeUnbreakable(newSword.clone())
        applyTeamEnchants(unbreakableSword)

        val inv = player.inventory
        var replaced = false
        for (i in 0 until inv.size) {
            val item = inv.getItem(i)
            if (item != null && isSword(item.type)) {
                inv.setItem(i, unbreakableSword)
                replaced = true
                break
            }
        }

        if (!replaced) {
            val leftover = inv.addItem(unbreakableSword)
            if (leftover.isNotEmpty()) {
                leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
                MessageUtils.sendMessage(player, "§eКупленный меч выпал на землю!")
            }
        }

        sword = unbreakableSword
        MessageUtils.sendMessage(player, "§aМеч улучшен!")
    }

    fun ensureHasSwordInInventory() {
        if (player.gameMode != org.bukkit.GameMode.SURVIVAL) return
        if (player.inventory.contents.any { it != null && isSword(it.type) }) return

        val baseSword = ItemStack(Material.WOODEN_SWORD)
        makeUnbreakable(baseSword)
        applyTeamEnchants(baseSword)

        val leftover = player.inventory.addItem(baseSword)
        if (leftover.isNotEmpty()) {
            leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
        }

        sword = baseSword
    }

    fun addOrReplaceTool(tool: ItemStack) {
        val item = makeUnbreakable(tool.clone())
        applyTeamEnchants(item)

        val toolType = getToolType(item.type)

        for (i in 0 until player.inventory.size) {
            val existingItem = player.inventory.getItem(i)
            if (existingItem != null && getToolType(existingItem.type) == toolType) {
                player.inventory.setItem(i, item)
                return
            }
        }

        val leftover = player.inventory.addItem(item)
        if (leftover.isNotEmpty()) {
            leftover.values.forEach {
                player.world.dropItemNaturally(player.location, it)
            }
            MessageUtils.sendMessage(player, "§eКупленный инструмент выпал на землю!")
        }
    }

    fun upgradeArmor(armorType: String, item: ItemStack, silent: Boolean = false) {
        val upgradedItem = makeUnbreakableArmor(item.clone())
        applyTeamEnchants(upgradedItem)
        val meta = upgradedItem.itemMeta

        meta?.addEnchant(Enchantment.BINDING_CURSE, 1, true)
        meta?.addItemFlags(ItemFlag.HIDE_UNBREAKABLE)
        @Suppress("DEPRECATION")
        meta?.setDisplayName("${team.color}${getArmorName(armorType)}")
        upgradedItem.itemMeta = meta

        when (armorType.lowercase()) {
            "leggings" -> {
                leggings = upgradedItem.clone()
                player.inventory.leggings = upgradedItem
                permanentArmorUpgrades.add("leggings_${item.type.name}")
            }
            "boots" -> {
                boots = upgradedItem.clone()
                player.inventory.boots = upgradedItem
                permanentArmorUpgrades.add("boots_${item.type.name}")
            }
            "helmet" -> {
                helmet = upgradedItem.clone()
                player.inventory.helmet = upgradedItem
                permanentArmorUpgrades.add("helmet_${item.type.name}")
            }
            "chestplate" -> {
                chestplate = upgradedItem.clone()
                player.inventory.chestplate = upgradedItem
                permanentArmorUpgrades.add("chestplate_${item.type.name}")
            }
        }

        if (!silent) {
            MessageUtils.sendMessage(player, "§aБроня улучшена!")
        }
    }

    fun equipElytra(item: ItemStack, silent: Boolean = false) {
        val elytra = item.clone()
        applyTeamEnchants(elytra)

        val oldChest = player.inventory.chestplate
        player.inventory.chestplate = elytra

        if (oldChest != null && !oldChest.type.isAir) {
            val leftover = player.inventory.addItem(oldChest)
            if (leftover.isNotEmpty()) {
                leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
            }
        }

        if (!silent) {
            MessageUtils.sendMessage(player, "§aЭлитры экипированы!")
        }
    }




    fun equipElytraFromHand(hand: EquipmentSlot, silent: Boolean = false) {
        val inv = player.inventory
        if (inv.chestplate?.type == Material.ELYTRA) {
            if (!silent) {
                MessageUtils.sendMessage(player, "§eЭлитры уже надеты.")
            }
            return
        }

        val handItem = if (hand == EquipmentSlot.HAND) inv.itemInMainHand else inv.itemInOffHand
        if (handItem.type != Material.ELYTRA || handItem.amount <= 0) return

        val one = handItem.clone()
        one.amount = 1

        // Consume one elytra from the used hand.
        // Use explicit setter methods to avoid Kotlin property quirks across different Bukkit/Paper APIs.
        if (handItem.amount <= 1) {
            if (hand == EquipmentSlot.HAND) {
                inv.setItemInMainHand(ItemStack(Material.AIR))
            } else {
                inv.setItemInOffHand(ItemStack(Material.AIR))
            }
        } else {
            handItem.amount = handItem.amount - 1
            if (hand == EquipmentSlot.HAND) {
                inv.setItemInMainHand(handItem)
            } else {
                inv.setItemInOffHand(handItem)
            }
        }

        equipElytra(one, silent = silent)
    }


    fun restoreAfterDeath() {
        player.inventory.clear()

        applyTeamEnchants(helmet)
        player.inventory.helmet = helmet

        applyTeamEnchants(chestplate)
        player.inventory.chestplate = chestplate

        applyTeamEnchants(leggings)
        player.inventory.leggings = leggings

        applyTeamEnchants(boots)
        player.inventory.boots = boots

        val baseSword = ItemStack(Material.WOODEN_SWORD)
        makeUnbreakable(baseSword)
        applyTeamEnchants(baseSword)
        player.inventory.addItem(baseSword)

        sword = baseSword
    }

    private fun isArmor(material: Material): Boolean =
        material == Material.ELYTRA ||
                material.name.endsWith("_HELMET") || material.name.endsWith("_CHESTPLATE") ||
                material.name.endsWith("_LEGGINGS") || material.name.endsWith("_BOOTS")

    private fun isSword(material: Material): Boolean = material.name.endsWith("_SWORD")

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
        else -> "unknown"
    }

    private fun getArmorName(armorType: String): String {
        return when (armorType.lowercase()) {
            "helmet" -> "Шлем"
            "chestplate" -> "Кираса"
            "leggings" -> "Поножи"
            "boots" -> "Ботинки"
            else -> "Броня"
        }
    }


    companion object {
        private val ELYTRA_SWAP_KEY by lazy {
            NamespacedKey(PluginManager.getPlugin(), "cw_elytra_swap")
        }

        fun markSwapElytra(item: ItemStack) {
            if (item.type != Material.ELYTRA) return
            val meta = item.itemMeta ?: return
            meta.persistentDataContainer.set(ELYTRA_SWAP_KEY, PersistentDataType.BYTE, 1)
            item.itemMeta = meta
        }

        fun isSwapElytra(item: ItemStack?): Boolean {
            if (item == null || item.type != Material.ELYTRA) return false
            val meta = item.itemMeta ?: return true

            if (meta.persistentDataContainer.has(ELYTRA_SWAP_KEY, PersistentDataType.BYTE)) {
                return true
            }

            // Backwards compatibility: treat a plain elytra (no name/lore) as a swap-elytra too.
            val hasName = meta.hasDisplayName()
            val lore = meta.lore
            if (!hasName && (lore == null || lore.isEmpty())) {
                return true
            }

            val name = if (hasName) meta.displayName else ""
            if (name.contains("Элитры")) return true
            if (lore?.any { it.contains("заменяют нагрудник") } == true) return true

            return false
        }
    }

    fun isPermanentUpgrade(upgrade: String): Boolean {
        return permanentArmorUpgrades.contains(upgrade)
    }
}