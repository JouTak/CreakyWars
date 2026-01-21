package ru.joutak.creakywars.config

import org.bukkit.configuration.file.YamlConfiguration
import ru.joutak.creakywars.arenas.ArenaManager
import ru.joutak.creakywars.ceremony.CeremonyPodium
import ru.joutak.creakywars.utils.PluginManager
import java.io.File

object AdminConfig {
    private lateinit var config: YamlConfiguration
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

    // Ceremony
    var ceremonyEnabled: Boolean = true
    var ceremonyTemplateWorldName: String = "cw_ceremony"
    var ceremonyDurationSeconds: Int = 10
    var ceremonyWindCharges: Int = 16
    var ceremonyPodiums: List<CeremonyPodium> = listOf(
        CeremonyPodium(0, 64, 0, 1, 1, 180f),
        CeremonyPodium(3, 64, 0, 4, 1, 180f),
        CeremonyPodium(6, 64, 0, 7, 1, 180f),
        CeremonyPodium(9, 64, 0, 10, 1, 180f),
    )

    var ceremonySpectators: List<CeremonyPodium> = listOf(
        CeremonyPodium(12, 64, 0, 15, 3, 180f),
    )

    fun load() {
        val plugin = PluginManager.getPlugin()
        file = File(plugin.dataFolder, "admin-config.yml")

        if (!file.exists()) {
            plugin.saveResource("admin-config.yml", false)
            PluginManager.getLogger().info("Создан admin-config.yml из шаблона")
        }

        config = YamlConfiguration.loadConfiguration(file)

        var changed = false
        fun ensure(path: String, value: Any) {
            if (!config.contains(path)) {
                config.set(path, value)
                changed = true
            }
        }

        // Backfill missing keys when updating the plugin
        ensure("debug.enabled", false)
        ensure("debug.min-players", 1)
        ensure("maps.available", listOf("game"))
        ensure("games.max-parallel", 10)
        ensure("teams.max-players-per-team", 4)
        ensure("teams.count", 4)
        ensure("players.max", 16)
        ensure("players.min-percent-to-start", 0.125)
        ensure("world.border-size", 261.0)
        ensure("world.template-name", "game")

        ensure("ceremony.enabled", true)
        ensure("ceremony.template-name", "cw_ceremony")
        ensure("ceremony.duration-seconds", 10)
        ensure("ceremony.wind-charges", 16)
        ensure(
            "ceremony.podiums",
            listOf(
                listOf(0, 64, 0, 1, 1, 180),
                listOf(3, 64, 0, 4, 1, 180),
                listOf(6, 64, 0, 7, 1, 180),
                listOf(9, 64, 0, 10, 1, 180),
            ),
        )

        ensure(
            "ceremony.spectators",
            listOf(
                listOf(12, 64, 0, 15, 3, 180),
            ),
        )

        if (changed) {
            try {
                config.save(file)
            } catch (_: Exception) {
            }
        }

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

        ceremonyEnabled = config.getBoolean("ceremony.enabled", ceremonyEnabled)
        ceremonyTemplateWorldName = config.getString("ceremony.template-name", ceremonyTemplateWorldName)!!
        ceremonyDurationSeconds = config.getInt("ceremony.duration-seconds", ceremonyDurationSeconds).coerceAtLeast(1)
        ceremonyWindCharges = config.getInt("ceremony.wind-charges", ceremonyWindCharges).coerceAtLeast(0)

        val podiumsRaw: List<*> = config.getList("ceremony.podiums") ?: emptyList<Any>()
        val parsedPodiums = mutableListOf<CeremonyPodium>()

        val spectatorsRaw: List<*> = config.getList("ceremony.spectators") ?: emptyList<Any>()
        val parsedSpectators = mutableListOf<CeremonyPodium>()

        fun numToInt(v: Any?): Int? = when (v) {
            is Int -> v
            is Long -> v.toInt()
            is Double -> v.toInt()
            is Float -> v.toInt()
            is String -> v.toIntOrNull()
            else -> null
        }

        fun numToFloat(v: Any?): Float? = when (v) {
            is Float -> v
            is Double -> v.toFloat()
            is Int -> v.toFloat()
            is Long -> v.toFloat()
            is String -> v.toFloatOrNull()
            else -> null
        }

        fun parsePodiums(raw: List<*>, out: MutableList<CeremonyPodium>) {
            for (entry in raw) {
                val list = entry as? List<*> ?: continue
                if (list.size < 5) continue
                val minX = numToInt(list.getOrNull(0)) ?: continue
                val y = numToInt(list.getOrNull(1)) ?: continue
                val minZ = numToInt(list.getOrNull(2)) ?: continue
                val maxX = numToInt(list.getOrNull(3)) ?: continue
                val maxZ = numToInt(list.getOrNull(4)) ?: continue
                val yaw = numToFloat(list.getOrNull(5)) ?: 180f
                out.add(CeremonyPodium(minX, y, minZ, maxX, maxZ, yaw))
            }
        }

        parsePodiums(podiumsRaw, parsedPodiums)
        parsePodiums(spectatorsRaw, parsedSpectators)

        if (parsedPodiums.size >= 4) {
            ceremonyPodiums = parsedPodiums.take(4)
        }

        if (parsedSpectators.isNotEmpty()) {
            ceremonySpectators = parsedSpectators
        }

        PluginManager.getLogger().info("Административный конфиг загружен!")
        if (debugMode) {
            PluginManager.getLogger().info("§e[DEBUG] Режим отладки включен! Игры будут запускаться с минимальным количеством игроков.")
        }
    }

    fun reload() {
        config = YamlConfiguration.loadConfiguration(file)
        load()
        ArenaManager.loadTemplate()
    }

    fun getMinPlayersToStart(): Int {
        return if (debugMode) {
            debugMinPlayers
        } else {
            (maxPlayers * minPlayersPercent).toInt()
        }
    }
}
