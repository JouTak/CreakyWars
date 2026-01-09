package ru.joutak.creakywars.commands

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import ru.joutak.creakywars.config.AdminConfig
import ru.joutak.creakywars.config.GameConfig
import ru.joutak.creakywars.config.MapConfig
import ru.joutak.creakywars.config.ScenarioConfig
import ru.joutak.creakywars.game.GameManager
import ru.joutak.creakywars.utils.PluginManager

/**
 * CreakyWars follows MiniGamesAPI for player-facing commands:
 * /ready, /unready, /teamselect, etc.
 *
 * This command is intentionally admin-only (same pattern as Splatoon /splatoon ...).
 */
class CreakyCommands : CommandExecutor {

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean {
        if (args.isEmpty()) {
            sendHelp(sender)
            return true
        }

        when (args[0].lowercase()) {
            "reload" -> {
                if (!sender.hasPermission("creakywars.admin") && sender !is ConsoleCommandSender) {
                    sender.sendMessage("§cНет прав.")
                    return true
                }

                try {
                    AdminConfig.load()
                    GameConfig.load()
                    ScenarioConfig.load()
                    MapConfig.init()
                    sender.sendMessage("§aКонфиги CreakyWars перезагружены.")
                } catch (e: Exception) {
                    sender.sendMessage("§cОшибка при перезагрузке конфигов. Смотри консоль.")
                    e.printStackTrace()
                }
            }

            "info" -> {
                val plugin = PluginManager.getPlugin()
                val activeGames = GameManager.getActiveGames().size
                sender.sendMessage("§6§lCreakyWars")
                sender.sendMessage("§eВерсия: §f${plugin.description.version}")
                sender.sendMessage("§eАктивных игр: §f$activeGames")
                sender.sendMessage("§7Очередь/команды: используйте §f/ready§7, §f/unready§7, §f/teamselect§7 (MiniGamesAPI)")
            }

            else -> sendHelp(sender)
        }

        return true
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage("§6§lCreakyWars")
        sender.sendMessage("§e/creakywars info §7- Информация")
        sender.sendMessage("§e/creakywars reload §7- Перезагрузить конфиги (§cop§7)")
        sender.sendMessage("§7Игрокам: §f/ready§7, §f/unready§7, §f/teamselect")
    }
}
