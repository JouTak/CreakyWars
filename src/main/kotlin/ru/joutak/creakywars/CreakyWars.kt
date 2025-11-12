package ru.joutak.creakywars

import org.bukkit.plugin.java.JavaPlugin
import ru.joutak.creakywars.arenas.ArenaManager
import ru.joutak.creakywars.commands.CreakyCommands
import ru.joutak.creakywars.config.AdminConfig
import ru.joutak.creakywars.config.GameConfig
import ru.joutak.creakywars.config.ScenarioConfig
import ru.joutak.creakywars.core.CoreManager
import ru.joutak.creakywars.core.RespawnSystem
import ru.joutak.creakywars.game.GameManager
import ru.joutak.creakywars.gui.GuiManager
import ru.joutak.creakywars.listeners.*
import ru.joutak.creakywars.queue.QueueManager
import ru.joutak.creakywars.resources.ResourceSpawner
import ru.joutak.creakywars.spartakiada.SpartakiadaManager
import ru.joutak.creakywars.trading.TraderManager
import ru.joutak.creakywars.utils.PluginManager

class CreakyWars : JavaPlugin() {

    override fun onEnable() {
        PluginManager.init(this)
        if (!dataFolder.exists()) {
            dataFolder.mkdirs()
        }

        AdminConfig.load()
        GameConfig.load()
        ScenarioConfig.load()

        ArenaManager.init()
        GameManager.init()
        QueueManager.init()
        CoreManager.init()
        ResourceSpawner.init()
        TraderManager.init()
        SpartakiadaManager.init()
        GuiManager.init()

        getCommand("cw")?.setExecutor(CreakyCommands())

        server.pluginManager.registerEvents(PlayerListener(), this)
        server.pluginManager.registerEvents(GameListener(), this)
        server.pluginManager.registerEvents(CoreListener(), this)
        server.pluginManager.registerEvents(RespawnSystem(), this)
        server.pluginManager.registerEvents(EyeblossomListener(), this)
        server.pluginManager.registerEvents(TraderListener(), this)

        logger.info("CreakyWars успешно загружен!")
    }

    override fun onDisable() {
        GameManager.shutdownAll()
        ArenaManager.deleteAllArenas()

        logger.info("CreakyWars выключен!")
    }
}