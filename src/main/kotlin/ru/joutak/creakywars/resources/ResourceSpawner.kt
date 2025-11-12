package ru.joutak.creakywars.resources

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Item
import org.bukkit.scheduler.BukkitTask
import ru.joutak.creakywars.config.GameConfig
import ru.joutak.creakywars.game.Game
import ru.joutak.creakywars.utils.PluginManager
import ru.joutak.creakywars.utils.SpawnLocation

object ResourceSpawner {
    private val activeSpawners = mutableMapOf<Game, MutableList<Spawner>>()
    private val resourceMultipliers = mutableMapOf<Game, Double>()

    fun init() {
        PluginManager.getLogger().info("ResourceSpawner инициализирован!")
    }

    fun startSpawning(game: Game) {
        val spawners = mutableListOf<Spawner>()
        resourceMultipliers[game] = 1.0

        for ((resourceTypeId, locations) in game.arena.mapConfig.resourceSpawners) {
            val resourceType = GameConfig.resourceTypes[resourceTypeId]

            if (resourceType == null) {
                PluginManager.getLogger().warning("Тип ресурса '$resourceTypeId' не найден в GameConfig!")
                continue
            }

            PluginManager.getLogger().info("Создаем спавнеры для '$resourceTypeId' (${locations.size} точек)")

            for (location in locations) {
                val spawner = Spawner(
                    game,
                    resourceType,
                    location,
                    resourceType.spawnPeriod
                )
                spawner.start()
                spawners.add(spawner)
            }
        }

        activeSpawners[game] = spawners
        PluginManager.getLogger().info("Запущено ${spawners.size} спавнеров ресурсов для игры #${game.arena.id}")
    }

    fun stopSpawning(game: Game) {
        activeSpawners[game]?.forEach { it.stop() }
        activeSpawners.remove(game)
        resourceMultipliers.remove(game)
    }

    fun setMultiplier(game: Game, multiplier: Double) {
        resourceMultipliers[game] = multiplier

        activeSpawners[game]?.forEach { spawner ->
            val newPeriod = (spawner.basePeriod / multiplier).toLong()
            spawner.updatePeriod(newPeriod)
        }
    }

    class Spawner(
        private val game: Game,
        private val resourceType: ResourceType,
        private val spawnLocation: SpawnLocation,
        val basePeriod: Long
    ) {
        private var task: BukkitTask? = null
        private var hologram: ArmorStand? = null
        private var spawnedItem: Item? = null
        private var currentPeriod: Long = basePeriod

        fun start() {
            val location = spawnLocation.toLocation(game.arena.world)

            createHologram(location)

            PluginManager.getLogger().info(
                "[Spawner] Запуск спавнера '${resourceType.displayName}' на " +
                        "(${spawnLocation.x}, ${spawnLocation.y}, ${spawnLocation.z}) " +
                        "период: $currentPeriod тиков (${currentPeriod / 20} сек)"
            )

            task = Bukkit.getScheduler().runTaskTimer(PluginManager.getPlugin(), Runnable {
                spawnResource()
            }, 0L, currentPeriod)
        }

        fun stop() {
            task?.cancel()
            hologram?.remove()
            spawnedItem?.remove()
        }

        fun updatePeriod(newPeriod: Long) {
            if (currentPeriod == newPeriod) return

            currentPeriod = newPeriod

            task?.cancel()
            task = Bukkit.getScheduler().runTaskTimer(PluginManager.getPlugin(), Runnable {
                spawnResource()
            }, 0L, currentPeriod)
        }

        private fun createHologram(location: Location) {
            val hologramLoc = location.clone().add(0.5, 2.0, 0.5)
            hologram = game.arena.world.spawn(hologramLoc, ArmorStand::class.java).apply {
                setGravity(false)
                isVisible = false
                isCustomNameVisible = true
                @Suppress("DEPRECATION")
                customName = "§e${resourceType.displayName}"
                isMarker = true
                setAI(false)
                isPersistent = true
            }
        }

        private fun spawnResource() {
            val location = spawnLocation.toLocation(game.arena.world).add(0.5, 0.5, 0.5)
            val itemStack = resourceType.createItemStack()

            val item = game.arena.world.dropItem(location, itemStack).apply {
                velocity = org.bukkit.util.Vector(0.0, 0.0, 0.0)
                pickupDelay = 10

                setMetadata(
                    "creakywars_resource",
                    org.bukkit.metadata.FixedMetadataValue(PluginManager.getPlugin(), resourceType.id)
                )
            }

            spawnedItem = item

            PluginManager.getLogger().info(
                "[Spawner] Заспавнен '${resourceType.displayName}' на " +
                        "(${location.blockX}, ${location.blockY}, ${location.blockZ})"
            )
        }
    }
}