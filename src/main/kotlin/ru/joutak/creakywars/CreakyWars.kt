@file:Suppress("DEPRECATION")

package ru.joutak.creakywars

import org.bukkit.plugin.java.JavaPlugin
import ru.joutak.creakywars.arenas.ArenaManager
import ru.joutak.creakywars.commands.CreakyCommands
import ru.joutak.creakywars.config.AdminConfig
import ru.joutak.creakywars.config.GameConfig
import ru.joutak.creakywars.config.MapConfig
import ru.joutak.creakywars.config.ScenarioConfig
import ru.joutak.creakywars.core.CoreManager
import ru.joutak.creakywars.game.GameManager
import ru.joutak.creakywars.gui.GuiManager
import ru.joutak.creakywars.listeners.*
import ru.joutak.creakywars.queue.QueueManager
import ru.joutak.creakywars.resources.ResourceSpawner
import ru.joutak.creakywars.spartakiada.SpartakiadaManager
import ru.joutak.creakywars.trading.TraderManager
import ru.joutak.creakywars.upgrades.UpgradeListener
import ru.joutak.creakywars.utils.PluginManager

class CreakyWars : JavaPlugin() {

    override fun onEnable() {
        PluginManager.init(this)

        if (!dataFolder.exists()) {
            dataFolder.mkdirs()
            logger.info("✓ Создана директория плагина: ${dataFolder.path}")
        }

        logger.info("========================================")
        logger.info("  Загрузка конфигурационных файлов...")
        logger.info("========================================")

        try {
            AdminConfig.load()
            GameConfig.load()
            ScenarioConfig.load()
            MapConfig.init()
            logger.info("✓ Все конфиги загружены успешно!")
        } catch (e: Exception) {
            logger.severe("✗ Ошибка при загрузке конфигов: ${e.message}")
            e.printStackTrace()
            server.pluginManager.disablePlugin(this)
            return
        }

        logger.info("========================================")
        logger.info("  Инициализация игровых систем...")
        logger.info("========================================")

        try {
            ArenaManager.init()
            GameManager.init()
            QueueManager.init()
            CoreManager.init()
            ResourceSpawner.init()
            TraderManager.init()
            SpartakiadaManager.init()
            GuiManager.init()
            logger.info("✓ Все системы инициализированы!")
        } catch (e: Exception) {
            logger.severe("✗ Ошибка при инициализации систем: ${e.message}")
            e.printStackTrace()
            server.pluginManager.disablePlugin(this)
            return
        }

        getCommand("cw")?.setExecutor(CreakyCommands())
            ?: logger.warning("⚠ Не удалось зарегистрировать команду /cw")

        logger.info("========================================")
        logger.info("  Регистрация event listeners...")
        logger.info("========================================")

        val pm = server.pluginManager
        pm.registerEvents(PlayerListener(), this)
        pm.registerEvents(GameListener(), this)
        pm.registerEvents(ExplosivesListener(), this)
        pm.registerEvents(EyeblossomListener(), this)
        pm.registerEvents(TraderListener(), this)
        pm.registerEvents(CreakingListener(), this)
        pm.registerEvents(UpgradeListener(), this)
        logger.info("✓ Все слушатели зарегистрированы!")

        // === ФИНАЛЬНОЕ СООБЩЕНИЕ ===
        logger.info("========================================")
        logger.info("  CreakyWars v${description.version} запущен!")
        logger.info("  Автор: ${description.authors.joinToString(", ")}")
        logger.info("  Доступных карт: ${MapConfig.getAllMapNames().size}")
        logger.info("========================================")
    }

    override fun onDisable() {
        logger.info("========================================")
        logger.info("  Выключение CreakyWars...")
        logger.info("========================================")

        try {
            GameManager.shutdownAll()
            logger.info("✓ Все игры остановлены")
            ArenaManager.deleteAllArenas()
            logger.info("✓ Все арены очищены")
        } catch (e: Exception) {
            logger.severe("✗ Ошибка при выключении: ${e.message}")
            e.printStackTrace()
        }

        logger.info("========================================")
        logger.info("  CreakyWars выключен!")
        logger.info("========================================")
    }
}