package ru.joutak.creakywars.resources

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Item
import org.bukkit.scheduler.BukkitTask
import ru.joutak.creakywars.config.GameConfig
import ru.joutak.creakywars.game.Game
import ru.joutak.creakywars.game.Team
import ru.joutak.creakywars.utils.PluginManager
import ru.joutak.creakywars.utils.SpawnLocation
import java.util.*

object ResourceSpawner {
    private val activeSpawners = mutableMapOf<Game, MutableList<Spawner>>()

    fun init() {
        PluginManager.getLogger().info("ResourceSpawner инициализирован!")
    }

    fun startSpawning(game: Game) {
        val spawners = mutableListOf<Spawner>()

        for ((resourceTypeId, locations) in game.arena.mapConfig.resourceSpawners) {
            val resourceType = GameConfig.resourceTypes[resourceTypeId] ?: continue

            locations.forEachIndexed { index, location ->
                val teamId = if (index < 4 && (resourceTypeId == "rubber_low" || resourceTypeId == "rubber_mid")) {
                    index
                } else {
                    null
                }

                val spawner = Spawner(
                    game,
                    resourceType,
                    location,
                    resourceType.spawnPeriod,
                    teamId
                )
                spawner.start()
                spawners.add(spawner)
            }
        }
        activeSpawners[game] = spawners
    }

    fun stopSpawning(game: Game) {
        activeSpawners[game]?.forEach { it.stop() }
        activeSpawners.remove(game)
    }

    fun updateTeamMultiplier(game: Game, team: Team, multiplier: Double) {
        activeSpawners[game]?.forEach { spawner ->
            if (spawner.teamId == team.id) {
                spawner.setTeamMultiplier(multiplier)
            }
        }
    }

    fun setMultiplier(game: Game, multiplier: Double) {
        activeSpawners[game]?.forEach { spawner ->
            spawner.setGlobalMultiplier(multiplier)
        }
    }

    class Spawner(
        private val game: Game,
        private val resourceType: ResourceType,
        private val spawnLocation: SpawnLocation,
        val basePeriod: Long,
        val teamId: Int?
    ) {
        private var task: BukkitTask? = null

        private var nameHologram: ArmorStand? = null
        private var timerHologram: ArmorStand? = null

        private var spawnedItem: Item? = null

        private var currentMaxPeriod: Long = basePeriod
        private var ticksRemaining: Long = 0

        private var teamMultiplier: Double = 1.0
        private var globalMultiplier: Double = 1.0

        fun start() {
            val location = spawnLocation.toLocation(game.arena.world)
            createHolograms(location)
            recalculatePeriod()
            ticksRemaining = currentMaxPeriod
            startTickingTask()
        }

        fun stop() {
            task?.cancel()
            nameHologram?.remove()
            timerHologram?.remove()
            spawnedItem?.remove()
        }

        private fun createHolograms(location: Location) {
            val nameLoc = location.clone().add(0.5, 2.3, 0.5)
            nameHologram = spawnArmorStand(nameLoc, "§e${resourceType.displayName}")

            val timerLoc = location.clone().add(0.5, 2.0, 0.5)
            timerHologram = spawnArmorStand(timerLoc, "§7Wait...")
        }

        private fun spawnArmorStand(loc: Location, name: String): ArmorStand {
            return game.arena.world.spawn(loc, ArmorStand::class.java).apply {
                setGravity(false)
                isVisible = false
                isCustomNameVisible = true
                @Suppress("DEPRECATION")
                customName = name
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
        }

        fun setTeamMultiplier(mult: Double) {
            this.teamMultiplier = mult
            recalculatePeriod()
        }

        fun setGlobalMultiplier(mult: Double) {
            this.globalMultiplier = mult
            recalculatePeriod()
        }

        private fun recalculatePeriod() {
            val totalMultiplier = teamMultiplier * globalMultiplier
            currentMaxPeriod = (basePeriod / totalMultiplier).toLong().coerceAtLeast(1)

            if (ticksRemaining > currentMaxPeriod) {
                ticksRemaining = currentMaxPeriod
            }
        }

        private fun startTickingTask() {
            task?.cancel()
            task = Bukkit.getScheduler().runTaskTimer(PluginManager.getPlugin(), Runnable {

                if (ticksRemaining <= 0) {
                    spawnResource()
                    ticksRemaining = currentMaxPeriod
                }

                val seconds = ticksRemaining / 20.0
                val color = if (seconds <= 3.0) "§c" else "§7"

                @Suppress("DEPRECATION")
                timerHologram?.customName = "$color${String.format(Locale.US, "%.1f", seconds)}"

                ticksRemaining--

            }, 0L, 1L)
        }
    }
}