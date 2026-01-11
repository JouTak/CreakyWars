package ru.joutak.creakywars.game

import org.bukkit.Bukkit
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import ru.joutak.creakywars.config.ScenarioConfig

class PhaseBossBar(private val game: Game) {
    private var bossBar: BossBar? = null

    fun create(phaseIndex: Int) {
        remove()

        if (phaseIndex >= ScenarioConfig.phases.size) return

        val phase = ScenarioConfig.phases[phaseIndex]

        bossBar = Bukkit.createBossBar(
            "§6${phase.name}",
            getPhaseColor(phaseIndex),
            BarStyle.SEGMENTED_10
        )

        bossBar?.progress = 1.0

        game.getAudiencePlayers().forEach { player ->
            bossBar?.addPlayer(player)
        }
    }

    fun updateProgress(remainingTime: Long, totalTime: Long) {
        val progress = remainingTime.toDouble() / totalTime.toDouble()
        bossBar?.progress = progress.coerceIn(0.0, 1.0)
    }

    fun remove() {
        bossBar?.removeAll()
        bossBar = null
    }

    private fun getPhaseColor(phaseIndex: Int): BarColor {
        return when (phaseIndex % 5) {
            0 -> BarColor.GREEN
            1 -> BarColor.YELLOW
            2 -> BarColor.RED
            3 -> BarColor.PURPLE
            4 -> BarColor.PINK
            else -> BarColor.WHITE
        }
    }

    fun addPlayer(player: Player) {
        bossBar?.addPlayer(player)
    }

    fun removePlayer(player: Player) {
        bossBar?.removePlayer(player)
    }
}