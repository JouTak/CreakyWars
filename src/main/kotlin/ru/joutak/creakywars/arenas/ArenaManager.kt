package ru.joutak.creakywars.arenas

import com.onarandombox.MultiverseCore.MultiverseCore
import org.bukkit.*
import ru.joutak.creakywars.config.AdminConfig
import ru.joutak.creakywars.config.MapConfig
import ru.joutak.creakywars.utils.PluginManager
import java.io.File

object ArenaManager {
    private val arenas = mutableMapOf<String, Arena>()
    private var nextArenaId = 1
    private var template: World? = null
    private lateinit var multiverseCore: MultiverseCore

    fun init() {
        val mvPlugin = Bukkit.getPluginManager().getPlugin("Multiverse-Core")
        if (mvPlugin == null || mvPlugin !is MultiverseCore) {
            PluginManager.getLogger().severe("Multiverse-Core не найден! Плагин не может работать без него.")
            return
        }
        multiverseCore = mvPlugin

        deleteExistingArenas()
        setTemplate()
        PluginManager.getLogger().info("✓ ArenaManager инициализирован!")
    }

    fun setTemplate() {
        val templateName = AdminConfig.templateWorldName
        template = Bukkit.getWorld(templateName)

        if (template == null) {
            PluginManager.getLogger().severe(
                "Отсутствует мир $templateName! Проверьте наличие мира с ареной и укажите верное название."
            )
        } else {
            configureWorld(template!!)
            PluginManager.getLogger().info("✓ Шаблонный мир '$templateName' готов к использованию!")
        }
    }

    fun getArenas(): Collection<Arena> = arenas.values

    fun getArena(worldName: String): Arena? = arenas[worldName]

    fun getArena(world: World): Arena? = arenas[world.name]

    fun isArena(world: World): Boolean = arenas.containsKey(world.name)

    private fun configureWorld(world: World) {
        world.difficulty = Difficulty.NORMAL
        world.setSpawnFlags(false, false)
        world.pvp = true
        world.setStorm(false)
        world.isThundering = false
        world.weatherDuration = 0
        world.time = 6000

        world.setGameRule(GameRule.FALL_DAMAGE, true)
        world.setGameRule(GameRule.FIRE_DAMAGE, true)
        world.setGameRule(GameRule.DROWNING_DAMAGE, true)
        world.setGameRule(GameRule.FREEZE_DAMAGE, true)
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false)
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false)
        world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false)
        world.setGameRule(GameRule.KEEP_INVENTORY, false)
        world.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true)
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false)
        world.setGameRule(GameRule.NATURAL_REGENERATION, true)
        world.setGameRule(GameRule.DO_FIRE_TICK, false)

        world.worldBorder.size = AdminConfig.worldBorderSize
        world.worldBorder.center = Location(world, 0.0, 64.0, 0.0)
    }

    fun createArena(mapName: String): Arena {
        if (template == null) {
            throw NullPointerException("Шаблонный мир не загружен! Создайте мир ${AdminConfig.templateWorldName}")
        }

        val worldName = "cw_arena_${nextArenaId}"

        if (!multiverseCore.mvWorldManager.cloneWorld(template!!.name, worldName)) {
            throw Exception("Не удалось склонировать мир! Проверьте логи плагина Multiverse.")
        }

        val world = Bukkit.getWorld(worldName)
            ?: throw Exception("Не удалось загрузить склонированный мир $worldName")

        configureWorld(world)

        val mapConfig = MapConfig.load(mapName)
        val arena = Arena(nextArenaId, world, mapConfig)

        arenas[worldName] = arena
        nextArenaId++

        PluginManager.getLogger().info("✓ Создана арена #${arena.id} с картой $mapName")
        return arena
    }

    fun deleteArena(arena: Arena) {
        deleteArena(arena.worldName)
    }

    fun deleteArena(worldName: String) {
        val mvWorldManager = multiverseCore.mvWorldManager
        if (mvWorldManager.isMVWorld(worldName)) {
            mvWorldManager.deleteWorld(worldName)
        } else {
            Bukkit.getScheduler().runTaskLater(
                PluginManager.getPlugin(),
                Runnable {
                    if (deleteWorldFolder(worldName)) {
                        PluginManager.getLogger().info("✓ Удаление арены $worldName прошло успешно!")
                    } else {
                        PluginManager.getLogger().warning("⚠ Не удалось удалить папку с ареной $worldName!")
                    }
                },
                20L
            )
        }
        arenas.remove(worldName)
    }

    fun deleteAllArenas() {
        arenas.keys.toList().forEach { deleteArena(it) }
    }

    private fun deleteExistingArenas() {
        val worldContainer = Bukkit.getWorldContainer()
        val arenaDirs = worldContainer.listFiles { file ->
            file.isDirectory && file.name.startsWith("cw_arena_")
        }?.filterNotNull() ?: return

        for (dir in arenaDirs) {
            val name = dir.name
            val mvWorldManager = multiverseCore.mvWorldManager
            if (mvWorldManager.isMVWorld(name)) {
                mvWorldManager.deleteWorld(name)
            } else {
                deleteWorldFolder(name)
            }
        }
    }

    private fun deleteWorldFolder(worldName: String): Boolean {
        val worldFolder = File(Bukkit.getWorldContainer(), worldName)
        if (!worldFolder.exists()) return true
        return worldFolder.deleteRecursively()
    }
}