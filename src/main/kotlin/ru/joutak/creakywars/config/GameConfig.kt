package ru.joutak.creakywars.config

import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.joutak.creakywars.resources.ResourceType
import ru.joutak.creakywars.trading.Trade
import ru.joutak.creakywars.utils.PluginManager
import java.io.File

object GameConfig {
    private lateinit var config: FileConfiguration
    private lateinit var file: File

    val resourceTypes = mutableMapOf<String, ResourceType>()
    val trades = mutableListOf<Trade>()

    var dayDurationTicks: Long = 6000L
    var nightDurationTicks: Long = 6000L
    var eyeblossomOpenPercent: Double = 0.3
    var creakingAggroRadius: Double = 30.0
    var respawnDelaySeconds: Int = 5
    var respawnSpectatorMode: Boolean = true

    fun load() {
        val plugin = PluginManager.getPlugin()
        file = File(plugin.dataFolder, "game-config.yml")

        if (!file.exists()) {
            plugin.saveResource("game-config.yml", false)
            PluginManager.getLogger().info("Создан game-config.yml из шаблона")
        }

        config = YamlConfiguration.loadConfiguration(file)

        loadDayNightCycle()
        loadRespawnSettings()
        loadResources()
        loadTrades()

        PluginManager.getLogger().info("Игровой конфиг загружен!")
    }

    private fun loadDayNightCycle() {
        dayDurationTicks = config.getLong("day-night-cycle.day-duration-ticks", 6000L)
        nightDurationTicks = config.getLong("day-night-cycle.night-duration-ticks", 6000L)
        eyeblossomOpenPercent = config.getDouble("day-night-cycle.eyeblossom-open-percent", 0.3)
        creakingAggroRadius = config.getDouble("day-night-cycle.creaking-aggro-radius", 30.0)
    }

    private fun loadRespawnSettings() {
        respawnDelaySeconds = config.getInt("respawn.delay-seconds", 5)
        respawnSpectatorMode = config.getBoolean("respawn.spectator-mode", true)
    }

    private fun loadResources() {
        resourceTypes.clear()

        val resourcesSection = config.getConfigurationSection("resources") ?: return

        for (key in resourcesSection.getKeys(false)) {
            val section = resourcesSection.getConfigurationSection(key) ?: continue

            val material = Material.valueOf(section.getString("material", "SLIME_BALL")!!.uppercase())
            val displayName = section.getString("display-name", key)!!
            val spawnPeriod = section.getLong("spawn-period", 20L)
            val tier = section.getInt("tier", 1)

            resourceTypes[key] = ResourceType(key, material, displayName, spawnPeriod, tier)
        }
    }

    private fun loadTrades() {
        trades.clear()

        val tradesSection = config.getConfigurationSection("trades") ?: return

        for (key in tradesSection.getKeys(false)) {
            val section = tradesSection.getConfigurationSection(key) ?: continue

            val cost = parseCost(section.getString("cost", "rubber_low:4")!!)
            val result = parseItem(section.getString("result", "STONE_SWORD")!!)
            val displayName = section.getString("display-name", key)!!
            val category = section.getString("category", "items")!!

            trades.add(Trade(key, cost, result, displayName, category))
        }
    }

    private fun parseCost(costStr: String): Pair<String, Int> {
        val parts = costStr.split(":")
        return Pair(parts[0], parts.getOrNull(1)?.toInt() ?: 1)
    }

    private fun parseItem(itemStr: String): ItemStack {
        val parts = itemStr.split(":")
        val material = Material.valueOf(parts[0].uppercase())
        val amount = parts.getOrNull(1)?.toInt() ?: 1
        return ItemStack(material, amount)
    }

    fun reload() {
        config = YamlConfiguration.loadConfiguration(file)
        load()
    }
}