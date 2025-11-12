package ru.joutak.creakywars.gui

import ru.joutak.creakywars.utils.PluginManager

object GuiManager {
    fun init() {
        TeamSelectionGui
        ShopGui

        PluginManager.getLogger().info("GuiManager инициализирован!")
    }
}