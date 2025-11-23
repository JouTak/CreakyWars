@file:Suppress("DEPRECATION")

package ru.joutak.creakywars.queue

import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.joutak.creakywars.config.AdminConfig
import ru.joutak.creakywars.game.GameManager
import ru.joutak.creakywars.game.Team
import ru.joutak.creakywars.gui.TeamSelectionGui
import ru.joutak.creakywars.spartakiada.SpartakiadaManager
import ru.joutak.creakywars.utils.MessageUtils
import ru.joutak.creakywars.utils.PluginManager

object QueueManager {
    private val queue = mutableListOf<Player>()
    private val playerTeamPreference = mutableMapOf<Player, Int>() // Хранит выбор команды игрока

    fun init() {
        PluginManager.getLogger().info("QueueManager инициализирован!")
    }

    fun addPlayer(player: Player) {
        if (queue.contains(player)) {
            MessageUtils.sendMessage(player, "§cВы уже в очереди!")
            return
        }

        val currentGame = GameManager.getGame(player)
        if (currentGame != null) {
            MessageUtils.sendMessage(player, "§cВы уже в игре!")
            return
        }

        if (SpartakiadaManager.isEnabled()) {
            val playerTeam = SpartakiadaManager.getPlayerTeam(player)

            if (playerTeam == null) {
                MessageUtils.sendMessage(player, "§cВы не состоите в команде!")
                return
            }

            val teamId = playerTeam.toIntOrNull()
            if (teamId == null) {
                MessageUtils.sendMessage(player, "§cОшибка определения команды!")
                return
            }

            if (!SpartakiadaManager.isWholeTeamOnline(teamId)) {
                val onlineSize = SpartakiadaManager.getOnlineTeamSize(teamId)
                val totalSize = SpartakiadaManager.getTeamSize(teamId)
                MessageUtils.sendMessage(
                    player,
                    "§cНе все игроки вашей команды на сервере! Онлайн: §e$onlineSize§c/§e$totalSize"
                )
                return
            }

            val teamPlayers = SpartakiadaManager.getTeamPlayers(playerTeam).filter { it.isOnline }
            val playersInGame = teamPlayers.filter { GameManager.getGame(it) != null }
            if (playersInGame.isNotEmpty()) {
                MessageUtils.sendMessage(player, "§cНекоторые игроки вашей команды уже в игре!")
                return
            }

            if (teamPlayers.any { queue.contains(it) }) {
                MessageUtils.sendMessage(player, "§cВаша команда уже в очереди!")
                return
            }

            val teamDisplayName = SpartakiadaManager.getTeamDisplayName(teamId)
            teamPlayers.forEach { teamPlayer ->
                queue.add(teamPlayer)
                MessageUtils.sendMessage(teamPlayer, "§aВаша команда §e$teamDisplayName §aдобавлена в очередь!")
            }

            queue.forEach { p ->
                MessageUtils.sendMessage(p, "§aВ очереди: §e${queue.size} §aигроков из ${AdminConfig.getMinPlayersToStart()} необходимых")
            }

            PluginManager.getLogger().info("Команда $teamDisplayName добавлена в очередь. Всего игроков: ${queue.size}")

        } else if (AdminConfig.debugMode) {
            queue.add(player)
            MessageUtils.sendMessage(player, "§a[DEBUG] Вы добавлены в очередь! Позиция: §e${queue.size}")

            PluginManager.getLogger().info("[DEBUG] ${player.name} добавлен в очередь. Всего: ${queue.size}")
        } else {
            showTeamSelection(player)
            return
        }

        checkQueue()
    }

    private fun showTeamSelection(player: Player) {
        val availableTeams = List(AdminConfig.teamsCount) { index ->
            Team(
                id = index,
                name = getTeamName(index),
                color = getTeamChatColor(index),
                woolColor = getTeamWoolColor(index)
            )
        }

        MessageUtils.sendMessage(player, "§eВыберите команду...")
        TeamSelectionGui.open(player, availableTeams) { selectedPlayer, selectedTeam ->
            addPlayerWithTeam(selectedPlayer, selectedTeam.id)
        }
    }

    private fun addPlayerWithTeam(player: Player, teamId: Int) {
        if (queue.contains(player)) {
            MessageUtils.sendMessage(player, "§cВы уже в очереди!")
            return
        }

        val currentGame = GameManager.getGame(player)
        if (currentGame != null) {
            MessageUtils.sendMessage(player, "§cВы уже в игре!")
            return
        }

        playerTeamPreference[player] = teamId

        queue.add(player)
        MessageUtils.sendMessage(player, "§aВы добавлены в очередь! Позиция: §e${queue.size}")
        MessageUtils.sendMessage(player, "§aВыбранная команда: ${getTeamColorCode(teamId)}${getTeamName(teamId)}")

        PluginManager.getLogger().info("${player.name} выбрал команду $teamId. Всего в очереди: ${queue.size}")

        checkQueue()
    }

    fun getPlayerTeamPreference(player: Player): Int? {
        return playerTeamPreference[player]
    }

    fun clearPlayerTeamPreference(player: Player) {
        playerTeamPreference.remove(player)
    }

    fun removePlayer(player: Player) {
        if (SpartakiadaManager.isEnabled()) {
            val playerTeam = SpartakiadaManager.getPlayerTeam(player)

            if (playerTeam == null) {
                if (queue.remove(player)) {
                    MessageUtils.sendMessage(player, "§cВы покинули очередь")
                }
                return
            }

            val teamId = playerTeam.toInt()
            val teamPlayers = SpartakiadaManager.getTeamPlayers(playerTeam)
            val teamDisplayName = SpartakiadaManager.getTeamDisplayName(teamId)

            teamPlayers.forEach { teamPlayer ->
                if (queue.remove(teamPlayer)) {
                    MessageUtils.sendMessage(teamPlayer, "§cВаша команда §e$teamDisplayName §cпокинула очередь")
                }
            }

            PluginManager.getLogger().info("Команда $teamDisplayName покинула очередь. Осталось: ${queue.size}")
        } else {
            // ОБЫЧНЫЙ РЕЖИМ и РЕЖИМ ОТЛАДКИ
            if (queue.remove(player)) {
                playerTeamPreference.remove(player)
                MessageUtils.sendMessage(player, "§cВы покинули очередь")

                PluginManager.getLogger().info("${player.name} покинул очередь. Осталось: ${queue.size}")
            }
        }
    }

    fun getQueue(): List<Player> = queue.toList()

    fun getQueueSize(): Int = queue.size

    fun isInQueue(player: Player): Boolean = queue.contains(player)

    fun checkQueue() {
        val minPlayers = if (AdminConfig.debugMode) {
            1
        } else {
            AdminConfig.getMinPlayersToStart()
        }

        PluginManager.getLogger().info("Проверка очереди: ${queue.size}/$minPlayers, можно создать игру: ${GameManager.canStartNewGame()}")

        if (queue.size >= minPlayers && GameManager.canStartNewGame()) {
            PluginManager.getLogger().info("✓ Запуск игры с ${queue.size} игроками!")
            startGame()
        }
    }

    fun forceStart(): Boolean {
        if (queue.isEmpty()) {
            PluginManager.getLogger().warning("Очередь пуста!")
            return false
        }

        if (!GameManager.canStartNewGame()) {
            PluginManager.getLogger().warning("Достигнут лимит игр!")
            return false
        }

        PluginManager.getLogger().info("Принудительный запуск игры с ${queue.size} игроками")
        startGame()
        return true
    }

    private fun startGame() {
        val players = queue.take(AdminConfig.maxPlayers).toList()

        if (players.isEmpty()) {
            PluginManager.getLogger().warning("Список игроков пуст!")
            return
        }

        val availableMaps = AdminConfig.availableMaps
        if (availableMaps.isEmpty()) {
            PluginManager.getLogger().severe("Нет доступных карт!")
            return
        }

        val mapName = availableMaps.random()
        val spartakiadaMode = SpartakiadaManager.isEnabled()

        PluginManager.getLogger().info("Создание игры: ${players.size} игроков, карта: $mapName, спартакиада: $spartakiadaMode")

        val game = GameManager.createGame(players, mapName, spartakiadaMode)

        if (game != null) {
            players.forEach {
                queue.remove(it)
                playerTeamPreference.remove(it)
            }

            PluginManager.getLogger().info("✓ Игра создана успешно!")
            game.startCountdown()
        } else {
            PluginManager.getLogger().severe("✗ Не удалось создать игру!")
        }
    }

    fun clearQueue() {
        queue.clear()
        playerTeamPreference.clear()
        PluginManager.getLogger().info("Очередь очищена")
    }

    private fun getTeamName(teamId: Int): String {
        return when (teamId) {
            0 -> "Оранжевые"
            1 -> "Синие"
            2 -> "Розовые"
            3 -> "Зелёные"
            else -> "Команда $teamId"
        }
    }

    private fun getTeamChatColor(teamId: Int): ChatColor {
        return when (teamId) {
            0 -> ChatColor.GOLD
            1 -> ChatColor.BLUE
            2 -> ChatColor.LIGHT_PURPLE
            3 -> ChatColor.GREEN
            else -> ChatColor.WHITE
        }
    }

    private fun getTeamColorCode(teamId: Int): String {
        return when (teamId) {
            0 -> "§6"
            1 -> "§9"
            2 -> "§d"
            3 -> "§a"
            else -> "§f"
        }
    }

    private fun getTeamWoolColor(teamId: Int): Material {
        return when (teamId) {
            0 -> Material.ORANGE_TERRACOTTA
            1 -> Material.BLUE_TERRACOTTA
            2 -> Material.PINK_TERRACOTTA
            3 -> Material.GREEN_TERRACOTTA
            else -> Material.WHITE_TERRACOTTA
        }
    }
}