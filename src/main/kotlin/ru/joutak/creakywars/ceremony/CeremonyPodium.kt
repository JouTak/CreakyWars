package ru.joutak.creakywars.ceremony

import org.bukkit.Location
import org.bukkit.World

data class CeremonyPodium(
    val minX: Int,
    val y: Int,
    val minZ: Int,
    val maxX: Int,
    val maxZ: Int,
    val yaw: Float = 180f,
) {
    private val width: Int = (maxX - minX + 1).coerceAtLeast(1)
    private val depth: Int = (maxZ - minZ + 1).coerceAtLeast(1)

    fun toBounds(): Bounds {
        // +1.0 to include the full last block space
        return Bounds(
            minX.toDouble(),
            (maxX + 1).toDouble(),
            minZ.toDouble(),
            (maxZ + 1).toDouble(),
        )
    }

    fun getSpawnLocation(world: World, slot: Int): Location {
        val idx = if (width * depth <= 0) 0 else (slot % (width * depth))
        val dx = idx % width
        val dz = idx / width

        val x = minX + dx + 0.5
        val z = minZ + dz + 0.5
        val loc = Location(world, x, y + 1.0, z)
        loc.yaw = yaw
        loc.pitch = 0f
        return loc
    }
}

data class Bounds(
    val minX: Double,
    val maxX: Double,
    val minZ: Double,
    val maxZ: Double,
) {
    fun contains(x: Double, z: Double): Boolean {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ
    }

    fun contains(loc: Location): Boolean {
        return contains(loc.x, loc.z)
    }

    fun clamp(loc: Location): Location {
        val x = loc.x.coerceIn(minX + 0.001, maxX - 0.001)
        val z = loc.z.coerceIn(minZ + 0.001, maxZ - 0.001)
        return Location(loc.world, x, loc.y, z, loc.yaw, loc.pitch)
    }
}
