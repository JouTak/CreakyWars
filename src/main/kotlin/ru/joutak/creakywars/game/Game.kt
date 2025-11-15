package ru.joutak.creakywars.game

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import ru.joutak.creakywars.arenas.Arena
import ru.joutak.creakywars.arenas.ArenaState
import ru.joutak.creakywars.config.AdminConfig
import ru.joutak.creakywars.config.GameConfig
import ru.joutak.creakywars.config.ScenarioConfig
import ru.joutak.creakywars.core.CoreManager
import ru.joutak.creakywars.resources.ResourceSpawner
import ru.joutak.creakywars.trading.TraderManager
import ru.joutak.creakywars.utils.MessageUtils
import ru.joutak.creakywars.utils.PluginManager
import java.util.UUID

class Game(
    val arena: Arena,
    val teams: List<Team>
) {
    private val playerData = mutableMapOf<UUID, PlayerData>()
    private var currentPhaseIndex = 0
    private var phaseStartTime = 0L
    private var gameStartTime = 0L
    private var gameTask: BukkitTask? = null
    private var startingTask: BukkitTask? = null
    private var countdown = 10
    private val isDebugMode = AdminConfig.debugMode

    private val dayNightCycle = DayNightCycle(this)
    private val phaseBossBar = PhaseBossBar(this)
    private val teamScoreboard = TeamScoreboard(this)

    private val respawnTimers = mutableMapOf<UUID, BukkitTask>()

    val players: List<Player>
        get() = playerData.keys.mapNotNull { Bukkit.getPlayer(it) }

    val activePlayers: List<Player>
        get() = players.filter { getPlayerData(it)?.isAlive == true }

    fun addPlayer(player: Player, team: Team) {
        val data = PlayerData(player.uniqueId, team)
        playerData[player.uniqueId] = data
        team.addPlayer(player.uniqueId)
    }

    fun getPlayerData(player: Player): PlayerData? {
        return playerData[player.uniqueId]
    }

    fun getTeam(player: Player): Team? {
        return getPlayerData(player)?.team
    }

    fun startCountdown() {
        arena.state = ArenaState.STARTING

        startingTask = Bukkit.getScheduler().runTaskTimer(PluginManager.getPlugin(), Runnable {
            if (countdown <= 0) {
                startingTask?.cancel()
                startGame()
                return@Runnable
            }

            if (countdown <= 5 || countdown % 10 == 0) {
                broadcastMessage("§eИгра начнется через §c$countdown §eсекунд!")
                players.forEach {
                    it.playSound(it.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1f)
                }
            }

            countdown--
        }, 0L, 20L)
    }

    fun cancelCountdown() {
        startingTask?.cancel()
        startingTask = null
        countdown = 10
        arena.state = ArenaState.WAITING
        broadcastMessage("§cНедостаточно игроков! Отсчёт отменён.")
    }

    private fun startGame() {
        arena.state = ArenaState.IN_GAME
        gameStartTime = System.currentTimeMillis()
        phaseStartTime = gameStartTime

        val activeTeams = teams.filter { it.players.isNotEmpty() }

        if (AdminConfig.debugMode) {
            PluginManager.getLogger().info("[DEBUG] Активных команд: ${activeTeams.size} из ${teams.size}")
        }

        activeTeams.forEachIndexed { index, team ->
            val spawnLocation = arena.mapConfig.teamSpawns.getOrNull(teams.indexOf(team))
            if (spawnLocation != null) {
                team.getOnlinePlayers().forEach { player ->
                    player.teleport(spawnLocation.toLocation(arena.world))
                    player.gameMode = GameMode.SURVIVAL
                    player.health = 20.0
                    player.foodLevel = 20
                    player.inventory.clear()

                    val playerData = getPlayerData(player)
                    if (playerData != null) {
                        val loadout = PlayerLoadout(player, team)
                        playerData.loadout = loadout
                        loadout.giveDefaultLoadout()
                    }
                }
            }
        }

        players.forEach { player ->
            teamScoreboard.addPlayer(player)
        }

        CoreManager.initializeCores(this, activeTeams)
        ResourceSpawner.startSpawning(this)
        TraderManager.spawnTraders(this)

        dayNightCycle.start()

        startPhase(0)

        gameTask = Bukkit.getScheduler().runTaskTimer(PluginManager.getPlugin(), Runnable {
            tick()
        }, 0L, 20L)

        broadcastTitle("§6Игра началась!", "§eУдачи!")
        broadcastMessage("§a▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬")
        broadcastMessage("§6§lCREAKY WARS")
        broadcastMessage("")
        broadcastMessage("§eЗащищайте §cсердце скрипуна§e вашей команды!")
        broadcastMessage("§eСобирайте §bрезину§e и покупайте улучшения!")
        broadcastMessage("§eНочью собирайте §dглазалии§e, но берегитесь §5Скрипуна§e!")
        broadcastMessage("§eУничтожьте §cсердца§e всех противников!")
        broadcastMessage("§a▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬")
    }

    fun getActiveTeams(): List<Team> {
        return teams.filter { it.players.isNotEmpty() }
    }

    private fun tick() {
        val currentTime = System.currentTimeMillis()
        val timeSincePhaseStart = (currentTime - phaseStartTime) / 1000

        if (currentPhaseIndex < ScenarioConfig.phases.size) {
            val currentPhase = ScenarioConfig.phases[currentPhaseIndex]

            val remainingTime = currentPhase.durationSeconds - timeSincePhaseStart
            phaseBossBar.updateProgress(remainingTime, currentPhase.durationSeconds)

            if (timeSincePhaseStart >= currentPhase.durationSeconds) {
                endPhase(currentPhaseIndex)

                if (currentPhaseIndex + 1 < ScenarioConfig.phases.size) {
                    startPhase(currentPhaseIndex + 1)
                }
            }

            if (currentPhase.borderShrink) {
                val border = arena.world.worldBorder
                if (border.size > currentPhase.borderFinalSize) {
                    border.size = maxOf(
                        currentPhase.borderFinalSize,
                        border.size - currentPhase.borderShrinkSpeed
                    )
                }
            }

            checkWinCondition()
        }

        checkWinCondition()
    }

    private fun startPhase(phaseIndex: Int) {
        currentPhaseIndex = phaseIndex
        phaseStartTime = System.currentTimeMillis()

        val phase = ScenarioConfig.phases[phaseIndex]

        ResourceSpawner.setMultiplier(this, phase.resourceMultiplier)

        phaseBossBar.create(phaseIndex)

        if (phase.startMessage.isNotEmpty()) {
            broadcastTitle("§6${phase.name}", "§e${phase.startMessage}")
            broadcastMessage("§6§l>>> §e${phase.startMessage}")
        }

        PluginManager.getLogger().info("Игра #${arena.id}: начата фаза ${phase.name}")
    }

    private fun endPhase(phaseIndex: Int) {
        val phase = ScenarioConfig.phases[phaseIndex]

        if (phase.endMessage.isNotEmpty()) {
            broadcastMessage("§c§l>>> §e${phase.endMessage}")
        }

        PluginManager.getLogger().info("Игра #${arena.id}: завершена фаза ${phase.name}")
    }

    fun canRespawn(): Boolean {
        if (currentPhaseIndex >= ScenarioConfig.phases.size) return false
        return ScenarioConfig.phases[currentPhaseIndex].respawnEnabled
    }

    fun handlePlayerDeath(player: Player, killer: Player?) {
        val data = getPlayerData(player) ?: return
        val team = data.team ?: return

        data.addDeath()

        if (killer != null) {
            val killerData = getPlayerData(killer)
            killerData?.addKill()
            broadcastMessage("${team.color}${player.name} §7был убит игроком ${killerData?.team?.color}${killer.name}")
            transferResourcesToKiller(player, killer)
        } else {
            broadcastMessage("${team.color}${player.name} §7погиб")
        }

        if (isDebugMode) {
            startRespawnTimer(player, player.location)
            return
        }

        if (team.coreDestroyed) {
            data.isAlive = false
            player.gameMode = GameMode.SPECTATOR

            MessageUtils.sendMessage(player, "§c§lВы выбыли из игры!")

            if (killer != null) {
                val killerData = getPlayerData(killer)
                killerData?.addFinalKill()
                broadcastMessage("§c☠ §e${killer.name} §6совершил финальное убийство!")
            }

            checkWinCondition()
            return
        }

        if (!canRespawn()) {
            if (team.canRespawn()) {
                team.decrementLives()
                startRespawnTimer(player, player.location)
            } else {
                data.isAlive = false
                player.gameMode = GameMode.SPECTATOR

                MessageUtils.sendMessage(player, "§c§lВы выбыли из игры!")

                if (killer != null) {
                    val killerData = getPlayerData(killer)
                    killerData?.addFinalKill()
                    broadcastMessage("§c☠ §e${killer.name} §6совершил финальное убийство!")
                }
            }
        } else {
            startRespawnTimer(player, player.location)
        }
    }

    private fun transferResourcesToKiller(victim: Player, killer: Player) {
        val resourceMaterials = setOf(
            Material.RESIN_CLUMP,
            Material.RESIN_BRICK,
            Material.RESIN_BRICKS,
            Material.OPEN_EYEBLOSSOM
        )

        var totalTransferred = 0
        val transferredItems = mutableListOf<String>()

        victim.inventory.contents.forEach { item ->
            if (item != null && resourceMaterials.contains(item.type)) {
                val amount = item.amount
                val displayName = when (item.type) {
                    Material.RESIN_CLUMP -> "§7Резина"
                    Material.RESIN_BRICK -> "§6Прочная резина"
                    Material.RESIN_BRICKS -> "§dРезиновый блок"
                    Material.OPEN_EYEBLOSSOM -> "§2Открытая глазалия"
                    else -> item.type.name
                }

                val leftover = killer.inventory.addItem(item.clone())

                if (leftover.isEmpty()) {
                    totalTransferred += amount
                    transferredItems.add("§e$amount§7x $displayName")
                } else {
                    val transferred = amount - leftover.values.sumOf { it.amount }
                    if (transferred > 0) {
                        totalTransferred += transferred
                        transferredItems.add("§e$transferred§7x $displayName")
                    }
                }
            }
        }
    }


    private fun startRespawnTimer(player: Player, deathLocation: org.bukkit.Location) {
        respawnTimers[player.uniqueId]?.cancel()

        val data = getPlayerData(player) ?: return
        val team = data.team ?: return

        val delaySeconds = GameConfig.respawnDelaySeconds

        if (GameConfig.respawnSpectatorMode) {
            player.gameMode = GameMode.SPECTATOR
            player.teleport(deathLocation)
        }

        var remainingSeconds = delaySeconds

        val timerTask = Bukkit.getScheduler().runTaskTimer(PluginManager.getPlugin(), Runnable {
            if (team.coreDestroyed) {
                respawnTimers[player.uniqueId]?.cancel()
                respawnTimers.remove(player.uniqueId)

                data.isAlive = false
                player.gameMode = GameMode.SPECTATOR

                MessageUtils.sendMessage(player, "§c§lВаше ядро уничтожено!")
                MessageUtils.sendTitle(player, "§c☠ ВЫБЫЛИ ☠", "§eВаше ядро уничтожено")
                return@Runnable
            }

            if (!team.canRespawn()) {
                respawnTimers[player.uniqueId]?.cancel()
                respawnTimers.remove(player.uniqueId)

                data.isAlive = false
                player.gameMode = GameMode.SPECTATOR

                MessageUtils.sendMessage(player, "§c§lУ команды закончились жизни!")
                return@Runnable
            }

            if (remainingSeconds <= 0) {
                respawnTimers[player.uniqueId]?.cancel()
                respawnTimers.remove(player.uniqueId)
                respawnPlayer(player)
                return@Runnable
            }

            MessageUtils.sendTitle(
                player,
                "§c☠ §fВы погибли §c☠",
                "§eВозрождение через: §c$remainingSeconds сек."
            )

            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1f)
            remainingSeconds--
        }, 0L, 20L)

        respawnTimers[player.uniqueId] = timerTask
    }


    @Suppress("DEPRECATION")
    private fun respawnPlayer(player: Player) {
        val data = getPlayerData(player) ?: return
        val team = data.team ?: return
        val spawnIndex = teams.indexOf(team)
        val spawnLocation = arena.mapConfig.teamSpawns.getOrNull(spawnIndex)

        if (spawnLocation != null) {
            Bukkit.getScheduler().runTaskLater(PluginManager.getPlugin(), Runnable {
                player.teleport(spawnLocation.toLocation(arena.world))
                player.gameMode = GameMode.SURVIVAL
                player.health = 20.0
                player.foodLevel = 20

                data.loadout?.restoreAfterDeath()

                MessageUtils.sendMessage(player, "§aВы возродились!")
                MessageUtils.sendTitle(player, "§aВозрождение!", "§eУдачи!")
                player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f)
            }, 1L)
        }
    }

    private fun checkWinCondition() {
        val aliveTeams = teams.filter { !it.isEliminated(this) }

        if (isDebugMode) {
            val currentTime = System.currentTimeMillis()
            if ((currentTime - gameStartTime) / 1000 % 5 == 0L) {
                PluginManager.getLogger().info("[DEBUG] Проверка условий победы:")
                teams.forEach { team ->
                    val onlinePlayers = team.getOnlinePlayers()
                    val alivePlayers = team.getAlivePlayers(this)
                    PluginManager.getLogger().info(
                        "[DEBUG]   ${team.name}: ядро=${!team.coreDestroyed}, " +
                                "онлайн=${onlinePlayers.size}, живых=${alivePlayers.size}, " +
                                "выбыла=${team.isEliminated(this)}"
                    )
                }
                broadcastMessage("§7[DEBUG] Команд в игре: ${aliveTeams.size} из ${teams.size}")
            }
            return
        }

        when {
            aliveTeams.size == 1 -> {
                PluginManager.getLogger().info("Игра #${arena.id}: побеждает команда ${aliveTeams.first().name}")
                endGame(aliveTeams.first())
            }
            aliveTeams.isEmpty() -> {
                PluginManager.getLogger().info("Игра #${arena.id}: ничья, все команды выбыли")
                endGame(null)
            }
        }
    }

    private fun endGame(winnerTeam: Team?) {
        arena.state = ArenaState.ENDING
        gameTask?.cancel()

        respawnTimers.values.forEach { it.cancel() }
        respawnTimers.clear()

        dayNightCycle.stop()

        ResourceSpawner.stopSpawning(this)
        TraderManager.removeTraders(this)

        if (winnerTeam != null) {
            broadcastTitle("§6Победа!", "§e${winnerTeam.color}${winnerTeam.name}")
            broadcastMessage("§a▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬")
            broadcastMessage("§6§lПОБЕДА: ${winnerTeam.color}${winnerTeam.name}")
            broadcastMessage("")
            displayStats()
            broadcastMessage("§a▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬")

            winnerTeam.getOnlinePlayers().forEach { player ->
                player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
            }
        } else {
            broadcastTitle("§eНичья!", "§7Никто не победил")
            broadcastMessage("§7Игра завершилась ничьей!")
        }

        Bukkit.getScheduler().runTaskLater(PluginManager.getPlugin(), Runnable {
            cleanup()
        }, 200L)
    }

    private fun displayStats() {
        broadcastMessage("§e§lТоп игроков:")

        val sortedPlayers = playerData.values
            .sortedByDescending { it.kills }
            .take(5)

        sortedPlayers.forEachIndexed { index, data ->
            val player = Bukkit.getPlayer(data.uuid)
            val teamColor = data.team?.color?.toString() ?: "§f"
            broadcastMessage("  ${index + 1}. $teamColor${player?.name ?: "Unknown"} §7- §e${data.kills} убийств, ${data.finalKills} финальных")
        }
    }

    private fun cleanup() {
        players.forEach { player ->
            teamScoreboard.removePlayer(player)
            player.teleport(Bukkit.getWorlds().first().spawnLocation)
            player.gameMode = GameMode.ADVENTURE
            player.inventory.clear()
            player.health = 20.0
            player.foodLevel = 20
        }

        phaseBossBar.remove()
        CoreManager.clearCores(this)

        playerData.clear()

        GameManager.onGameEnd(this)
    }

    fun forceEnd() {
        broadcastMessage("§cИгра принудительно завершена администратором!")
        endGame(null)
    }

    fun broadcastMessage(message: String) {
        MessageUtils.broadcastMessage(message, players)
    }

    private fun broadcastTitle(title: String, subtitle: String) {
        players.forEach { MessageUtils.sendTitle(it, title, subtitle) }
    }

    fun debugEnd(winner: Team? = null) {
        if (!isDebugMode) {
            return
        }

        broadcastMessage("§e[DEBUG] Игра завершена администратором")
        endGame(winner)
    }

    fun handleCoreDestroyed(team: Team, destroyer: Player?) {
        team.coreDestroyed = true

        if (destroyer != null) {
            broadcastTitle("§c${team.name}", "§eядро уничтожено ${destroyer.name}!")
        } else {
            broadcastTitle("§c${team.name}", "§eядро уничтожено!")
        }

        broadcastMessage("§c§l⚠ §6Ядро команды ${team.color}${team.name} §6уничтожено! §c§l⚠")

        team.getOnlinePlayers().forEach { player ->
            val data = getPlayerData(player)

            if (data?.isAlive == true) {
                MessageUtils.sendMessage(player, "§c§l⚠ ВАШЕ ЯДРО УНИЧТОЖЕНО!")
                MessageUtils.sendMessage(player, "§eПосле смерти вы не возродитесь!")
                player.playSound(player.location, Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 0.8f)
            } else {
                data?.isAlive = false
                player.gameMode = GameMode.SPECTATOR
                MessageUtils.sendMessage(player, "§c§lВы выбыли из игры!")
            }
        }

        team.players.forEach { uuid ->
            respawnTimers[uuid]?.cancel()
            respawnTimers.remove(uuid)
        }

        teamScoreboard.update()

        players.forEach {
            it.playSound(it.location, Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1f)
        }

        checkWinCondition()
    }

    fun getDayNightCycle(): DayNightCycle = dayNightCycle

    fun removePlayer(player: Player) {
        val uuid = player.uniqueId

        playerData.remove(uuid)

        respawnTimers[uuid]?.cancel()
        respawnTimers.remove(uuid)

        val team = teams.firstOrNull { it.hasPlayer(uuid) }
        team?.removePlayer(uuid)

        teamScoreboard.removePlayer(player)

        val mainWorld = Bukkit.getWorlds().firstOrNull()
        if (mainWorld != null) {
            player.teleport(mainWorld.spawnLocation)
        }

        player.inventory.clear()
        player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
        player.health = player.maxHealth
        player.foodLevel = 20
        player.saturation = 20f
        player.fireTicks = 0
        player.gameMode = GameMode.ADVENTURE

        if (AdminConfig.debugMode) {
            PluginManager.getLogger().info("[DEBUG] Игрок ${player.name} удален из игры")
        }

        if (playerData.isEmpty()) {
            PluginManager.getLogger().info("Все игроки покинули игру на арене #${arena.id}")
            forceEnd()
        }
    }
}