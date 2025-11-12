package ru.joutak.creakywars.resources

import org.bukkit.Material
import org.bukkit.inventory.ItemStack

data class ResourceType(
    val id: String,
    val material: Material,
    val displayName: String,
    val spawnPeriod: Long, // тики
    val tier: Int // 1 = низкий, 2 = средний, 3 = высокий
) {
    fun createItemStack(amount: Int = 1): ItemStack {
        val item = ItemStack(material, amount)
        val meta = item.itemMeta
        @Suppress("DEPRECATION")
        meta?.setDisplayName("§r$displayName")
        @Suppress("DEPRECATION")
        meta?.lore = listOf("§7Ресурс уровня $tier")
        item.itemMeta = meta
        return item
    }
}