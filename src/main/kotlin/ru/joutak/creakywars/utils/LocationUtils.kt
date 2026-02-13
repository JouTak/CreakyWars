package ru.joutak.creakywars.utils

import org.bukkit.Location
import org.bukkit.World

data class SpawnLocation(
    val name: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float
) {
    fun toLocation(world: World): Location {
        return Location(world, x, y, z, yaw, pitch)
    }

    companion object {
        fun fromString(str: String, name: String = "location"): SpawnLocation {
            val parts = str.split(",").map { it.trim() }
            return SpawnLocation(
                name,
                parts[0].toDouble(),
                parts[1].toDouble(),
                parts[2].toDouble(),
                parts.getOrNull(3)?.toFloat() ?: 0f,
                parts.getOrNull(4)?.toFloat() ?: 0f
            )
        }
    }

    override fun toString(): String {
        return "$x, $y, $z, $yaw, $pitch"
    }
}