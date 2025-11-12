package ru.joutak.creakywars.trading

import org.bukkit.Bukkit
import org.bukkit.entity.EntityType
import org.bukkit.entity.Illusioner
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import ru.joutak.creakywars.game.Game
import ru.joutak.creakywars.utils.PluginManager

object TraderManager {
    private val gameTraders = mutableMapOf<Game, MutableList<Illusioner>>()
    private val interactionTasks = mutableMapOf<Game, BukkitTask>()

    fun init() {
        PluginManager.getLogger().info("TraderManager инициализирован!")
    }

    fun spawnTraders(game: Game) {
        val traders = mutableListOf<Illusioner>()

        game.arena.mapConfig.traderLocations.forEach { location ->
            val loc = location.toLocation(game.arena.world)
            val trader = game.arena.world.spawnEntity(loc, EntityType.ILLUSIONER) as Illusioner

            @Suppress("DEPRECATION")
            trader.customName = "§9§lТорговец"
            trader.isCustomNameVisible = true
            trader.setAI(false)
            trader.isInvulnerable = true
            trader.isSilent = true
            trader.setGravity(false)
            trader.isCollidable = false
            trader.isPersistent = true
            trader.removeWhenFarAway = false

            traders.add(trader)
        }

        gameTraders[game] = traders
        startInteractionCheck(game)

        PluginManager.getLogger().info("Заспавнено ${traders.size} торговцев для игры #${game.arena.id}")
    }

    private fun startInteractionCheck(game: Game) {
        val task = Bukkit.getScheduler().runTaskTimer(
            PluginManager.getPlugin(),
            Runnable {
                val traders = gameTraders[game] ?: return@Runnable
                var needRespawn = false
                traders.removeIf { trader ->
                    if (!trader.isValid) {
                        PluginManager.getLogger().warning("Торговец исчез в игре #${game.arena.id}!")
                        needRespawn = true
                        true
                    } else {
                        if (!trader.isPersistent) trader.isPersistent = true
                        if (trader.removeWhenFarAway) trader.removeWhenFarAway = false
                        if (!trader.isInvulnerable) trader.isInvulnerable = true
                        false
                    }
                }

                if (needRespawn) {
                    PluginManager.getLogger().warning("Респавн торговцев для игры #${game.arena.id}...")
                    respawnTraders(game)
                }
            },
            0L,
            100L
        )

        interactionTasks[game] = task
    }

    private fun respawnTraders(game: Game) {
        gameTraders[game]?.forEach { it.remove() }
        gameTraders.remove(game)

        spawnTraders(game)
    }

    fun removeTraders(game: Game) {
        interactionTasks[game]?.cancel()
        interactionTasks.remove(game)
        gameTraders[game]?.forEach { trader ->
            trader.remove()
        }

        gameTraders.remove(game)
        PluginManager.getLogger().info("Торговцы удалены для игры #${game.arena.id}")
    }

    fun getTraders(game: Game): List<Illusioner> {
        return gameTraders[game] ?: emptyList()
    }
}