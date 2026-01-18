package ru.joutak.creakywars.commands

import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import ru.joutak.creakywars.arenas.ArenaManager
import ru.joutak.creakywars.arenas.ArenaState
import ru.joutak.creakywars.ceremony.CeremonyController
import ru.joutak.creakywars.config.AdminConfig
import ru.joutak.creakywars.config.GameConfig
import ru.joutak.creakywars.config.MapConfig
import ru.joutak.creakywars.config.ScenarioConfig
import ru.joutak.creakywars.game.Game
import ru.joutak.creakywars.game.GameManager
import ru.joutak.creakywars.utils.MessageUtils
import ru.joutak.creakywars.utils.PluginManager

/**
 * CreakyWars follows MiniGamesAPI for player-facing commands:
 * /ready, /unready, /teamselect, etc.
 *
 * This command is intentionally admin-only and focused on tournament ops & observation.
 */
class CreakyCommands : CommandExecutor, TabCompleter {

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
            "help" -> sendHelp(sender)

            "info" -> {
                val plugin = PluginManager.getPlugin()
                val activeGames = GameManager.getActiveGames().size
                sender.sendMessage("§6§lCreakyWars")
                sender.sendMessage("§eВерсия: §f${plugin.description.version}")
                sender.sendMessage("§eАктивных игр: §f$activeGames")
                sender.sendMessage("§7Очередь/команды: используйте §f/ready§7, §f/unready§7, §f/teamselect§7 (MiniGamesAPI)")
            }

            "reload" -> {
                if (!hasAdmin(sender)) {
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

            "status", "games", "list" -> {
                if (!hasAdmin(sender)) {
                    sender.sendMessage("§cНет прав.")
                    return true
                }

                // /creakywars status [target]
                val targetToken = args.getOrNull(1)
                if (targetToken == null) {
                    sendGamesList(sender)
                    return true
                }

                val game = resolveGame(sender, targetToken)
                if (game == null) {
                    sender.sendMessage("§cИгра не найдена. Используй: /creakywars status (список)")
                    return true
                }
                sendGameDetails(sender, game)
            }

            "spectate", "spec" -> {
                if (!hasAdmin(sender)) {
                    sender.sendMessage("§cНет прав.")
                    return true
                }

                val player = sender as? Player
                if (player == null) {
                    sender.sendMessage("§cТолько для игроков.")
                    return true
                }

                val game = resolveGame(sender, args.getOrNull(1) ?: "here")
                if (game == null) {
                    MessageUtils.sendMessage(player, "§cИгра не найдена. Используй: /creakywars games")
                    return true
                }

                val asPlayer = GameManager.getGame(player)
                if (asPlayer != null) {
                    MessageUtils.sendMessage(player, "§cНельзя включить наблюдение, пока ты участвуешь в матче.")
                    return true
                }

                // If already spectating some other match, exit it first (with full restore).
                val oldSpectate = GameManager.getSpectatingGame(player)
                if (oldSpectate != null && oldSpectate != game) {
                    try {
                        oldSpectate.removeSpectator(player, silent = true, forceLobby = false)
                    } catch (_: Exception) {
                    }
                }

                game.addSpectator(player)
            }

            "unspectate", "unspec", "leave" -> {
                if (!hasAdmin(sender)) {
                    sender.sendMessage("§cНет прав.")
                    return true
                }
                val player = sender as? Player
                if (player == null) {
                    sender.sendMessage("§cТолько для игроков.")
                    return true
                }

                val game = GameManager.getSpectatingGame(player)
                if (game == null) {
                    MessageUtils.sendMessage(player, "§eТы сейчас не наблюдаешь ни за одной игрой.")
                    return true
                }

                game.removeSpectator(player, silent = false, forceLobby = false)
            }

            "end" -> {
                if (!hasDanger(sender)) {
                    sender.sendMessage("§cНет прав. (нужно creakywars.admin.danger)")
                    return true
                }

                val game = resolveGame(sender, args.getOrNull(1) ?: "here")
                if (game == null) {
                    sender.sendMessage("§cИгра не найдена. Используй: /creakywars games")
                    return true
                }
                val reason = if (args.size >= 3) args.copyOfRange(2, args.size).joinToString(" ") else null
                game.adminEnd(reason)
                sender.sendMessage("§aОк. Игра #${game.arena.id} завершаетcя.")
            }

            "startnow", "forcestart" -> {
                if (!hasDanger(sender)) {
                    sender.sendMessage("§cНет прав. (нужно creakywars.admin.danger)")
                    return true
                }

                val game = resolveGame(sender, args.getOrNull(1) ?: "here")
                if (game == null) {
                    sender.sendMessage("§cИгра не найдена.")
                    return true
                }

                if (game.adminStartNow()) {
                    sender.sendMessage("§aОк. Принудительный старт #${game.arena.id}.")
                } else {
                    sender.sendMessage("§eНечего стартовать: арена не в отсчёте.")
                }
            }

            "phase" -> {
                if (!hasDanger(sender)) {
                    sender.sendMessage("§cНет прав. (нужно creakywars.admin.danger)")
                    return true
                }

                val action = args.getOrNull(1)?.lowercase()
                if (action == null) {
                    sender.sendMessage("§cИспользование: /creakywars phase <skip|set|info> [target] ...")
                    return true
                }

                when (action) {
                    "info" -> {
                        val game = resolveGame(sender, args.getOrNull(2) ?: "here")
                        if (game == null) {
                            sender.sendMessage("§cИгра не найдена.")
                            return true
                        }
                        sendPhaseInfo(sender, game)
                    }

                    "skip" -> {
                        val game = resolveGame(sender, args.getOrNull(2) ?: "here")
                        if (game == null) {
                            sender.sendMessage("§cИгра не найдена.")
                            return true
                        }
                        if (game.adminSkipPhase()) {
                            sender.sendMessage("§aОк. Следующая фаза: §e${game.getCurrentPhaseName()}§a.")
                        } else {
                            sender.sendMessage("§eНечего скипать: игра не в фазе.")
                        }
                    }

                    "set" -> {
                        val game = resolveGame(sender, args.getOrNull(2) ?: "here")
                        if (game == null) {
                            sender.sendMessage("§cИгра не найдена.")
                            return true
                        }
                        val index = args.getOrNull(3)?.toIntOrNull()
                        if (index == null) {
                            sender.sendMessage("§cИспользование: /creakywars phase set <target> <index>")
                            return true
                        }
                        if (game.adminSetPhase(index)) {
                            sender.sendMessage("§aОк. Текущая фаза: §e${game.getCurrentPhaseName()}§a.")
                        } else {
                            sender.sendMessage("§cНельзя: игра не запущена или индекс неверный.")
                        }
                    }

                    else -> sender.sendMessage("§cНеизвестно. Доступно: skip, set, info")
                }
            }

            "broadcast" -> {
                if (!hasAdmin(sender)) {
                    sender.sendMessage("§cНет прав.")
                    return true
                }
                if (args.size < 3) {
                    sender.sendMessage("§cИспользование: /creakywars broadcast <all|target> <сообщение...>")
                    return true
                }
                val target = args[1]
                val msg = args.copyOfRange(2, args.size).joinToString(" ")
                if (target.equals("all", ignoreCase = true)) {
                    GameManager.getActiveGames().forEach { it.broadcastMessage(msg) }
                    sender.sendMessage("§aОк. Отправлено во все активные игры.")
                } else {
                    val game = resolveGame(sender, target)
                    if (game == null) {
                        sender.sendMessage("§cИгра не найдена.")
                        return true
                    }
                    game.broadcastMessage(msg)
                    sender.sendMessage("§aОк. Отправлено в игру #${game.arena.id}.")
                }
            }

            "arena" -> {
                if (!hasDanger(sender)) {
                    sender.sendMessage("§cНет прав. (нужно creakywars.admin.danger)")
                    return true
                }
                val action = args.getOrNull(1)?.lowercase()
                if (action == null) {
                    sender.sendMessage("§cИспользование: /creakywars arena cleanup")
                    return true
                }

                when (action) {
                    "cleanup", "cleanuporphans", "orphans" -> {
                        val activeWorlds = mutableSetOf<String>()
                        GameManager.getActiveGames().forEach { game ->
                            game.arena.worldName?.let { activeWorlds.add(it) }
                        }
                        activeWorlds.addAll(CeremonyController.getActiveWorldNames())
                        val deleted = ArenaManager.cleanupOrphans(activeWorlds)
                        sender.sendMessage("§aОк. Удалено сиротских арен: §e$deleted§a.")
                    }
                    else -> sender.sendMessage("§cНеизвестно. Доступно: cleanup")
                }
            }

            "give" -> {
                if (!hasAdmin(sender)) {
                    sender.sendMessage("§cНет прав.")
                    return true
                }

                if (args.size < 2) {
                    sender.sendMessage("§cИспользование: /creakywars give <resourceId> [amount] [player]")
                    sender.sendMessage("§7Доступные ресурсы: §f${GameConfig.resourceTypes.keys.sorted().joinToString(", ")}")
                    return true
                }

                val idToken = args[1]
                val resource = GameConfig.resourceTypes.entries.firstOrNull { it.key.equals(idToken, ignoreCase = true) }?.value
                if (resource == null) {
                    sender.sendMessage("§cНеизвестный ресурс: §f$idToken")
                    sender.sendMessage("§7Доступные ресурсы: §f${GameConfig.resourceTypes.keys.sorted().joinToString(", ")}")
                    return true
                }

                val amount = args.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(1) ?: 16

                val target: Player? = when {
                    sender is Player && args.size < 4 -> sender
                    else -> Bukkit.getPlayerExact(args.getOrNull(3) ?: "")
                }

                if (target == null) {
                    sender.sendMessage("§cИгрок не найден. Использование: /creakywars give <resourceId> [amount] <player>")
                    return true
                }

                var remaining = amount
                val maxStack = resource.material.maxStackSize.coerceAtLeast(1)

                while (remaining > 0) {
                    val stackAmount = minOf(maxStack, remaining)
                    val item = resource.createItemStack(stackAmount)

                    val leftover = target.inventory.addItem(item)
                    if (leftover.isNotEmpty()) {
                        leftover.values.forEach { target.world.dropItemNaturally(target.location, it) }
                    }

                    remaining -= stackAmount
                }

                sender.sendMessage("§aОк. Выдано §e$amount§a x §f${resource.displayName}§a игроку §e${target.name}§a.")
            }

            else -> sendHelp(sender)
        }

        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): MutableList<String> {
        if (!hasAdmin(sender)) return mutableListOf()

        val sub = listOf(
            "help",
            "info",
            "reload",
            "games",
            "status",
            "spectate",
            "unspectate",
            "broadcast",
            "give",
            "startnow",
            "end",
            "phase",
            "arena",
        )

        if (args.size == 1) {
            return sub.filter { it.startsWith(args[0], ignoreCase = true) }.toMutableList()
        }

        if (args.size == 2) {
            return when (args[0].lowercase()) {
                "give" -> GameConfig.resourceTypes.keys
                    .sorted()
                    .filter { it.startsWith(args[1], ignoreCase = true) }
                    .toMutableList()
                "spectate", "spec", "status", "games", "end", "startnow", "forcestart", "broadcast" -> {
                    buildGameTargets().filter { it.startsWith(args[1], ignoreCase = true) }.toMutableList()
                }
                "phase" -> listOf("info", "skip", "set")
                    .filter { it.startsWith(args[1], ignoreCase = true) }
                    .toMutableList()
                "arena" -> listOf("cleanup")
                    .filter { it.startsWith(args[1], ignoreCase = true) }
                    .toMutableList()
                else -> mutableListOf()
            }
        }

        if (args.size == 3 && args[0].equals("give", ignoreCase = true)) {
            return listOf("1", "4", "8", "16", "32", "64")
                .filter { it.startsWith(args[2], ignoreCase = true) }
                .toMutableList()
        }

        if (args.size == 4 && args[0].equals("give", ignoreCase = true)) {
            return Bukkit.getOnlinePlayers().map { it.name }
                .filter { it.startsWith(args[3], ignoreCase = true) }
                .toMutableList()
        }

        if (args.size == 3 && args[0].equals("phase", ignoreCase = true)) {
            return buildGameTargets().filter { it.startsWith(args[2], ignoreCase = true) }.toMutableList()
        }

        return mutableListOf()
    }

    private fun hasAdmin(sender: CommandSender): Boolean {
        return sender is ConsoleCommandSender || sender.hasPermission("creakywars.admin")
    }

    private fun hasDanger(sender: CommandSender): Boolean {
        return sender is ConsoleCommandSender || sender.hasPermission("creakywars.admin.danger")
    }

    private fun buildGameTargets(): List<String> {
        val targets = mutableListOf("here")
        GameManager.getActiveGames().forEach { targets.add(it.arena.id.toString()) }
        return targets
    }

    private fun resolveGame(sender: CommandSender, token: String): Game? {
        if (token.equals("here", ignoreCase = true)) {
            val player = sender as? Player ?: return null
            return GameManager.getGame(player.world)
        }

        val id = token.toIntOrNull()
        if (id != null) {
            return GameManager.getGameById(id)
        }

        val p = Bukkit.getPlayerExact(token)
        if (p != null) {
            val asPlayer = GameManager.getGame(p)
            if (asPlayer != null) return asPlayer
            val asSpectator = GameManager.getSpectatingGame(p)
            if (asSpectator != null) return asSpectator
        }

        val world: World? = Bukkit.getWorld(token)
        if (world != null) {
            return GameManager.getGame(world)
        }

        return null
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage("§6§lCreakyWars")
        sender.sendMessage("§e/creakywars info §7- Информация")
        sender.sendMessage("§e/creakywars games §7- Список активных игр")
        sender.sendMessage("§e/creakywars status <id|here> §7- Подробности игры")
        sender.sendMessage("§e/creakywars spectate <id|here> §7- Наблюдать с UI")
        sender.sendMessage("§e/creakywars unspectate §7- Выйти из наблюдения")
        sender.sendMessage("§e/creakywars broadcast <all|id|here> <msg...> §7- Сообщение в матч")
        sender.sendMessage("§e/creakywars give <resourceId> [amount] [player] §7- Выдать игровой ресурс")
        sender.sendMessage("§e/creakywars reload §7- Перезагрузить конфиги (§cop§7)")
        sender.sendMessage("§cОпасное (§cop§7): §e/creakywars end/startnow/phase/arena cleanup")
        sender.sendMessage("§7Игрокам: §f/ready§7, §f/unready§7, §f/teamselect")
    }

    private fun sendGamesList(sender: CommandSender) {
        val games = GameManager.getActiveGames()
        sender.sendMessage("§6§lCreakyWars §7Активные игры: §f${games.size}")
        if (games.isEmpty()) {
            sender.sendMessage("§7(пусто)")
            return
        }

        games.forEach { game ->
            sender.sendMessage(formatGameLine(game))
        }
    }

    private fun sendPhaseInfo(sender: CommandSender, game: Game) {
        val idx = game.getCurrentPhaseIndex()
        val name = game.getCurrentPhaseName()
        val rem = game.getRemainingPhaseSeconds()
        sender.sendMessage("§6Фаза игры §e#${game.arena.id}§6: §f$name")
        sender.sendMessage("§7Индекс: §f$idx§7, остаток: §f${rem?.toString() ?: "—"}s")
        sender.sendMessage("§7Состояние арены: §f${formatState(game.arena.state)}")
    }

    private fun sendGameDetails(sender: CommandSender, game: Game) {
        sender.sendMessage("§6§lИгра #${game.arena.id} §7(${game.arena.mapConfig.displayName})")
        sender.sendMessage("§7Мир: §f${game.arena.worldName}§7, состояние: §f${formatState(game.arena.state)}")

        val phaseName = game.getCurrentPhaseName()
        val rem = game.getRemainingPhaseSeconds()
        val countdown = game.getCountdownSeconds()

        when (game.arena.state) {
            ArenaState.STARTING -> sender.sendMessage("§7Отсчёт: §f${countdown ?: "—"}s")
            ArenaState.IN_GAME -> sender.sendMessage("§7Фаза: §f$phaseName§7, остаток: §f${rem?.toString() ?: "—"}s")
            else -> sender.sendMessage("§7Фаза: §f$phaseName")
        }

        sender.sendMessage("§7Игроки: §f${game.players.size}§7, наблюдатели: §f${game.getSpectators().size}")

        game.teams.forEach { team ->
            val online = team.getOnlinePlayers().map { it.name }
            val alive = team.getAlivePlayers(game).map { it.name }
            val status = when {
                team.isEliminated(game) -> "§c✗"
                team.coreDestroyed -> "§e⚠"
                else -> "§a✓"
            }
            sender.sendMessage(" ${status} ${team.color}${team.name}§7: §f${online.joinToString(", ")}" +
                    if (alive.size != online.size) " §7(живы: §f${alive.joinToString(", ")}§7)" else "")
        }
    }

    private fun formatGameLine(game: Game): String {
        val state = formatState(game.arena.state)
        val phaseName = game.getCurrentPhaseName()
        val rem = game.getRemainingPhaseSeconds()
        val countdown = game.getCountdownSeconds()

        val phasePart = when (game.arena.state) {
            ArenaState.STARTING -> "§7отсчёт §f${countdown ?: "—"}s"
            ArenaState.IN_GAME -> "§7фаза §f$phaseName§7 (§f${rem?.toString() ?: "—"}s§7)"
            else -> "§7$phaseName"
        }

        return "§e#${game.arena.id} §f${game.arena.mapConfig.displayName} §7| $state §7| игроков §f${game.players.size}§7/набл. §f${game.getSpectators().size} §7| $phasePart"
    }

    private fun formatState(state: ArenaState): String {
        return when (state) {
            ArenaState.WAITING -> "ОЖИДАНИЕ"
            ArenaState.STARTING -> "ОТСЧЁТ"
            ArenaState.IN_GAME -> "В ИГРЕ"
            ArenaState.ENDING -> "ЗАВЕРШЕНИЕ"
            ArenaState.RESETTING -> "СБРОС"
            else -> state.name
        }
    }
}
