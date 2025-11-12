package ru.joutak.creakywars.arenas

import org.bukkit.Bukkit
import org.bukkit.Difficulty
import org.bukkit.GameRule
import org.bukkit.World
import ru.joutak.creakywars.config.AdminConfig
import ru.joutak.creakywars.config.MapConfig
import ru.joutak.creakywars.utils.PluginManager
import java.io.File

object ArenaManager {
    private val arenas = mutableListOf<Arena>()
    private var nextArenaId = 1
    private var template: World? = null

    fun init() {
        deleteExistingArenas()
        loadTemplate()
        PluginManager.getLogger().info("ArenaManager инициализирован!")
    }

    fun loadTemplate() {
        val templateName = AdminConfig.templateWorldName
        template = Bukkit.getWorld(templateName)

        if (template == null) {
            PluginManager.getLogger().warning("Шаблонный мир '$templateName' не найден! Попытка создать...")

            val created = Bukkit.getServer().dispatchCommand(
                Bukkit.getConsoleSender(),
                "mv create $templateName normal -t flat"
            )

            if (created) {
                Thread.sleep(2000)
                template = Bukkit.getWorld(templateName)
            }

            if (template == null) {
                PluginManager.getLogger().warning("§e╔════════════════════════════════════════════════╗")
                PluginManager.getLogger().warning("§e║ Не удалось создать шаблонный мир автоматически║")
                PluginManager.getLogger().warning("§e║                                                ║")
                PluginManager.getLogger().warning("§e║ Создайте командой:                            ║")
                PluginManager.getLogger().warning("§e║ §f/mv create $templateName normal -t flat§e      ║")
                PluginManager.getLogger().warning("§e║                                                ║")
                PluginManager.getLogger().warning("§e║ После создания:                               ║")
                PluginManager.getLogger().warning("§e║ §f/cw reload§e                                   ║")
                PluginManager.getLogger().warning("§e╚════════════════════════════════════════════════╝")
                return
            }
        }

        configureWorld(template!!)
        setupTemplateStructure(template!!)
        PluginManager.getLogger().info("§a✓ Шаблонный мир '$templateName' готов к использованию!")
    }

    fun getArenas(): List<Arena> = arenas.toList()

    fun getAvailableArena(): Arena? = arenas.firstOrNull { it.isAvailable() }

    fun getArena(world: World): Arena? = arenas.firstOrNull { it.world == world }

    fun getArena(id: Int): Arena? = arenas.firstOrNull { it.id == id }

    fun isArena(world: World): Boolean = arenas.any { it.world == world }

    private fun setupTemplateStructure(world: World) {
        val spawnLocation = world.getBlockAt(0, 65, 0).location
        world.setSpawnLocation(spawnLocation)

        if (AdminConfig.debugMode) {
            createTestPlatforms(world)
        }
    }

    private fun createTestPlatforms(world: World) {
        val platformLocations = listOf(
            Pair(40, 0), Pair(-40, 0), Pair(0, 40), Pair(0, -40)
        )

        val platformMaterial = org.bukkit.Material.STONE

        // Платформы команд
        for ((x, z) in platformLocations) {
2
            for (dx in -5..5) {
                for (dz in -5..5) {
                    val block = world.getBlockAt(x + dx, 64, z + dz)
                    if (block.type == org.bukkit.Material.AIR) {
                        block.type = platformMaterial
                    }
                }
            }
        }
        for (x in -10..10) {
            for (z in -10..10) {
                val block = world.getBlockAt(x, 60, z)
                if (block.type == org.bukkit.Material.AIR) {
                    block.type = org.bukkit.Material.STONE
                }
            }
        }

        PluginManager.getLogger().info("§a✓ Созданы тестовые платформы (4 базы + центр 20x20)")
    }

    fun createArena(mapName: String): Arena {
        if (template == null) {
            throw NullPointerException("Шаблонный мир не загружен! Выполните: /mv create ${AdminConfig.templateWorldName} normal -t flat, затем /cw reload")
        }

        val worldName = "cw_arena_${nextArenaId}"

        val existingWorldFolder = File(Bukkit.getWorldContainer(), worldName)
        if (existingWorldFolder.exists()) {
            PluginManager.getLogger().warning("Мир $worldName уже существует! Удаляем...")
            deleteWorldSync(worldName)
            Thread.sleep(2000)
        }

        val success = cloneWorldWithMultiverse(template!!.name, worldName)
        if (!success) {
            throw Exception("Не удалось склонировать мир через Multiverse!")
        }

        var world = Bukkit.getWorld(worldName)
        var attempts = 0
        while (world == null && attempts < 50) {
            Thread.sleep(100)
            world = Bukkit.getWorld(worldName)
            attempts++
        }

        if (world == null) {
            throw Exception("Не удалось загрузить скопированный мир!")
        }

        configureWorld(world)

        val mapConfig = MapConfig.load(mapName)
        val arena = Arena(nextArenaId, world, mapConfig)

        arenas.add(arena)
        nextArenaId++

        PluginManager.getLogger().info("Создана арена #${arena.id} с картой $mapName")
        return arena
    }

    private fun cloneWorldWithMultiverse(sourceWorldName: String, targetWorldName: String): Boolean {
        return try {
            PluginManager.getLogger().info("Клонирование мира $sourceWorldName -> $targetWorldName через Multiverse...")

            val success = Bukkit.getServer().dispatchCommand(
                Bukkit.getConsoleSender(),
                "mv clone $sourceWorldName $targetWorldName"
            )

            if (success) {
                PluginManager.getLogger().info("§aМир успешно склонирован!")
            } else {
                PluginManager.getLogger().warning("§eКоманда клонирования вернула false, но мир может быть создан")
            }

            true
        } catch (e: Exception) {
            PluginManager.getLogger().severe("§cОшибка при клонировании мира: ${e.message}")
            e.printStackTrace()
            false
        }
    }

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

        world.worldBorder.size = AdminConfig.worldBorderSize
        world.worldBorder.center = world.spawnLocation
    }

    fun deleteArena(arena: Arena) {
        arenas.remove(arena)
        deleteWorld(arena.worldName)
    }

    fun deleteAllArenas() {
        arenas.toList().forEach { deleteArena(it) }
    }

    private fun deleteExistingArenas() {
        val worldContainer = Bukkit.getWorldContainer()
        val arenaDirs = worldContainer.listFiles { file ->
            file.isDirectory && file.name.startsWith("cw_arena_")
        }?.filterNotNull() ?: return

        for (dir in arenaDirs) {
            deleteWorld(dir.name)
        }
    }

    private fun deleteWorldSync(worldName: String) {
        try {
            val world = Bukkit.getWorld(worldName)
            world?.players?.forEach { player ->
                player.teleport(Bukkit.getWorlds().first().spawnLocation)
            }

            val removed = Bukkit.getServer().dispatchCommand(
                Bukkit.getConsoleSender(),
                "mv remove $worldName"
            )

            if (removed) {
                PluginManager.getLogger().info("Мир $worldName выгружен через Multiverse")
            }

            Thread.sleep(500)

            val worldFolder = File(Bukkit.getWorldContainer(), worldName)
            if (worldFolder.exists()) {
                worldFolder.deleteRecursively()
                PluginManager.getLogger().info("Папка мира $worldName удалена")
            }
        } catch (e: Exception) {
            PluginManager.getLogger().severe("Ошибка при синхронном удалении мира $worldName: ${e.message}")
        }
    }

    private fun deleteWorld(worldName: String) {
        try {
            val world = Bukkit.getWorld(worldName)
            world?.players?.forEach { player ->
                player.teleport(Bukkit.getWorlds().first().spawnLocation)
            }

            Bukkit.getScheduler().runTaskLater(
                PluginManager.getPlugin(),
                Runnable {
                    val removed = Bukkit.getServer().dispatchCommand(
                        Bukkit.getConsoleSender(),
                        "mv remove $worldName"
                    )

                    if (removed) {
                        PluginManager.getLogger().info("Мир $worldName успешно удален")
                    }

                    Bukkit.getScheduler().runTaskLater(
                        PluginManager.getPlugin(),
                        Runnable {
                            deleteWorldFolder(worldName)
                        },
                        40L
                    )
                },
                20L
            )
        } catch (e: Exception) {
            PluginManager.getLogger().severe("Ошибка при удалении мира $worldName: ${e.message}")
        }
    }

    private fun deleteWorldFolder(worldName: String) {
        val worldFolder = File(Bukkit.getWorldContainer(), worldName)
        if (!worldFolder.exists()) return

        var attempts = 0
        while (worldFolder.exists() && attempts < 5) {
            try {
                Thread.sleep(1000)
                if (worldFolder.deleteRecursively()) {
                    PluginManager.getLogger().info("Папка мира $worldName удалена")
                    break
                }
                attempts++
            } catch (e: Exception) {
                attempts++
                if (attempts >= 5) {
                    PluginManager.getLogger().warning("Не удалось удалить папку $worldName после $attempts попыток")
                }
            }
        }
    }
}