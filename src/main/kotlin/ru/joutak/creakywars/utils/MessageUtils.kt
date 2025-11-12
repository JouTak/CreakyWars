package ru.joutak.creakywars.utils

import org.bukkit.entity.Player

object MessageUtils {
    private const val PREFIX = "§6[CreakyWars] §r"

    fun sendMessage(player: Player, message: String) {
        player.sendMessage("$PREFIX$message")
    }

    fun broadcastMessage(message: String, players: Collection<Player>) {
        val fullMessage = "$PREFIX$message"
        players.forEach { it.sendMessage(fullMessage) }
    }

    fun sendTitle(player: Player, title: String, subtitle: String = "", fadeIn: Int = 10, stay: Int = 70, fadeOut: Int = 20) {
        player.sendTitle(title, subtitle, fadeIn, stay, fadeOut)
    }

    fun sendActionBar(player: Player, message: String) {
        try {
            player.sendActionBar(message)
        } catch (e: Exception) {
            // пропускаем
        }
    }
}