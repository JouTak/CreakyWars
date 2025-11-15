package ru.joutak.creakywars.spartakiada

import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import ru.joutak.creakywars.utils.PluginManager
import java.io.File

object SpartakiadaManager {
    private var enabled = false
    private val teamAssignmentsByName = mutableMapOf<String, Int>()
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

        teamAssignmentsByName.clear()
        val assignmentsSection = config.getConfigurationSection("assignments")

        if (assignmentsSection != null) {
            for (teamId in assignmentsSection.getKeys(false)) {
                val playerNames = assignmentsSection.getStringList(teamId)
                val teamIdInt = teamId.toIntOrNull() ?: continue

                for (playerName in playerNames) {
                    teamAssignmentsByName[playerName.lowercase()] = teamIdInt
                }
            }
        }

        PluginManager.getLogger().info("Загружено ${teamAssignmentsByName.size} назначений команд для спартакиады")
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
        return teamAssignmentsByName[player.name.lowercase()]
    }

    fun getPlayerTeam(player: Player): String? {
        val teamId = getTeamAssignment(player)
        return teamId?.toString()
    }

    fun getTeamPlayers(teamName: String): List<Player> {
        val teamId = teamName.toIntOrNull() ?: return emptyList()

        return org.bukkit.Bukkit.getOnlinePlayers().filter { player ->
            getTeamAssignment(player) == teamId
        }
    }

    fun isWholeTeamOnline(teamId: Int): Boolean {
        val allTeamPlayerNames = teamAssignmentsByName.filterValues { it == teamId }.keys

        if (allTeamPlayerNames.isEmpty()) {
            PluginManager.getLogger().warning("Команда $teamId не найдена в конфиге!")
            return false
        }

        val onlineCount = allTeamPlayerNames.count { playerName ->
            org.bukkit.Bukkit.getPlayerExact(playerName)?.isOnline == true
        }

        val isAllOnline = onlineCount == allTeamPlayerNames.size

        if (!isAllOnline) {
            PluginManager.getLogger().info(
                "Команда $teamId: $onlineCount/${allTeamPlayerNames.size} игроков онлайн"
            )
        }

        return isAllOnline
    }

    fun getTeamSize(teamId: Int): Int {
        return teamAssignmentsByName.count { it.value == teamId }
    }

    fun getOnlineTeamSize(teamId: Int): Int {
        val teamPlayerNames = teamAssignmentsByName.filterValues { it == teamId }.keys

        return teamPlayerNames.count { playerName ->
            org.bukkit.Bukkit.getPlayerExact(playerName)?.isOnline == true
        }
    }

    fun getTeamDisplayName(teamId: Int): String {
        val customName = config.getString("team-names.$teamId")
        return customName ?: "Команда $teamId"
    }

    fun assignPlayerToTeam(player: Player, teamId: Int) {
        teamAssignmentsByName[player.name.lowercase()] = teamId
        saveAssignments()
    }

    fun removePlayerAssignment(player: Player) {
        teamAssignmentsByName.remove(player.name.lowercase())
        saveAssignments()
    }

    private fun saveAssignments() {
        val teamGroups = mutableMapOf<Int, MutableList<String>>()

        for ((playerName, teamId) in teamAssignmentsByName) {
            teamGroups.getOrPut(teamId) { mutableListOf() }.add(playerName)
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

    fun getAllTeamPlayerNames(teamId: Int): List<String> {
        return teamAssignmentsByName.filterValues { it == teamId }.keys.toList()
    }
}