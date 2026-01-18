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

        val arenaId = nextArenaId++
        val worldName = "cw_game_${mapConfigName}_${arenaId}"

        if (!multiverseCore.mvWorldManager.cloneWorld(templateWorld.name, worldName)) {
            throw IllegalStateException("Failed to clone world $worldName")
        }

        val world = Bukkit.getWorld(worldName) ?: throw IllegalStateException("World $worldName is null after clone")
        configureWorldRules(world)

        val mapConfig = MapConfig.load(mapConfigName)

        val arena = Arena(arenaId, world, mapConfig)
        arenas[worldName] = arena
        return arena
    }



/**
 * Clone any multiverse world and apply our default gamerules/border settings.
 * Used for ceremony worlds as well.
 */
fun cloneWorld(templateWorldName: String, newWorldName: String): World {
    val templateWorld = Bukkit.getWorld(templateWorldName)
        ?: throw IllegalStateException("Template world '$templateWorldName' not found")

    if (!multiverseCore.mvWorldManager.cloneWorld(templateWorld.name, newWorldName)) {
        throw IllegalStateException("Failed to clone world $newWorldName")
    }

    val world = Bukkit.getWorld(newWorldName)
        ?: throw IllegalStateException("World $newWorldName is null after clone")

    configureWorldRules(world)
    return world
}

fun deleteWorldByName(worldName: String) {
    try {
        multiverseCore.mvWorldManager.deleteWorld(worldName, true, true)
    } catch (_: Exception) {
    }

    try {
        File(Bukkit.getWorldContainer(), worldName).deleteRecursively()
    } catch (_: Exception) {
    }

    arenas.remove(worldName)
}
    fun deleteArena(arena: Arena) {
        val worldName = arena.worldName
        multiverseCore.mvWorldManager.deleteWorld(worldName, true, true)
        File(Bukkit.getWorldContainer(), worldName).deleteRecursively()
        arenas.remove(worldName)
    }

    fun deleteAllArenas() {
        arenas.values.toList().forEach { deleteArena(it) }
    }

    /**
     * Remove orphaned cloned worlds (cw_game_*) that are NOT currently used by running games.
     * Useful after crashes/reloads, to prevent Multiverse/world-container from accumulating garbage.
     */
    fun cleanupOrphans(activeWorlds: Set<String>): Int {
        if (!::multiverseCore.isInitialized) return 0

        var deleted = 0

        val mvWorldsToDelete = multiverseCore.mvWorldManager.mvWorlds
            .filter { (it.name.startsWith("cw_game_") || it.name.startsWith("cw_ceremony_")) && !activeWorlds.contains(it.name) }
            .map { it.name }
            .toSet()

        mvWorldsToDelete.forEach { worldName ->
            try {
                if (multiverseCore.mvWorldManager.deleteWorld(worldName, true, true)) {
                    deleted++
                }
            } catch (_: Exception) {
            }

            try {
                File(Bukkit.getWorldContainer(), worldName).deleteRecursively()
            } catch (_: Exception) {
            }
        }

        // Also remove folders without a registered mv-world (rare, but happens after hard crashes)
        val container = Bukkit.getWorldContainer()
        container.listFiles { f ->
            f.isDirectory && (f.name.startsWith("cw_game_") || f.name.startsWith("cw_ceremony_")) && !activeWorlds.contains(f.name)
        }?.forEach { dir ->
            try {
                if (dir.deleteRecursively()) {
                    deleted++
                }
            } catch (_: Exception) {
            }
        }

        return deleted
    }

    private fun configureWorldRules(world: World) {
        world.difficulty = Difficulty.NORMAL
        world.setSpawnFlags(false, false)
        world.pvp = true
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false)
        world.setGameRule(GameRule.KEEP_INVENTORY, false)
        world.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true)
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false)

        val border = world.worldBorder
        border.size = AdminConfig.worldBorderSize

        // В ваниле у WorldBorder есть урон за пределами границы.
        // У нас это должно быть включено, иначе игроки могут "пересидеть" сужение за границей.
        border.damageBuffer = 0.0
        border.damageAmount = 2.0
    }

    private fun deleteExistingArenas() {
        val mvWorldsToDelete = multiverseCore.mvWorldManager.mvWorlds
            .filter { it.name.startsWith("cw_game_") || it.name.startsWith("cw_ceremony_") }
            .map { it.name }
            .toSet()

        if (mvWorldsToDelete.isNotEmpty()) {
            PluginManager.getLogger().info("Обнаружено ${mvWorldsToDelete.size} старых игровых арен для удаления через Multiverse...")
        }

        mvWorldsToDelete.forEach { worldName ->
            PluginManager.getLogger().info("Удаление старой арены: $worldName")
            if (multiverseCore.mvWorldManager.deleteWorld(worldName, true, true)) {
                PluginManager.getLogger().info("Успешно удалена арена $worldName.")
            } else {
                PluginManager.getLogger().warning("Не удалось удалить мир '$worldName' через Multiverse-Core. Попытка удалить папку вручную.")
            }
        }

        val worldContainer = Bukkit.getWorldContainer()
        worldContainer.listFiles { f -> f.isDirectory && (f.name.startsWith("cw_game_") || f.name.startsWith("cw_ceremony_")) }?.forEach {
            PluginManager.getLogger().info("Удаление оставшейся папки: ${it.name}")
            it.deleteRecursively()
        }

        PluginManager.getLogger().info("Очистка старых арен завершена. Сброс счетчика ID.")

        nextArenaId = 1
    }
}