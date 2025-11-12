package ru.joutak.creakywars.game

import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.LeatherArmorMeta
import ru.joutak.creakywars.utils.MessageUtils

data class PlayerLoadout(
    val player: Player,
    val team: Team
) {
    // Базовые предметы (всегда сохраняются)
    private var helmet: ItemStack = createLeatherArmor(Material.LEATHER_HELMET)
    private var chestplate: ItemStack = createLeatherArmor(Material.LEATHER_CHESTPLATE)
    private var leggings: ItemStack? = createLeatherArmor(Material.LEATHER_LEGGINGS)
    private var boots: ItemStack? = createLeatherArmor(Material.LEATHER_BOOTS)
    private var sword: ItemStack = ItemStack(Material.WOODEN_SWORD)

    // Купленные улучшения (сохраняются после смерти)
    private val permanentUpgrades = mutableSetOf<String>()

    private fun createLeatherArmor(material: Material): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta as? LeatherArmorMeta ?: return item

        meta.setColor(getTeamColor(team.woolColor))

        @Suppress("DEPRECATION")
        meta.setDisplayName("${team.color}Броня команды")

        meta.isUnbreakable = true

        item.itemMeta = meta
        return item
    }

    private fun getTeamColor(woolColor: Material): Color {
        return when (woolColor) {
            Material.RED_WOOL -> Color.RED
            Material.BLUE_WOOL -> Color.BLUE
            Material.GREEN_WOOL -> Color.GREEN
            Material.YELLOW_WOOL -> Color.YELLOW
            Material.LIGHT_BLUE_WOOL -> Color.AQUA
            Material.PINK_WOOL -> Color.FUCHSIA
            Material.WHITE_WOOL -> Color.WHITE
            Material.GRAY_WOOL -> Color.GRAY
            else -> Color.WHITE
        }
    }

    fun giveDefaultLoadout() {
        player.inventory.clear()

        player.inventory.helmet = helmet
        player.inventory.chestplate = chestplate
        player.inventory.leggings = leggings
        player.inventory.boots = boots

        player.inventory.addItem(sword)
    }

    fun restoreAfterDeath() {
        player.inventory.clear()

        player.inventory.helmet = helmet
        player.inventory.chestplate = chestplate
        player.inventory.leggings = leggings
        player.inventory.boots = boots

        player.inventory.addItem(ItemStack(Material.WOODEN_SWORD))
    }

    fun upgradeArmor(armorType: String, item: ItemStack) {
        when (armorType.lowercase()) {
            "leggings" -> {
                leggings = item.clone()
                player.inventory.leggings = item
                permanentUpgrades.add("leggings_${item.type.name}")
            }
            "boots" -> {
                boots = item.clone()
                player.inventory.boots = item
                permanentUpgrades.add("boots_${item.type.name}")
            }
            "helmet" -> {
                helmet = item.clone()
                player.inventory.helmet = item
                permanentUpgrades.add("helmet_${item.type.name}")
            }
            "chestplate" -> {
                chestplate = item.clone()
                player.inventory.chestplate = item
                permanentUpgrades.add("chestplate_${item.type.name}")
            }
        }

        MessageUtils.sendMessage(player, "§aБроня улучшена! Она сохранится после смерти.")
    }

    fun upgradeSword(newSword: ItemStack) {
        sword = newSword.clone()

        for (i in 0 until player.inventory.size) {
            val item = player.inventory.getItem(i)
            if (item != null && isSword(item.type)) {
                player.inventory.setItem(i, newSword)
                break
            }
        }

        permanentUpgrades.add("sword_${newSword.type.name}")
        MessageUtils.sendMessage(player, "§aМеч улучшен! После смерти вернется деревянный.")
    }

    private fun isSword(material: Material): Boolean {
        return material.name.endsWith("_SWORD")
    }

    fun isPermanentUpgrade(upgrade: String): Boolean {
        return permanentUpgrades.contains(upgrade)
    }
}