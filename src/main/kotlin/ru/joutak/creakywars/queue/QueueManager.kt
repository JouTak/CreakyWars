package ru.joutak.creakywars.queue

import org.bukkit.entity.Player
import ru.joutak.creakywars.config.AdminConfig
import ru.joutak.creakywars.game.GameManager
import ru.joutak.creakywars.spartakiada.SpartakiadaManager
import ru.joutak.creakywars.utils.MessageUtils
import ru.joutak.creakywars.utils.PluginManager

object QueueManager {
    private val queue = Queue()
    
    fun init() {
        PluginManager.getLogger().info("QueueManager инициализирован!")
    }
    
    fun addPlayer(player: Player) {
        if (GameManager.isInGame(player)) {
            MessageUtils.sendMessage(player, "§cВы уже находитесь в игре!")
            return
        }
        
        if (queue.contains(player)) {
            MessageUtils.sendMessage(player, "§cВы уже в очереди!")
            return
        }
        
        queue.add(player)
        MessageUtils.sendMessage(player, "§aВы добавлены в очередь! §7(${queue.size()}/${AdminConfig.maxPlayers})")

        queue.getPlayers().forEach { queuedPlayer ->
            if (queuedPlayer != player) {
                MessageUtils.sendActionBar(queuedPlayer, "§e${player.name} присоединился к очереди! §7(${queue.size()}/${AdminConfig.maxPlayers})")
            }
        }
        
        checkQueue()
    }
    
    fun removePlayer(player: Player) {
        if (queue.contains(player)) {
            queue.remove(player)
            MessageUtils.sendMessage(player, "§cВы покинули очередь!")
            queue.getPlayers().forEach { queuedPlayer ->
                MessageUtils.sendActionBar(queuedPlayer, "§e${player.name} покинул очередь! §7(${queue.size()}/${AdminConfig.maxPlayers})")
            }
        }
    }

    fun checkQueue() {
        val queueSize = queue.size()
        val minPlayers = AdminConfig.getMinPlayersToStart()

        if (AdminConfig.debugMode) {
            PluginManager.getLogger().info("§e[DEBUG] В очереди: $queueSize, минимум: $minPlayers")
        }

        if (queueSize >= minPlayers && GameManager.canStartNewGame()) {
            startGame()
        }
    }
    
    private fun startGame() {
        val players = queue.getPlayers().take(AdminConfig.maxPlayers)
        
        if (players.isEmpty()) return

        val availableMaps = AdminConfig.availableMaps
        if (availableMaps.isEmpty()) {
            PluginManager.getLogger().severe("Нет доступных карт!")
            return
        }
        
        val mapName = availableMaps.random()
        val spartakiadaMode = SpartakiadaManager.isEnabled()

        val game = GameManager.createGame(players, mapName, spartakiadaMode)
        
        if (game != null) {
            players.forEach { queue.remove(it) }
            game.startCountdown()
        } else {
            PluginManager.getLogger().severe("Не удалось создать игру!")
        }
    }
    
    fun getQueueSize(): Int = queue.size()
    
    fun isInQueue(player: Player): Boolean = queue.contains(player)
}