package ru.joutak.creakywars.queue

import org.bukkit.entity.Player
import java.util.UUID

class Queue {
    private val players = mutableListOf<UUID>()

    fun add(player: Player) {
        if (!players.contains(player.uniqueId)) {
            players.add(player.uniqueId)
        }
    }

    fun remove(player: Player) {
        players.remove(player.uniqueId)
    }

    fun contains(player: Player): Boolean {
        return players.contains(player.uniqueId)
    }

    fun size(): Int = players.size

    fun getPlayers(): List<Player> {
        return players.mapNotNull { org.bukkit.Bukkit.getPlayer(it) }
    }

    fun clear() {
        players.clear()
    }
}