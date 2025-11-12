package ru.joutak.creakywars.spartakiada

import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import ru.joutak.creakywars.game.Team
import ru.joutak.creakywars.utils.PluginManager
import java.io.File
import java.util.UUID

object SpartakiadaManager {
    private var enabled = false
    private val teamAssignments = mutableMapOf<UUID, Int>()
    private lateinit var config: FileConfiguration
    private lateinit var file: File
    
    fun init() {
        loadConfig()
        PluginManager.getLogger().info("SpartakiadaManager инициализирован! Режим: ${if (enabled) "включен" else "выключен"}")
    }
    
    private fun loadConfig() {
        val plugin = PluginManager.getPlugin()
        file = File(plugin.dataFolder, "spartakiada.yml")
        
        if (!file.exists()) {
            plugin.saveResource("spartakiada.yml", false)
        }
        
        config = YamlConfiguration.loadConfiguration(file)
        
        enabled = config.getBoolean("enabled", false)

        teamAssignments.clear()
        val assignmentsSection = config.getConfigurationSection("assignments")
        
        if (assignmentsSection != null) {
            for (teamId in assignmentsSection.getKeys(false)) {
                val playerNames = assignmentsSection.getStringList(teamId)
                val teamIdInt = teamId.toIntOrNull() ?: continue
                
                for (playerName in playerNames) {
                    val player = org.bukkit.Bukkit.getPlayerExact(playerName)
                    if (player != null) {
                        teamAssignments[player.uniqueId] = teamIdInt
                    }
                }
            }
        }
        
        PluginManager.getLogger().info("Загружено ${teamAssignments.size} назначений команд для спартакиады")
    }
    
    fun reload() {
        config = YamlConfiguration.loadConfiguration(file)
        loadConfig()
    }
    
    fun isEnabled(): Boolean = enabled
    
    fun setEnabled(value: Boolean) {
        enabled = value
        config.set("enabled", value)
        saveConfig()
    }
    
    fun getTeamAssignment(player: Player): Int? {
        return teamAssignments[player.uniqueId]
    }
    
    fun assignPlayerToTeam(player: Player, teamId: Int) {
        teamAssignments[player.uniqueId] = teamId
        saveAssignments()
    }
    
    fun removePlayerAssignment(player: Player) {
        teamAssignments.remove(player.uniqueId)
        saveAssignments()
    }
    
    private fun saveAssignments() {
        val teamGroups = mutableMapOf<Int, MutableList<String>>()
        
        for ((uuid, teamId) in teamAssignments) {
            val player = org.bukkit.Bukkit.getPlayer(uuid)
            if (player != null) {
                teamGroups.getOrPut(teamId) { mutableListOf() }.add(player.name)
            }
        }

        config.set("assignments", null)
        for ((teamId, playerNames) in teamGroups) {
            config.set("assignments.$teamId", playerNames)
        }
        
        saveConfig()
    }
    
    private fun saveConfig() {
        try {
            config.save(file)
        } catch (e: Exception) {
            PluginManager.getLogger().severe("Не удалось сохранить spartakiada.yml: ${e.message}")
        }
    }
}