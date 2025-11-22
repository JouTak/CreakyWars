package ru.joutak.creakywars.game

import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.inventory.meta.LeatherArmorMeta
import ru.joutak.creakywars.utils.MessageUtils

data class PlayerLoadout(
    val player: Player,
    val team: Team
) {
    private var helmet: ItemStack = makeUnbreakableArmor(createLeatherArmor(Material.LEATHER_HELMET))
    private var chestplate: ItemStack = makeUnbreakableArmor(createLeatherArmor(Material.LEATHER_CHESTPLATE))
    private var leggings: ItemStack = makeUnbreakableArmor(createLeatherArmor(Material.LEATHER_LEGGINGS))
    private var boots: ItemStack = makeUnbreakableArmor(createLeatherArmor(Material.LEATHER_BOOTS))
    private var sword: ItemStack = makeUnbreakable(ItemStack(Material.WOODEN_SWORD))
    private val permanentArmorUpgrades = mutableSetOf<String>()

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
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
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
        player.inventory.helmet = helmet
        player.inventory.chestplate = chestplate
        player.inventory.leggings = leggings
        player.inventory.boots = boots
        player.inventory.addItem(makeUnbreakable(sword.clone()))
    }

    fun restoreAfterDeath() {
        player.inventory.clear()
        player.inventory.helmet = helmet
        player.inventory.chestplate = chestplate
        player.inventory.leggings = leggings
        player.inventory.boots = boots
        player.inventory.addItem(makeUnbreakable(ItemStack(Material.WOODEN_SWORD)))
    }

    fun upgradeArmor(armorType: String, item: ItemStack, silent: Boolean = false) {
        val upgradedItem = makeUnbreakableArmor(item.clone())
        val meta = upgradedItem.itemMeta

        meta?.addEnchant(Enchantment.BINDING_CURSE, 1, true)
        meta?.addItemFlags(ItemFlag.HIDE_ENCHANTS)
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
            MessageUtils.sendMessage(player, "§aБроня улучшена! Она сохранится после смерти.")
        }
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

    fun upgradeSword(newSword: ItemStack) {
        val unbreakableSword = makeUnbreakable(newSword.clone())
        for (i in 0 until player.inventory.size) {
            val item = player.inventory.getItem(i)
            if (item != null && isSword(item.type)) {
                player.inventory.setItem(i, unbreakableSword)
                break
            }
        }
        MessageUtils.sendMessage(player, "§aМеч улучшен! §eПосле смерти вернется деревянный.")
    }

    fun giveUnbreakableTool(tool: ItemStack) {
        player.inventory.addItem(makeUnbreakable(tool.clone()))
    }

    fun giveUnbreakableArmor(armor: ItemStack) {
        player.inventory.addItem(makeUnbreakableArmor(armor.clone()))
    }

    private fun isSword(material: Material): Boolean {
        return material.name.endsWith("_SWORD")
    }

    fun isPermanentUpgrade(upgrade: String): Boolean {
        return permanentArmorUpgrades.contains(upgrade)
    }
}