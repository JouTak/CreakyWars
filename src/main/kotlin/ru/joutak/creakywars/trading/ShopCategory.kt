package ru.joutak.creakywars.trading
import org.bukkit.Material

data class ShopCategory(
    val id: String,
    val displayName: String,
    val icon: Material,
    val slot: Int
)