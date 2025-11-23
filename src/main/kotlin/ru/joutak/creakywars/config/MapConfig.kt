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
    val creakingSpawnLocations: List<SpawnLocation>,
    val upgradeLocations: List<SpawnLocation>
) {
    companion object {
        private lateinit var config: FileConfiguration
        private lateinit var file: File

        fun init() {
            val plugin = PluginManager.getPlugin()
            file = File(plugin.dataFolder, "maps-config.yml")

            if (!file.exists()) {
                plugin.saveResource("maps-config.yml", false)
                PluginManager.getLogger().info("✓ Создан maps-config.yml из шаблона")
            }

            config = YamlConfiguration.loadConfiguration(file)

            val mapCount = config.getKeys(false).size
            if (mapCount == 0) {
                PluginManager.getLogger().warning("⚠ В maps-config.yml не найдено ни одной карты!")
            } else {
                PluginManager.getLogger().info("✓ Конфиг карт загружен! Найдено карт: $mapCount")
            }
        }

        fun load(mapName: String): MapConfig {
            if (!::config.isInitialized) {
                init()
            }

            val mapSection = config.getConfigurationSection(mapName)
                ?: throw IllegalStateException("Карта '$mapName' не найдена в maps-config.yml!")

            val displayName = mapSection.getString("display-name", mapName) ?: mapName
            val description = mapSection.getString("description", "") ?: ""
            val timeOfDay = mapSection.getLong("time-of-day", 6000L)
            val weather = mapSection.getString("weather", "clear") ?: "clear"

            // Генераторы
            val resourceSpawners = mutableMapOf<String, List<SpawnLocation>>()
            val spawnersSection = mapSection.getConfigurationSection("resource-spawners")
            if (spawnersSection != null) {
                for (resourceType in spawnersSection.getKeys(false)) {
                    val locations = spawnersSection.getStringList(resourceType).mapIndexed { index, locStr ->
                        try {
                            SpawnLocation.fromString(locStr, "${resourceType}_$index")
                        } catch (e: Exception) {
                            PluginManager.getLogger().warning("⚠ Ошибка парсинга локации генератора $resourceType[$index]: $locStr")
                            throw e
                        }
                    }
                    resourceSpawners[resourceType] = locations
                }
            }

            val upgradeLocations = mapSection.getStringList("upgrade-locations").mapIndexed { index, locStr ->
                try {
                    SpawnLocation.fromString(locStr, "upgrade_$index")
                } catch (e: Exception) {
                    PluginManager.getLogger().warning("⚠ Ошибка парсинга локации улучшений[$index]: $locStr")
                    throw e
                }
            }

            // Торговцы
            val traderLocations = mapSection.getStringList("trader-locations").mapIndexed { index, locStr ->
                try {
                    SpawnLocation.fromString(locStr, "trader_$index")
                } catch (e: Exception) {
                    PluginManager.getLogger().warning("⚠ Ошибка парсинга локации торговца[$index]: $locStr")
                    throw e
                }
            }

            // Спавны команд
            val teamSpawns = mapSection.getStringList("team-spawns").mapIndexed { index, locStr ->
                try {
                    SpawnLocation.fromString(locStr, "team_$index")
                } catch (e: Exception) {
                    PluginManager.getLogger().warning("⚠ Ошибка парсинга спавна команды[$index]: $locStr")
                    throw e
                }
            }

            // Ядра
            val coreLocations = mapSection.getStringList("core-locations").mapIndexed { index, locStr ->
                try {
                    SpawnLocation.fromString(locStr, "core_$index")
                } catch (e: Exception) {
                    PluginManager.getLogger().warning("⚠ Ошибка парсинга локации ядра[$index]: $locStr")
                    throw e
                }
            }

            // Глазосветы
            val eyeblossomLocations = mapSection.getStringList("eyeblossom-locations").mapIndexed { index, locStr ->
                try {
                    SpawnLocation.fromString(locStr, "eyeblossom_$index")
                } catch (e: Exception) {
                    PluginManager.getLogger().warning("⚠ Ошибка парсинга локации Eyeblossom[$index]: $locStr")
                    throw e
                }
            }

            // Скрипуны
            val creakingSpawnLocations = mapSection.getStringList("creaking-spawns").mapIndexed { index, locStr ->
                try {
                    SpawnLocation.fromString(locStr, "creaking_spawn_$index")
                } catch (e: Exception) {
                    PluginManager.getLogger().warning("⚠ Ошибка парсинга спавна Скрипуна[$index]: $locStr")
                    throw e
                }
            }

            PluginManager.getLogger().info("✓ Карта '$displayName' загружена успешно")

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
                creakingSpawnLocations,
                upgradeLocations
            )
        }

        fun getAllMapNames(): List<String> {
            if (!::config.isInitialized) {
                init()
            }

            return config.getKeys(false).toList()
        }

        fun reload() {
            if (!file.exists()) {
                PluginManager.getLogger().warning("⚠ maps-config.yml не найден! Создаю новый...")
                init()
                return
            }

            config = YamlConfiguration.loadConfiguration(file)
            val mapCount = config.getKeys(false).size
            PluginManager.getLogger().info("✓ maps-config.yml перезагружен! Карт: $mapCount")
        }

        fun exists(mapName: String): Boolean {
            if (!::config.isInitialized) {
                init()
            }
            return config.contains(mapName)
        }
    }
}