package ru.joutak.creakywars.listeners

import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import ru.joutak.creakywars.game.Game
import ru.joutak.creakywars.game.Team
import ru.joutak.creakywars.utils.MessageUtils
import ru.joutak.creakywars.utils.PluginManager

class TeamChestListener(private val game: Game) : Listener {

    val teamChests: MutableMap<Block, Team> = mutableMapOf()

    init {
        PluginManager.getPlugin().server.pluginManager.registerEvents(this, PluginManager.getPlugin())
    }

    fun addTeamChest(block: Block, team: Team) {
        teamChests[block] = team
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val block = event.clickedBlock ?: return
        val player = event.player

        if (event.action != Action.RIGHT_CLICK_BLOCK) return

        if (block.type != Material.CHEST && block.type != Material.TRAPPED_CHEST) {
            return
        }

        val owningTeam = teamChests[block] ?: return

        val playerTeam = game.getTeam(player)

        val isOwner = playerTeam == owningTeam
        val isTeamEliminated = owningTeam.isEliminated(game)

        if (isOwner || isTeamEliminated) {

            if (isTeamEliminated && !isOwner) {
                MessageUtils.sendMessage(player, "§eВы открыли сундук выбывшей команды ${owningTeam.color}${owningTeam.name}§e!")
            }

        } else {
            event.isCancelled = true

            val message = if (playerTeam == null) {
                "§cЭтот сундук защищен! Он принадлежит команде ${owningTeam.color}${owningTeam.name}§c."
            } else {
                "§cВы не можете открыть сундук команды ${owningTeam.color}${owningTeam.name}§c!"
            }

            MessageUtils.sendMessage(player, message)
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
        }
    }
}