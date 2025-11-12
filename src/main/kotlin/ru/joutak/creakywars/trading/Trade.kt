package ru.joutak.creakywars.trading

import org.bukkit.inventory.ItemStack

data class Trade(
    val id: String,
    val cost: Pair<String, Int>,
    val result: ItemStack,
    val displayName: String,
    val category: String
)