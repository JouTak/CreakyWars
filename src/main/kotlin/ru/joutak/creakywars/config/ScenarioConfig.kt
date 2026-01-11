package ru.joutak.creakywars.config

import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import ru.joutak.creakywars.game.GamePhase
import ru.joutak.creakywars.utils.PluginManager
import java.io.File

object ScenarioConfig {
    private lateinit var config: FileConfiguration
    private lateinit var file: File

    val phases = mutableListOf<GamePhase>()

    fun load() {
        val plugin = PluginManager.getPlugin()
        file = File(plugin.dataFolder, "scenario-config.yml")

        if (!file.exists()) {
            plugin.saveResource("scenario-config.yml", false)
        }

        config = YamlConfiguration.loadConfiguration(file)

        loadPhases()

        PluginManager.getLogger().info("Конфиг сценариев загружен!")
    }

    private fun loadPhases() {
        phases.clear()

        val phasesSection = config.getConfigurationSection("phases") ?: return

        for (key in phasesSection.getKeys(false)) {
            val section = phasesSection.getConfigurationSection(key) ?: continue

            val name = section.getString("name", key)!!

            // Legacy duration (seconds)
            val durationSeconds = section.getLong("duration", 600L)

            // New: absolute tick from match start when the phase ends.
            val endAtTick: Long? = if (section.contains("end-at-tick")) {
                section.getLong("end-at-tick")
            } else {
                null
            }

            val resourceMultiplier = section.getDouble("resource-multiplier", 1.0)
            val respawnEnabled = section.getBoolean("respawn-enabled", true)
            val borderShrink = section.getBoolean("border-shrink", false)
            val borderShrinkSpeed = section.getDouble("border-shrink-speed", 0.1)
            val borderFinalSize = section.getDouble("border-final-size", 20.0)

            // New: Creaking buffs
            val creakingSpeedAmplifier = section.getInt("creaking-speed-amplifier", 0)

            // New: Glow all players on this phase
            val glowPlayers = section.getBoolean("glow-players", false)

            val startMessage = section.getString("start-message", "")!!
            val endMessage = section.getString("end-message", "")!!

            phases.add(
                GamePhase(
                    name = name,
                    durationSeconds = durationSeconds,
                    endAtTick = endAtTick,
                    resourceMultiplier = resourceMultiplier,
                    respawnEnabled = respawnEnabled,
                    borderShrink = borderShrink,
                    borderShrinkSpeed = borderShrinkSpeed,
                    borderFinalSize = borderFinalSize,
                    creakingSpeedAmplifier = creakingSpeedAmplifier,
                    glowPlayers = glowPlayers,
                    startMessage = startMessage,
                    endMessage = endMessage
                )
            )
        }

        // Keep existing behavior: phases are expected to have names like "Фаза 1", "Фаза 2"...
        phases.sortBy { it.name }
    }

    fun reload() {
        config = YamlConfiguration.loadConfiguration(file)
        load()
    }
}
