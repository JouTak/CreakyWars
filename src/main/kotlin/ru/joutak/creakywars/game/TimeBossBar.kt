package ru.joutak.creakywars.game

import org.bukkit.Bukkit
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import ru.joutak.creakywars.config.GameConfig

class TimeBossBar(private val game: Game, private val dayNightCycle: DayNightCycle) {
    private var bossBar: BossBar? = null

    fun create() {
        remove()

        val startTitle = formatTitle()

        bossBar = Bukkit.createBossBar(
            "§6$startTitle",
            BarColor.YELLOW,
            BarStyle.SOLID
        )

        bossBar?.progress = 1.0

        game.getAudiencePlayers().forEach { player ->
            bossBar?.addPlayer(player)
        }
    }

    private fun formatTitle(): String {
        return if (dayNightCycle.isNightTime()) {
            "§6До окончания ночи: ${formatTime(GameConfig.nightDurationTicks - dayNightCycle.currentTick())}"
        } else {
            "§6До наступления ночи: ${formatTime(GameConfig.dayDurationTicks - dayNightCycle.currentTick())}"
        }
    }

    private fun formatTime(ticks: Long): String {
        var seconds = ticks / 20

        val minutes = seconds / 60

        seconds -= minutes * 60

        return "§r$minutes:$seconds"
    }

    /**
     * [remaining] and [total] are in the same units (ticks or seconds) – only ratio matters.
     */
    fun updateProgress(remaining: Long, total: Long) {
        if (total <= 0L) {
            bossBar?.progress = 0.0
            return
        }
        val progress = remaining.toDouble() / total.toDouble()
        bossBar?.progress = progress.coerceIn(0.0, 1.0)
        bossBar?.setTitle(formatTitle())
    }

    fun remove() {
        bossBar?.removeAll()
        bossBar = null
    }

    fun addPlayer(player: Player) {
        bossBar?.addPlayer(player)
    }

    fun removePlayer(player: Player) {
        bossBar?.removePlayer(player)
    }
}
