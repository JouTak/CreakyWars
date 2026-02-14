@file:Suppress("DEPRECATION")

package ru.joutak.creakywars.utils

import org.bukkit.ChatColor
import org.bukkit.Color
import org.bukkit.entity.Player

/**
 * Paper 1.21.7+ added a "locator bar" with player waypoints.
 * We use reflection to keep compatibility with older server builds / forks.
 */
object WaypointCompat {
    private val setWaypointColorMethod by lazy {
        try {
            Player::class.java.getMethod("setWaypointColor", Color::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun setWaypointColor(player: Player, color: Color?) {
        val m = setWaypointColorMethod ?: return
        try {
            m.invoke(player, color)
        } catch (_: Exception) {
        }
    }

    fun resetWaypoint(player: Player) {
        setWaypointColor(player, null)
    }

    fun toWaypointColor(chatColor: ChatColor): Color {
        // Use vanilla formatting colors (same palette as sidebar/team colors).
        val rgb = when (chatColor) {
            ChatColor.GOLD -> 0xFFAA00
            ChatColor.BLUE -> 0x5555FF
            ChatColor.LIGHT_PURPLE -> 0xFF55FF
            ChatColor.GREEN -> 0x55FF55
            ChatColor.RED -> 0xFF5555
            ChatColor.YELLOW -> 0xFFFF55
            ChatColor.AQUA -> 0x55FFFF
            ChatColor.DARK_AQUA -> 0x00AAAA
            ChatColor.DARK_BLUE -> 0x0000AA
            ChatColor.DARK_GREEN -> 0x00AA00
            ChatColor.DARK_PURPLE -> 0xAA00AA
            ChatColor.DARK_RED -> 0xAA0000
            ChatColor.GRAY -> 0xAAAAAA
            ChatColor.DARK_GRAY -> 0x555555
            ChatColor.BLACK -> 0x000000
            ChatColor.WHITE -> 0xFFFFFF
            else -> 0xFFFFFF
        }
        return Color.fromRGB((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
    }
}
