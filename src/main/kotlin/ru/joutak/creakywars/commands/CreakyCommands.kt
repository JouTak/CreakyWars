package ru.joutak.creakywars.commands

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import ru.joutak.creakywars.arenas.ArenaManager
import ru.joutak.creakywars.config.AdminConfig
import ru.joutak.creakywars.config.GameConfig
import ru.joutak.creakywars.config.ScenarioConfig
import ru.joutak.creakywars.game.GameManager
import ru.joutak.creakywars.queue.QueueManager
import ru.joutak.creakywars.spartakiada.SpartakiadaManager
import ru.joutak.creakywars.utils.MessageUtils
import ru.joutak.creakywars.utils.PluginManager
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

class CreakyCommands : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sendHelp(sender)
            return true
        }

        when (args[0].lowercase()) {
            "join" -> handleJoin(sender, args)
            "leave" -> handleLeave(sender)
            "start" -> handleStart(sender)
            "stop" -> handleStop(sender, args)
            "info" -> handleInfo(sender)
            "reload" -> handleReload(sender)
            "spartakiada" -> handleSpartakiada(sender, args)
            "forcestart" -> handleForceStart(sender)
            "setteam" -> handleSetTeam(sender, args)
            "stats" -> handleStats(sender, args)
            "debug" -> handleDebug(sender, args)
            "debugend" -> handleDebugEnd(sender, args) // Новая команда
            "setup" -> handleSetupMap(sender)
            "template" -> handleTemplateInfo(sender)
            else -> sendHelp(sender)
        }

        return true
    }

    private fun handleDebugEnd(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("creakywars.admin")) {
            sender.sendMessage("§cУ вас нет прав на использование этой команды!")
            return
        }

        if (!AdminConfig.debugMode) {
            sender.sendMessage("§cЭта команда доступна только в режиме отладки!")
            return
        }

        if (sender !is Player) {
            sender.sendMessage("§cЭту команду может использовать только игрок!")
            return
        }

        val game = GameManager.getGame(sender)
        if (game == null) {
            sender.sendMessage("§cВы не находитесь в игре!")
            return
        }

        if (args.size >= 2) {
            val teamId = args[1].toIntOrNull()
            if (teamId != null && teamId < game.teams.size) {
                val winner = game.teams[teamId]
                sender.sendMessage("§aЗавершаем игру. Победитель: ${winner.color}${winner.name}")
                game.debugEnd(winner)
                return
            }
        }

        sender.sendMessage("§aЗавершаем отладочную игру без победителя...")
        game.debugEnd(null)
    }

    private fun handleJoin(sender: CommandSender, args: Array<out String>) {
        if (sender !is Player) {
            sender.sendMessage("§cЭту команду может использовать только игрок!")
            return
        }

        if (SpartakiadaManager.isEnabled()) {
            QueueManager.addPlayer(sender)
            return
        }

        if (AdminConfig.debugMode) {
            QueueManager.addPlayer(sender)
            return
        }

        QueueManager.addPlayer(sender)
    }

    private fun handleLeave(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage("§cЭту команду может использовать только игрок!")
            return
        }

        if (GameManager.isInGame(sender)) {
            if (GameManager.removePlayerFromGame(sender)) {
                return
            }
        }

        if (QueueManager.isInQueue(sender)) {
            QueueManager.removePlayer(sender)
            return
        }

        MessageUtils.sendMessage(sender, "§cВы не находитесь ни в игре, ни в очереди!")
    }

    private fun handleStart(sender: CommandSender) {
        if (!sender.hasPermission("creakywars.admin")) {
            sender.sendMessage("§cУ вас нет прав на использование этой команды!")
            return
        }

        val minPlayers = AdminConfig.getMinPlayersToStart()
        if (QueueManager.getQueueSize() < minPlayers) {
            sender.sendMessage("§cНедостаточно игроков в очереди для начала игры!")
            sender.sendMessage("§cТребуется минимум: §e$minPlayers §cигроков")
            return
        }

        QueueManager.checkQueue()
        sender.sendMessage("§aПопытка запустить игру...")
    }

    private fun handleForceStart(sender: CommandSender) {
        if (!sender.hasPermission("creakywars.admin")) {
            sender.sendMessage("§cУ вас нет прав на использование этой команды!")
            return
        }

        val minPlayers = AdminConfig.getMinPlayersToStart()
        if (QueueManager.getQueueSize() < minPlayers) {
            sender.sendMessage("§cВ очереди должно быть минимум $minPlayers игрок(ов)!")
            return
        }

        val players = QueueManager.getQueueSize()
        sender.sendMessage("§aПринудительный запуск игры с $players игроками...")
        QueueManager.checkQueue()
    }

    private fun handleStop(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("creakywars.admin")) {
            sender.sendMessage("§cУ вас нет прав на использование этой команды!")
            return
        }

        if (args.size < 2) {
            sender.sendMessage("§cИспользование: /cw stop <arena_id|all>")
            return
        }

        if (args[1].equals("all", ignoreCase = true)) {
            GameManager.shutdownAll()
            sender.sendMessage("§aВсе игры остановлены!")
        } else {
            val arenaId = args[1].toIntOrNull()
            if (arenaId == null) {
                sender.sendMessage("§cНеверный ID арены!")
                return
            }

            val arena = ArenaManager.getArenas().firstOrNull { it.id == arenaId }
            if (arena == null) {
                sender.sendMessage("§cАрена с ID $arenaId не найдена!")
                return
            }

            val game = GameManager.getGame(arena)
            if (game == null) {
                sender.sendMessage("§cНа арене #$arenaId нет активной игры!")
                return
            }

            game.forceEnd()
            sender.sendMessage("§aИгра на арене #$arenaId остановлена!")
        }
    }

    private fun handleInfo(sender: CommandSender) {
        sender.sendMessage("§6§l=== CreakyWars Info ===")
        sender.sendMessage("§eИгроков в очереди: §f${QueueManager.getQueueSize()}")
        sender.sendMessage("§eАктивных игр: §f${GameManager.getActiveGames().size}")
        sender.sendMessage("§eДоступных арен: §f${ArenaManager.getArenas().count { it.isAvailable() }}")
        sender.sendMessage("§eРежим спартакиады: §f${if (SpartakiadaManager.isEnabled()) "§aВКЛ" else "§cВЫКЛ"}")
        sender.sendMessage("§eРежим отладки: §f${if (AdminConfig.debugMode) "§aВКЛ" else "§cВЫКЛ"}")

        if (AdminConfig.debugMode) {
            sender.sendMessage("§7  Минимум игроков: §f${AdminConfig.getMinPlayersToStart()}")
        }

        if (sender.hasPermission("creakywars.admin")) {
            sender.sendMessage("")
            sender.sendMessage("§6Активные игры:")
            if (GameManager.getActiveGames().isEmpty()) {
                sender.sendMessage("  §7Нет активных игр")
            } else {
                GameManager.getActiveGames().forEach { game ->
                    sender.sendMessage("  §e#${game.arena.id} §7- §f${game.players.size} игроков §7- §f${game.arena.state}")
                }
            }
        }
    }

    private fun handleReload(sender: CommandSender) {
        if (!sender.hasPermission("creakywars.admin")) {
            sender.sendMessage("§cУ вас нет прав на использование этой команды!")
            return
        }

        sender.sendMessage("§eПерезагрузка конфигурации...")

        try {
            AdminConfig.reload()
            GameConfig.reload()
            ScenarioConfig.reload()
            SpartakiadaManager.reload()

            ArenaManager.setTemplate()

            sender.sendMessage("§aКонфигурация успешно перезагружена!")
            if (AdminConfig.debugMode) {
                sender.sendMessage("§e[DEBUG] Режим отладки активен")
            }
        } catch (e: Exception) {
            sender.sendMessage("§cОшибка при перезагрузке: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun handleDebug(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("creakywars.admin")) {
            sender.sendMessage("§cУ вас нет прав на использование этой команды!")
            return
        }

        if (args.size < 2) {
            sender.sendMessage("§cИспользование: /cw debug <on|off>")
            sender.sendMessage("§eТекущий статус: ${if (AdminConfig.debugMode) "§aВКЛ" else "§cВЫКЛ"}")
            return
        }

        val enabled = when (args[1].lowercase()) {
            "on", "true", "1" -> true
            "off", "false", "0" -> false
            else -> {
                sender.sendMessage("§cИспользуйте: on или off")
                return
            }
        }

        val configFile = File(PluginManager.getPlugin().dataFolder, "admin-config.yml")
        val config = YamlConfiguration.loadConfiguration(configFile)
        config.set("debug.enabled", enabled)

        try {
            config.save(configFile)
            if (enabled) {
                sender.sendMessage("§a[DEBUG] Режим отладки включен!")
                sender.sendMessage("§eТеперь игры можно запускать с ${config.getInt("debug.min-players", 1)} игроком(ами)")
            } else {
                sender.sendMessage("§c[DEBUG] Режим отладки выключен!")
            }
            sender.sendMessage("§eПерезагрузите плагин: §6/cw reload")
        } catch (e: Exception) {
            sender.sendMessage("§cОшибка при сохранении конфига: ${e.message}")
        }
    }

    private fun handleSetupMap(sender: CommandSender) {
        if (!sender.hasPermission("creakywars.admin")) {
            sender.sendMessage("§cУ вас нет прав на использование этой команды!")
            return
        }

        sender.sendMessage("§6§l=== Быстрая настройка CreakyWars ===")
        sender.sendMessage("")
        sender.sendMessage("§a✓ Шаблонный мир создается автоматически!")
        sender.sendMessage("§7Мир: §e${AdminConfig.templateWorldName}")
        sender.sendMessage("")
        sender.sendMessage("§eДля начала тестирования:")
        sender.sendMessage("")
        sender.sendMessage("§61. §fПроверить информацию")
        sender.sendMessage("   §e/cw info")
        sender.sendMessage("   §e/cw template")
        sender.sendMessage("")
        sender.sendMessage("§62. §fВключить режим отладки §7(если не включен)")
        sender.sendMessage("   §e/cw debug on")
        sender.sendMessage("   §e/cw reload")
        sender.sendMessage("")
        sender.sendMessage("§63. §fПрисоединиться к игре")
        sender.sendMessage("   §e/cw join")
        sender.sendMessage("")
        sender.sendMessage("§7Опционально:")
        sender.sendMessage("§7- Редактировать шаблон: §f/mv tp ${AdminConfig.templateWorldName}")
        sender.sendMessage("§7- После изменений: §f/cw reload")
        sender.sendMessage("")
        sender.sendMessage("§a✓ Готово к тестированию!")
    }

    private fun handleTemplateInfo(sender: CommandSender) {
        if (!sender.hasPermission("creakywars.admin")) {
            sender.sendMessage("§cУ вас нет прав на использование этой команды!")
            return
        }

        val templateName = AdminConfig.templateWorldName
        val world = Bukkit.getWorld(templateName)

        sender.sendMessage("§6§l=== Информация о шаблоне ===")
        sender.sendMessage("§eНазвание: §f$templateName")
        sender.sendMessage("§eСуществует: ${if (world != null) "§aДа" else "§cНет"}")

        if (world != null) {
            sender.sendMessage("§eТип: §f${world.worldType}")
            sender.sendMessage("§eСпавн: §f${world.spawnLocation.blockX}, ${world.spawnLocation.blockY}, ${world.spawnLocation.blockZ}")
            sender.sendMessage("§eГраница: §f${world.worldBorder.size.toInt()} блоков")
            sender.sendMessage("§eИгроков в мире: §f${world.players.size}")
            sender.sendMessage("§eВремя: §f${world.time}")
            sender.sendMessage("§eПогода: §f${if (world.hasStorm()) "Дождь" else "Ясно"}")
        } else {
            sender.sendMessage("")
            sender.sendMessage("§cШаблонный мир не найден!")
            sender.sendMessage("§eОн будет создан автоматически при перезагрузке плагина.")
        }

        sender.sendMessage("")
        sender.sendMessage("§7Команды:")
        sender.sendMessage("§7- Телепорт: §f/mv tp $templateName")
        sender.sendMessage("§7- Перезагрузка: §f/cw reload")
    }

    private fun handleSpartakiada(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("creakywars.admin")) {
            sender.sendMessage("§cУ вас нет прав на использование этой команды!")
            return
        }

        if (args.size < 2) {
            sender.sendMessage("§cИспользование: /cw spartakiada <on|off|assign>")
            sender.sendMessage("§eТекущий статус: ${if (SpartakiadaManager.isEnabled()) "§aВКЛ" else "§cВЫКЛ"}")
            return
        }

        when (args[1].lowercase()) {
            "on" -> {
                SpartakiadaManager.setEnabled(true)
                sender.sendMessage("§aРежим спартакиады включен!")
            }
            "off" -> {
                SpartakiadaManager.setEnabled(false)
                sender.sendMessage("§cРежим спартакиады выключен!")
            }
            "assign" -> {
                if (args.size < 4) {
                    sender.sendMessage("§cИспользование: /cw spartakiada assign <игрок> <team_id>")
                    return
                }

                val targetPlayer = Bukkit.getPlayerExact(args[2])
                if (targetPlayer == null) {
                    sender.sendMessage("§cИгрок не найден!")
                    return
                }

                val teamId = args[3].toIntOrNull()
                if (teamId == null) {
                    sender.sendMessage("§cНеверный ID команды!")
                    return
                }

                SpartakiadaManager.assignPlayerToTeam(targetPlayer, teamId)
                sender.sendMessage("§aИгрок ${targetPlayer.name} назначен в команду #$teamId")
            }
            else -> sender.sendMessage("§cНеизвестная подкоманда!")
        }
    }

    private fun handleSetTeam(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("creakywars.admin")) {
            sender.sendMessage("§cУ вас нет прав на использование этой команды!")
            return
        }

        if (args.size < 3) {
            sender.sendMessage("§cИспользование: /cw setteam <игрок> <team_id>")
            return
        }

        val targetPlayer = Bukkit.getPlayerExact(args[1])
        if (targetPlayer == null) {
            sender.sendMessage("§cИгрок не найден!")
            return
        }

        val teamId = args[2].toIntOrNull()
        if (teamId == null) {
            sender.sendMessage("§cНеверный ID команды!")
            return
        }

        val game = GameManager.getGame(targetPlayer)
        if (game == null) {
            sender.sendMessage("§cИгрок не находится в игре!")
            return
        }

        if (teamId >= game.teams.size) {
            sender.sendMessage("§cКоманда с ID $teamId не существует!")
            return
        }

        val newTeam = game.teams[teamId]
        val playerData = game.getPlayerData(targetPlayer)

        if (playerData != null) {
            playerData.team?.removePlayer(targetPlayer.uniqueId)
            playerData.team = newTeam
            newTeam.addPlayer(targetPlayer.uniqueId)

            sender.sendMessage("§aИгрок ${targetPlayer.name} перемещен в команду ${newTeam.color}${newTeam.name}")
            MessageUtils.sendMessage(targetPlayer, "§eВы были перемещены в команду ${newTeam.color}${newTeam.name}")
        }
    }

    private fun handleStats(sender: CommandSender, args: Array<out String>) {
        if (sender !is Player && args.size < 2) {
            sender.sendMessage("§cИспользование: /cw stats <игрок>")
            return
        }

        val targetPlayer = if (args.size >= 2) {
            Bukkit.getPlayerExact(args[1])
        } else {
            sender as Player
        }

        if (targetPlayer == null) {
            sender.sendMessage("§cИгрок не найден!")
            return
        }

        val game = GameManager.getGame(targetPlayer)
        if (game == null) {
            sender.sendMessage("§cИгрок не находится в игре!")
            return
        }

        val playerData = game.getPlayerData(targetPlayer)
        if (playerData == null) {
            sender.sendMessage("§cДанные игрока не найдены!")
            return
        }

        val team = playerData.team

        sender.sendMessage("§6§l=== Статистика: ${targetPlayer.name} ===")
        sender.sendMessage("§eКоманда: ${team?.color}${team?.name ?: "Нет"}")
        sender.sendMessage("§eУбийств: §f${playerData.kills}")
        sender.sendMessage("§eСмертей: §f${playerData.deaths}")
        sender.sendMessage("§eФинальных убийств: §f${playerData.finalKills}")
        sender.sendMessage("§eРесурсов собрано: §f${playerData.resourcesCollected}")
        sender.sendMessage("§eЖив: §f${if (playerData.isAlive) "§aДа" else "§cНет"}")
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage("§6§l=== CreakyWars Commands ===")
        sender.sendMessage("§e/cw join §7- Присоединиться к очереди")
        sender.sendMessage("§e/cw leave §7- Покинуть очередь/игру")
        sender.sendMessage("§e/cw info §7- Информация о плагине")
        sender.sendMessage("§e/cw stats [игрок] §7- Статистика игрока")

        if (sender.hasPermission("creakywars.admin")) {
            sender.sendMessage("")
            sender.sendMessage("§c§lАдминистративные команды:")
            sender.sendMessage("§e/cw setup §7- Быстрая настройка")
            sender.sendMessage("§e/cw template §7- Информация о шаблоне")
            sender.sendMessage("§e/cw debug <on|off> §7- Режим отладки (1 игрок)")

            if (AdminConfig.debugMode) {
                sender.sendMessage("§e/cw debugend [team_id] §7- Завершить отладочную игру")
            }

            sender.sendMessage("§e/cw start §7- Запустить игру")
            sender.sendMessage("§e/cw forcestart §7- Принудительно запустить игру")
            sender.sendMessage("§e/cw stop <arena_id|all> §7- Остановить игру")
            sender.sendMessage("§e/cw reload §7- Перезагрузить конфигурацию")
            sender.sendMessage("§e/cw spartakiada <on|off|assign> §7- Управление спартакиадой")
            sender.sendMessage("§e/cw setteam <игрок> <team_id> §7- Переместить игрока в команду")
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String>? {
        if (args.size == 1) {
            val commands = mutableListOf("join", "leave", "info", "stats")

            if (sender.hasPermission("creakywars.admin")) {
                commands.addAll(listOf(
                    "start", "forcestart", "stop", "reload", "spartakiada",
                    "setteam", "debug", "setup", "template"
                ))

                if (AdminConfig.debugMode) {
                    commands.add("debugend")
                }
            }

            return commands.filter { it.startsWith(args[0].lowercase()) }
        }

        if (args.size == 2) {
            when (args[0].lowercase()) {
                "debugend" -> {
                    if (sender.hasPermission("creakywars.admin") && AdminConfig.debugMode) {
                        return (0 until AdminConfig.teamsCount).map { it.toString() }
                    }
                }
                "debug" -> {
                    if (sender.hasPermission("creakywars.admin")) {
                        return listOf("on", "off").filter { it.startsWith(args[1].lowercase()) }
                    }
                }
                "spartakiada" -> {
                    if (sender.hasPermission("creakywars.admin")) {
                        return listOf("on", "off", "assign").filter { it.startsWith(args[1].lowercase()) }
                    }
                }
                "stop" -> {
                    if (sender.hasPermission("creakywars.admin")) {
                        val options = mutableListOf("all")
                        options.addAll(ArenaManager.getArenas().map { it.id.toString() })
                        return options.filter { it.startsWith(args[1].lowercase()) }
                    }
                }
                "stats", "setteam" -> {
                    return Bukkit.getOnlinePlayers().map { it.name }.filter {
                        it.lowercase().startsWith(args[1].lowercase())
                    }
                }
            }
        }

        if (args.size == 3) {
            when (args[0].lowercase()) {
                "spartakiada" -> {
                    if (args[1].equals("assign", ignoreCase = true) && sender.hasPermission("creakywars.admin")) {
                        return Bukkit.getOnlinePlayers().map { it.name }.filter {
                            it.lowercase().startsWith(args[2].lowercase())
                        }
                    }
                }
                "setteam" -> {
                    if (sender.hasPermission("creakywars.admin")) {
                        return (0 until AdminConfig.teamsCount).map { it.toString() }
                    }
                }
            }
        }

        if (args.size == 4) {
            when (args[0].lowercase()) {
                "spartakiada" -> {
                    if (args[1].equals("assign", ignoreCase = true) && sender.hasPermission("creakywars.admin")) {
                        return (0 until AdminConfig.teamsCount).map { it.toString() }
                    }
                }
            }
        }

        return null
    }
}