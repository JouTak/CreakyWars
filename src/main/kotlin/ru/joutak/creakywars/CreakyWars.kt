package ru.joutak.creakywars

import org.bukkit.plugin.java.JavaPlugin
import ru.joutak.creakywars.arenas.ArenaManager
import ru.joutak.creakywars.ceremony.CeremonyController
import ru.joutak.creakywars.commands.CreakyCommands
import ru.joutak.creakywars.config.AdminConfig
import ru.joutak.creakywars.config.GameConfig
import ru.joutak.creakywars.config.MapConfig
import ru.joutak.creakywars.config.ScenarioConfig
import ru.joutak.creakywars.core.CoreManager
import ru.joutak.creakywars.game.GameManager
import ru.joutak.creakywars.listeners.*
import ru.joutak.creakywars.resources.ResourceSpawner
import ru.joutak.creakywars.trading.TraderManager
import ru.joutak.creakywars.upgrades.UpgradeListener
import ru.joutak.creakywars.utils.PluginManager
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.managers.MatchmakingManager

class CreakyWars : JavaPlugin() {

    override fun onEnable() {
        PluginManager.init(this)

        if (!dataFolder.exists()) dataFolder.mkdirs()

        MiniGamesCore.initialize(this)

        loadConfigs()
        initSystems()
        registerEvents()

        CreakingListener.init()

        // Player-facing queue/team commands come from MiniGamesAPI (/ready, /unready, /teamselect, ...).
        // Keep only a mode-specific admin command, same pattern as Splatoon (/splatoon ...).
        val creakyCmd = CreakyCommands()
        getCommand("creakywars")?.setExecutor(creakyCmd)
        getCommand("creakywars")?.tabCompleter = creakyCmd

        ArenaManager.registerArenasToApi()

        logger.info("CreakyWars запущен! Ожидание матчей...")

        server.scheduler.runTaskTimer(this, Runnable {
            val instance = MatchmakingManager.pollReady()
            if (instance != null) {
                GameManager.createGame(instance)
            }
        }, 20L, 20L)
    }

    override fun onDisable() {
        CreakingListener.shutdown()
        GameManager.shutdownAll()
        ArenaManager.deleteAllArenas()
    }

    private fun loadConfigs() {
        try {
            AdminConfig.load()
            GameConfig.load()
            ScenarioConfig.load()
            MapConfig.init()
        } catch (e: Exception) {
            e.printStackTrace()
            server.pluginManager.disablePlugin(this)
        }
    }

    private fun initSystems() {
        ArenaManager.init()
        GameManager.init()
        CoreManager.init()
        ResourceSpawner.init()
        TraderManager.init()
    }

    private fun registerEvents() {
        val pm = server.pluginManager
        pm.registerEvents(PlayerListener(), this)
        pm.registerEvents(GameListener(this), this)
        pm.registerEvents(ExplosivesListener(), this)
        pm.registerEvents(EyeblossomListener(), this)
        pm.registerEvents(TraderListener(), this)
        pm.registerEvents(CreakingListener(), this)
        pm.registerEvents(UpgradeListener(), this)
        pm.registerEvents(CeremonyController, this)
    }
}
