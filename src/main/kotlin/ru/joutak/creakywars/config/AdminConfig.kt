package ru.joutak.creakywars.config

import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import ru.joutak.creakywars.arenas.ArenaManager
import ru.joutak.creakywars.utils.PluginManager
import java.io.File

object AdminConfig {
    private lateinit var config: FileConfiguration
    private lateinit var file: File

    var availableMaps: List<String> = listOf()
    var maxParallelGames: Int = 1
    var maxPlayersPerTeam: Int = 4
    var teamsCount: Int = 4
    var maxPlayers: Int = 16
    var minPlayersPercent: Double = 0.5
    var worldBorderSize: Double = 100.0
    var templateWorldName: String = "cw_template"
    var debugMode: Boolean = false
    var debugMinPlayers: Int = 1

    fun load() {
        val plugin = PluginManager.getPlugin()
        file = File(plugin.dataFolder, "admin-config.yml")

        if (!file.exists()) {
            plugin.saveResource("admin-config.yml", false)
            PluginManager.getLogger().info("Создан admin-config.yml из шаблона")
        }

        config = YamlConfiguration.loadConfiguration(file)

        availableMaps = config.getStringList("maps.available")
        maxParallelGames = config.getInt("games.max-parallel", 1)
        maxPlayersPerTeam = config.getInt("teams.max-players-per-team", 4)
        teamsCount = config.getInt("teams.count", 4)
        maxPlayers = config.getInt("players.max", 16)
        minPlayersPercent = config.getDouble("players.min-percent-to-start", 0.5)
        worldBorderSize = config.getDouble("world.border-size", 100.0)
        templateWorldName = config.getString("world.template-name", "cw_template")!!
        debugMode = config.getBoolean("debug.enabled", false)
        debugMinPlayers = config.getInt("debug.min-players", 1)

        PluginManager.getLogger().info("Административный конфиг загружен!")
        if (debugMode) {
            PluginManager.getLogger().info("§e[DEBUG] Режим отладки включен! Игры будут запускаться с минимальным количеством игроков.")
        }
    }

    fun reload() {
        config = YamlConfiguration.loadConfiguration(file)
        load()
        ArenaManager.setTemplate()
    }

    fun getMinPlayersToStart(): Int {
        return if (debugMode) {
            debugMinPlayers
        } else {
            (maxPlayers * minPlayersPercent).toInt()
        }
    }
}