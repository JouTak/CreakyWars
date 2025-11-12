package ru.joutak.creakywars.utils

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import ru.joutak.creakywars.CreakyWars
import java.util.logging.Logger

object PluginManager {
    private lateinit var plugin: CreakyWars

    val multiverseCore: org.bukkit.plugin.Plugin by lazy {
        val mvPlugin = Bukkit.getPluginManager().getPlugin("Multiverse-Core")
        if (mvPlugin == null || !mvPlugin.isEnabled) {
            throw IllegalStateException("Multiverse-Core не найден или не включен! Установите плагин.")
        }
        mvPlugin
    }

    fun init(plugin: CreakyWars) {
        this.plugin = plugin
    }

    fun getPlugin(): JavaPlugin = plugin

    fun getLogger(): Logger = plugin.logger
}