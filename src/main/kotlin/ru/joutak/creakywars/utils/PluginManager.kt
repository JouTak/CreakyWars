package ru.joutak.creakywars.utils

import org.bukkit.plugin.java.JavaPlugin
import ru.joutak.creakywars.CreakyWars
import java.util.logging.Logger

object PluginManager {
    private lateinit var plugin: CreakyWars

    fun init(plugin: CreakyWars) {
        this.plugin = plugin
    }

    fun getPlugin(): JavaPlugin = plugin

    fun getLogger(): Logger = plugin.logger
}