package ru.joutak.creakywars.trading

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.EntityType
import org.bukkit.entity.Illusioner
import org.bukkit.scheduler.BukkitTask
import ru.joutak.creakywars.game.Game
import ru.joutak.creakywars.utils.PluginManager

object TraderManager {
    private val gameTraders = mutableMapOf<Game, MutableList<Illusioner>>()
    private val traderLocations = mutableMapOf<Game, List<Location>>()
    private val interactionTasks = mutableMapOf<Game, BukkitTask>()

    fun init() {
        PluginManager.getLogger().info("TraderManager инициализирован!")
    }

    fun spawnTraders(game: Game) {
        val traders = mutableListOf<Illusioner>()
        val locations = mutableListOf<Location>()

        game.arena.mapConfig.traderLocations.forEach { location ->
            val loc = location.toLocation(game.arena.world)
            locations.add(loc)

            val trader = spawnSingleTrader(loc)
            traders.add(trader)
        }

        gameTraders[game] = traders
        traderLocations[game] = locations

        PluginManager.getLogger().info("Заспавнено ${traders.size} торговцев для игры #${game.arena.id}")
    }

    private fun spawnSingleTrader(loc: Location): Illusioner {
        val trader = loc.world.spawnEntity(loc, EntityType.ILLUSIONER) as Illusioner

        @Suppress("DEPRECATION")
        trader.customName = "§6§lТорговец"
        trader.isCustomNameVisible = true
        trader.setAI(false)
        trader.isInvulnerable = true
        trader.isSilent = true
        trader.setGravity(false)
        trader.isCollidable = false
        trader.isPersistent = true
        trader.removeWhenFarAway = false
        trader.health = trader.maxHealth

        return trader
    }

    fun removeTraders(game: Game) {
        interactionTasks[game]?.cancel()
        interactionTasks.remove(game)

        gameTraders[game]?.forEach { trader ->
            trader.remove()
        }

        gameTraders.remove(game)
        traderLocations.remove(game)
        PluginManager.getLogger().info("Торговцы удалены для игры #${game.arena.id}")
    }

    fun getTraders(game: Game): List<Illusioner> {
        return gameTraders[game] ?: emptyList()
    }
}