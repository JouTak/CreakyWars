package ru.joutak.creakywars.upgrades

import org.bukkit.Location
import org.bukkit.entity.ArmorStand
import ru.joutak.creakywars.game.Game

/**
 * "МОЗГ" — станция прокачки (пока просто подпись над точкой взаимодействия).
 * Позже сюда можно подвязать отдельный GUI перков.
 */
object BrainStationManager {

    private const val DISPLAY_NAME = "§d§lМОЗГ"

    private val stands = mutableMapOf<Game, MutableList<ArmorStand>>()

    fun spawn(game: Game) {
        remove(game)

        val list = mutableListOf<ArmorStand>()
        game.arena.mapConfig.upgradeLocations.forEach { spawnLoc ->
            val base = spawnLoc.toLocation(game.arena.world)
            val nameLoc = base.clone().add(0.5, 2.2, 0.5)
            list.add(spawnStand(game, nameLoc, DISPLAY_NAME))
        }

        if (list.isNotEmpty()) {
            stands[game] = list
        }
    }

    fun remove(game: Game) {
        stands.remove(game)?.forEach { stand ->
            try {
                stand.remove()
            } catch (_: Exception) {
            }
        }
    }

    private fun spawnStand(game: Game, loc: Location, name: String): ArmorStand {
        return game.arena.world.spawn(loc, ArmorStand::class.java).apply {
            setGravity(false)
            isVisible = false
            isCustomNameVisible = true
            @Suppress("DEPRECATION")
            customName = name
            isMarker = true
            setAI(false)
            isPersistent = true
            isInvulnerable = true
            isSilent = true
            canPickupItems = false
            removeWhenFarAway = false
            try {
                isCollidable = false
            } catch (_: Exception) {
            }
        }
    }
}
