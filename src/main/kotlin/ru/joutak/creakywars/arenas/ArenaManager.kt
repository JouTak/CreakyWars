package ru.joutak.creakywars.arenas

import com.onarandombox.MultiverseCore.MultiverseCore
import org.bukkit.*
import ru.joutak.creakywars.config.AdminConfig
import ru.joutak.creakywars.config.MapConfig
import ru.joutak.creakywars.utils.PluginManager
import ru.joutak.minigames.domain.GameInstanceConfig
import ru.joutak.minigames.managers.MatchmakingManager
import java.io.File

object ArenaManager {
    private val arenas = mutableMapOf<String, Arena>()
    private var nextArenaId = 1
    private lateinit var multiverseCore: MultiverseCore
    private var template: World? = null

    fun init() {
        val mvPlugin = Bukkit.getPluginManager().getPlugin("Multiverse-Core") as? MultiverseCore
        if (mvPlugin == null) {
            PluginManager.getLogger().severe("Multiverse-Core не найден!")
            return
        }
        multiverseCore = mvPlugin
        deleteExistingArenas()

        loadTemplate()
    }

    fun loadTemplate() {
        val templateName = AdminConfig.templateWorldName
        template = Bukkit.getWorld(templateName)
        if (template == null) {
            PluginManager.getLogger().warning("Шаблонный мир $templateName не найден! Проверьте admin-config.yml.")
        } else {
            configureWorldRules(template!!)
            PluginManager.getLogger().info("Шаблонный мир '$templateName' успешно загружен/обновлен.")
        }
    }

    fun isArena(world: World): Boolean {
        return arenas.containsKey(world.name)
    }

    fun registerArenasToApi() {
        val availableMaps = MapConfig.getAllMapNames()
        val apiConfigs = mutableListOf<GameInstanceConfig>()

        for (mapName in availableMaps) {
            apiConfigs.add(
                GameInstanceConfig(
                    id = mapName,
                    teamCount = AdminConfig.teamsCount,
                    playersPerTeam = AdminConfig.maxPlayers / AdminConfig.teamsCount,
                    meta = mapOf(
                        "mapName" to mapName,
                        "templateWorld" to AdminConfig.templateWorldName
                    )
                )
            )
        }
        MatchmakingManager.loadInstances(apiConfigs)
        PluginManager.getLogger().info("Загружено ${apiConfigs.size} карт в систему матчмейкинга.")
    }

    fun createPhysicalArena(mapConfigName: String): Arena {
        if (template == null) template = Bukkit.getWorld(AdminConfig.templateWorldName)
        val templateWorld = template ?: throw IllegalStateException("Template world not found")

        val worldName = "cw_game_${mapConfigName}_${nextArenaId++}"

        if (!multiverseCore.mvWorldManager.cloneWorld(templateWorld.name, worldName)) {
            throw IllegalStateException("Failed to clone world $worldName")
        }

        val world = Bukkit.getWorld(worldName) ?: throw IllegalStateException("World $worldName is null after clone")
        configureWorldRules(world)

        val mapConfig = MapConfig.load(mapConfigName)

        val arena = Arena(nextArenaId, world, mapConfig)
        arenas[worldName] = arena
        return arena
    }

    fun deleteArena(arena: Arena) {
        val worldName = arena.worldName
        multiverseCore.mvWorldManager.deleteWorld(worldName)
        File(Bukkit.getWorldContainer(), worldName).deleteRecursively()
        arenas.remove(worldName)
    }

    fun deleteAllArenas() {
        arenas.values.toList().forEach { deleteArena(it) }
    }

    private fun configureWorldRules(world: World) {
        world.difficulty = Difficulty.NORMAL
        world.setSpawnFlags(false, false)
        world.pvp = true
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false)
        world.setGameRule(GameRule.KEEP_INVENTORY, false)
        world.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true)
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false)
        world.worldBorder.size = AdminConfig.worldBorderSize
    }

    private fun deleteExistingArenas() {
        val worldContainer = Bukkit.getWorldContainer()
        worldContainer.listFiles { f -> f.isDirectory && f.name.startsWith("cw_game_") }?.forEach {
            it.deleteRecursively()
        }
    }
}