package ru.joutak.creakywars.config

import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import ru.joutak.creakywars.utils.PluginManager
import ru.joutak.creakywars.utils.SpawnLocation
import java.io.File

data class MapConfig(
    val mapName: String,
    val displayName: String,
    val description: String,
    val timeOfDay: Long,
    val weather: String,
    val resourceSpawners: Map<String, List<SpawnLocation>>,
    val traderLocations: List<SpawnLocation>,
    val teamSpawns: List<SpawnLocation>,
    val coreLocations: List<SpawnLocation>,
    val eyeblossomLocations: List<SpawnLocation>,
    val creakingSpawnLocation: SpawnLocation?
) {
    companion object {
        private lateinit var config: FileConfiguration
        private lateinit var file: File

        fun init() {
            val plugin = PluginManager.getPlugin()
            file = File(plugin.dataFolder, "maps-config.yml")

            if (!file.exists()) {
                plugin.saveResource("maps-config.yml", false)
                PluginManager.getLogger().info("Создан maps-config.yml из шаблона")
            }

            config = YamlConfiguration.loadConfiguration(file)
            PluginManager.getLogger().info("Конфиг карт (maps-config.yml) загружен!")
        }

        fun load(mapName: String): MapConfig {
            if (!::config.isInitialized) {
                init()
            }

            val mapSection = config.getConfigurationSection(mapName)
                ?: throw IllegalStateException("Карта '$mapName' не найдена в maps.yml!")

            val displayName = mapSection.getString("display-name", mapName) ?: mapName
            val description = mapSection.getString("description", "") ?: ""
            val timeOfDay = mapSection.getLong("time-of-day", 6000L)
            val weather = mapSection.getString("weather", "clear") ?: "clear"

            val resourceSpawners = mutableMapOf<String, List<SpawnLocation>>()
            val spawnersSection = mapSection.getConfigurationSection("resource-spawners")
            if (spawnersSection != null) {
                for (resourceType in spawnersSection.getKeys(false)) {
                    val locations = spawnersSection.getStringList(resourceType).mapIndexed { index, locStr ->
                        SpawnLocation.fromString(locStr, "${resourceType}_$index")
                    }
                    resourceSpawners[resourceType] = locations
                }
            }

            val traderLocations = mapSection.getStringList("trader-locations").mapIndexed { index, locStr ->
                SpawnLocation.fromString(locStr, "trader_$index")
            }

            val teamSpawns = mapSection.getStringList("team-spawns").mapIndexed { index, locStr ->
                SpawnLocation.fromString(locStr, "team_$index")
            }

            val coreLocations = mapSection.getStringList("core-locations").mapIndexed { index, locStr ->
                SpawnLocation.fromString(locStr, "core_$index")
            }

            val eyeblossomLocations = mapSection.getStringList("eyeblossom-locations").mapIndexed { index, locStr ->
                SpawnLocation.fromString(locStr, "eyeblossom_$index")
            }

            val creakingSpawnStr = mapSection.getString("creaking-spawn")
            val creakingSpawnLocation = if (creakingSpawnStr != null) {
                SpawnLocation.fromString(creakingSpawnStr, "creaking_spawn")
            } else null

            return MapConfig(
                mapName,
                displayName,
                description,
                timeOfDay,
                weather,
                resourceSpawners,
                traderLocations,
                teamSpawns,
                coreLocations,
                eyeblossomLocations,
                creakingSpawnLocation
            )
        }

        fun getAllMapNames(): List<String> {
            if (!::config.isInitialized) {
                init()
            }

            return config.getKeys(false).toList()
        }

        fun reload() {
            config = YamlConfiguration.loadConfiguration(file)
        }
    }
}