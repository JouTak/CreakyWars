package ru.joutak.creakywars.commands

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.joutak.creakywars.game.GameManager
import ru.joutak.minigames.managers.MatchmakingManager

class CreakyCommands : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("§cКоманда только для игроков!")
            return true
        }

        if (args.isEmpty()) {
            sendHelp(sender)
            return true
        }

        when (args[0].lowercase()) {
            "join" -> {
                if (GameManager.isInGame(sender)) {
                    sender.sendMessage("§cВы уже в игре!")
                    return true
                }

                MatchmakingManager.addPlayer(sender)
                sender.sendMessage("§aВы встали в очередь! Ожидайте начала игры.")
            }

            "leave" -> {
                MatchmakingManager.removePlayer(sender)

                val game = GameManager.getGame(sender)
                if (game != null) {
                    game.removePlayer(sender)
                } else {
                    sender.sendMessage("§aВы покинули очередь.")
                }
            }

            "start" -> {
                if (!sender.hasPermission("creakywars.admin")) {
                    sender.sendMessage("§cНет прав.")
                    return true
                }
                sender.sendMessage("§eИгры запускаются автоматически при заполнении очереди.")
            }

            else -> sendHelp(sender)
        }
        return true
    }

    private fun sendHelp(sender: Player) {
        sender.sendMessage("§6§lCreakyWars")
        sender.sendMessage("§e/cw join §7- Встать в очередь (автоподбор карты)")
        sender.sendMessage("§e/cw leave §7- Покинуть очередь/игру")
    }
}