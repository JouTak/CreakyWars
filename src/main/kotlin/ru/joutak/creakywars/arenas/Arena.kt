package ru.joutak.creakywars.arenas

import org.bukkit.World
import ru.joutak.creakywars.config.MapConfig
import ru.joutak.creakywars.game.Game

data class Arena(
    val id: Int,
    val world: World,
    val mapConfig: MapConfig,
    var state: ArenaState = ArenaState.WAITING,
    var game: Game? = null
) {
    val worldName: String get() = world.name

    fun isAvailable(): Boolean {
        return state == ArenaState.WAITING
    }
}

