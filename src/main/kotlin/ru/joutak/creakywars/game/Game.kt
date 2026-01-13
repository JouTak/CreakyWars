package ru.joutak.creakywars.game

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.event.HandlerList
import org.bukkit.scheduler.BukkitTask
import ru.joutak.creakywars.arenas.Arena
import ru.joutak.creakywars.arenas.ArenaState
import ru.joutak.creakywars.config.AdminConfig
import ru.joutak.creakywars.config.GameConfig
import ru.joutak.creakywars.config.ScenarioConfig
import ru.joutak.creakywars.core.CoreManager
import ru.joutak.creakywars.listeners.TeamChestListener
import ru.joutak.creakywars.resources.ResourceSpawner
import ru.joutak.creakywars.trading.TraderManager
import ru.joutak.creakywars.upgrades.BrainStationManager
import ru.joutak.creakywars.utils.MessageUtils
import ru.joutak.creakywars.utils.PluginManager
import ru.joutak.minigames.managers.MatchmakingManager
import java.util.UUID

@Suppress("DEPRECATION")
class Game(
    val arena: Arena,
    val teams: List<Team>
) {
    private val playerData = mutableMapOf<UUID, PlayerData>()
    private var currentPhaseIndex = 0
    private var gameTick = 0L
    private var phaseStartTick = 0L
    private var phaseStartTime = 0L
    private var gameStartTime = 0L
    private var gameTask: BukkitTask? = null
    private var startingTask: BukkitTask? = null
    private var cleanupTask: BukkitTask? = null
    private var cleanupStarted = false
    private var countdown = 10
    private val isDebugMode = AdminConfig.debugMode

    private val dayNightCycle = DayNightCycle(this)
    private val phaseBossBar = PhaseBossBar(this)
    private val teamScoreboard = TeamScoreboard(this)
    private val teamChestListener: TeamChestListener

    private val respawnTimers = mutableMapOf<UUID, BukkitTask>()

    private val spectators = mutableSetOf<UUID>()
    private val spectatorBackups = mutableMapOf<UUID, SpectatorBackup>()

    val players: List<Player>
        get() = playerData.keys.mapNotNull { Bukkit.getPlayer(it) }

    val spectatorsOnline: List<Player>
        get() = spectators.mapNotNull { Bukkit.getPlayer(it) }

    /**
     * Players + spectators who should receive match UI (bossbars/scoreboard) and announcements.
     */
    fun getAudiencePlayers(): List<Player> {
        val list = ArrayList<Player>(playerData.size + spectators.size)
        players.forEach { list.add(it) }
        spectatorsOnline.forEach { s ->
            if (list.none { it.uniqueId == s.uniqueId }) list.add(s)
        }
        return list
    }

    val activePlayers: List<Player>
        get() = players.filter { getPlayerData(it)?.isAlive == true }

    init {
        teamChestListener = TeamChestListener(this)
    }

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

    fun isSpectator(uuid: UUID): Boolean = spectators.contains(uuid)

    fun getSpectators(): List<Player> = spectatorsOnline.toList()

    fun getCurrentPhaseIndex(): Int = currentPhaseIndex

    fun getCurrentPhaseName(): String {
        return when {
            arena.state == ArenaState.WAITING -> "Ожидание"
            arena.state == ArenaState.STARTING -> "Отсчёт"
            arena.state == ArenaState.ENDING -> "Завершение"
            currentPhaseIndex >= ScenarioConfig.phases.size -> "Фазы завершены"
            else -> ScenarioConfig.phases[currentPhaseIndex].name
        }
    }


    fun getCurrentCreakingSpeedAmplifier(): Int {
        if (arena.state != ArenaState.IN_GAME) return 0
        if (currentPhaseIndex < 0 || currentPhaseIndex >= ScenarioConfig.phases.size) return 0
        return ScenarioConfig.phases[currentPhaseIndex].creakingSpeedAmplifier.coerceIn(0, 10)
    }

    fun getRemainingPhaseSeconds(): Long? {
        if (arena.state != ArenaState.IN_GAME) return null
        if (currentPhaseIndex >= ScenarioConfig.phases.size) return null
        val phase = ScenarioConfig.phases[currentPhaseIndex]

        val remainingTicks = getRemainingPhaseTicks(phase)
        return (remainingTicks / 20L).coerceAtLeast(0L)
    }

    fun getCountdownSeconds(): Int? {
        if (arena.state != ArenaState.STARTING) return null
        return countdown.coerceAtLeast(0)
    }


    private fun getRemainingPhaseTicks(phase: GamePhase): Long {
        val endAt = phase.endAtTick

        return if (endAt != null) {
            (endAt - gameTick).coerceAtLeast(0L)
        } else {
            val totalTicks = (phase.durationSeconds * 20L).coerceAtLeast(0L)
            val elapsedTicks = (gameTick - phaseStartTick).coerceAtLeast(0L)
            (totalTicks - elapsedTicks).coerceAtLeast(0L)
        }
    }

    private fun setPlayersGlowing(enabled: Boolean) {
        players.toList().forEach { player ->
            try {
                if (enabled) {
                    player.addPotionEffect(
                        PotionEffect(PotionEffectType.GLOWING, Int.MAX_VALUE, 0, false, false)
                    )
                } else {
                    player.removePotionEffect(PotionEffectType.GLOWING)
                }
            } catch (_: Exception) {
            }
        }
    }

    fun adminStartNow(): Boolean {
        if (arena.state != ArenaState.STARTING) return false
        if (startingTask == null) return false
        startingTask?.cancel()
        startingTask = null
        startGame()
        return true
    }

    fun adminSkipPhase(): Boolean {
        if (arena.state != ArenaState.IN_GAME) return false
        if (currentPhaseIndex >= ScenarioConfig.phases.size) return false

        try {
            endPhase(currentPhaseIndex)
        } catch (_: Exception) {
        }

        if (currentPhaseIndex + 1 < ScenarioConfig.phases.size) {
            startPhase(currentPhaseIndex + 1)
        } else {
            currentPhaseIndex = ScenarioConfig.phases.size
        }
        return true
    }

    fun adminSetPhase(index: Int): Boolean {
        if (arena.state != ArenaState.IN_GAME) return false
        if (index < 0 || index >= ScenarioConfig.phases.size) return false

        try {
            if (currentPhaseIndex < ScenarioConfig.phases.size) {
                endPhase(currentPhaseIndex)
            }
        } catch (_: Exception) {
        }

        startPhase(index)
        return true
    }

    fun adminEnd(reason: String? = null) {
        if (arena.state == ArenaState.ENDING) return
        if (!reason.isNullOrBlank()) {
            broadcastMessage("§cИгра принудительно завершена: §f$reason")
        } else {
            broadcastMessage("§cИгра принудительно завершена администратором!")
        }
        endGame(null)
    }

    fun addSpectator(player: Player): Boolean {
        val uuid = player.uniqueId
        if (playerData.containsKey(uuid)) {
            MessageUtils.sendMessage(player, "§cВы уже участвуете в этой игре.")
            return false
        }

        if (!spectatorBackups.containsKey(uuid)) {
            spectatorBackups[uuid] = SpectatorBackup.fromPlayer(player)
        }

        // Ensure admin is not stuck in queue / match state of the API.
        try {
            MatchmakingManager.removePlayer(player)
        } catch (_: Exception) {
        }

        spectators.add(uuid)

        // Safe spectator state
        try {
            player.inventory.clear()
            player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
            player.health = player.maxHealth
            player.foodLevel = 20
            player.saturation = 20f
            player.fireTicks = 0
            player.gameMode = GameMode.SPECTATOR
        } catch (_: Exception) {
        }

        try {
            player.teleport(getSpectatorViewLocation())
        } catch (_: Exception) {
        }

        teamScoreboard.addPlayer(player)
        teamScoreboard.update()
        phaseBossBar.addPlayer(player)

        MessageUtils.sendMessage(
            player,
            "§aВы наблюдаете за игрой §e#${arena.id}§a на карте §e${arena.mapConfig.displayName}§a."
        )
        return true
    }

    fun removeSpectator(player: Player, silent: Boolean = false, forceLobby: Boolean = true) {
        val uuid = player.uniqueId
        if (!spectators.contains(uuid)) return

        spectators.remove(uuid)

        try {
            phaseBossBar.removePlayer(player)
        } catch (_: Exception) {
        }
        try {
            teamScoreboard.removePlayer(player)
        } catch (_: Exception) {
        }

        val backup = spectatorBackups.remove(uuid)
        if (backup != null) {
            backup.restoreTo(player, forceLobby)
        } else {
            // Fallback reset
            val mainWorld = Bukkit.getWorlds().firstOrNull()
            if (mainWorld != null) {
                player.teleport(mainWorld.spawnLocation)
            }
            player.gameMode = GameMode.ADVENTURE
            player.inventory.clear()
            player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
            player.health = player.maxHealth
            player.foodLevel = 20
            player.saturation = 20f
            player.fireTicks = 0
        }

        if (!silent) {
            MessageUtils.sendMessage(player, "§aВы вышли из режима наблюдателя.")
        }
    }

    private fun getSpectatorViewLocation(): Location {
        // Simple safe point: (0.5, 75, 0.5) is used for in-game death spectate as well.
        return Location(arena.world, 0.5, 75.0, 0.5)
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
                getAudiencePlayers().forEach {
                    it.playSound(it.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1f)
                }
            }

            countdown--
        }, 0L, 1L)
    }

    fun startFromMatchmaking() {
        if (arena.state == ArenaState.IN_GAME) return
        if (arena.state == ArenaState.ENDING || arena.state == ArenaState.RESETTING) return

        try {
            startingTask?.cancel()
        } catch (_: Exception) {
        }
        startingTask = null
        countdown = 0

        startGame()
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
        gameTick = 0L
        phaseStartTick = 0L

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

        getAudiencePlayers().forEach { player ->
            teamScoreboard.addPlayer(player)
        }

        teamScoreboard.update()

        setupTeamChests()

        CoreManager.initializeCores(this, teams)

        ResourceSpawner.startSpawning(this)
        TraderManager.spawnTraders(this)

        // Cosmetic label for the perk station ("МОЗГ") above each base upgrade block.
        BrainStationManager.spawn(this)

        dayNightCycle.start()

        startPhase(0)

        gameTask = Bukkit.getScheduler().runTaskTimer(PluginManager.getPlugin(), Runnable {
            tick()
        }, 0L, 1L)

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

    private fun setupTeamChests() {
        if (teams.size != arena.mapConfig.teamChestLocations.size) {
            PluginManager.getLogger().warning("Количество команд (${teams.size}) не совпадает с количеством командных сундуков (${arena.mapConfig.teamChestLocations.size}) в конфиге!")
            return
        }

        teams.forEachIndexed { index, team ->
            val chestLoc = arena.mapConfig.teamChestLocations.getOrNull(index)?.toLocation(arena.world)
            if (chestLoc != null) {
                val block = chestLoc.block
                block.type = Material.CHEST
                teamChestListener.addTeamChest(block, team)
            }
        }

        arena.mapConfig.enderChestLocations.forEach { spawnLoc ->
            val loc = spawnLoc.toLocation(arena.world)
            loc.block.type = Material.ENDER_CHEST
        }
    }

    fun getActiveTeams(): List<Team> {
        return teams.filter { it.players.isNotEmpty() }
    }

    private fun tick() {
        gameTick++

        if (currentPhaseIndex < ScenarioConfig.phases.size) {
            val currentPhase = ScenarioConfig.phases[currentPhaseIndex]

            val endAt = currentPhase.endAtTick

            val totalTicks: Long
            val remainingTicks: Long
            val phaseFinished: Boolean

            if (endAt != null) {
                totalTicks = (endAt - phaseStartTick).coerceAtLeast(1L)
                remainingTicks = (endAt - gameTick).coerceAtLeast(0L)
                phaseFinished = gameTick >= endAt
            } else {
                totalTicks = (currentPhase.durationSeconds * 20L).coerceAtLeast(1L)
                val elapsedTicks = (gameTick - phaseStartTick).coerceAtLeast(0L)
                remainingTicks = (totalTicks - elapsedTicks).coerceAtLeast(0L)
                phaseFinished = elapsedTicks >= totalTicks
            }

            if (gameTick % 10L == 0L || phaseFinished) {
                phaseBossBar.updateProgress(remainingTicks, totalTicks)
            }

            if (phaseFinished) {
                endPhase(currentPhaseIndex)

                if (currentPhaseIndex + 1 < ScenarioConfig.phases.size) {
                    startPhase(currentPhaseIndex + 1)
                } else {
                    currentPhaseIndex++
                }
            }

            if (currentPhase.borderShrink && gameTick % 20L == 0L) {
                val border = arena.world.worldBorder
                if (border.size > currentPhase.borderFinalSize) {
                    border.size = maxOf(
                        currentPhase.borderFinalSize,
                        border.size - currentPhase.borderShrinkSpeed
                    )
                }
            }
        }

        if (gameTick % 20L == 0L) {
            checkWinCondition(currentPhaseIndex >= ScenarioConfig.phases.size)
        }
    }

    private fun startPhase(phaseIndex: Int) {
        currentPhaseIndex = phaseIndex
        phaseStartTime = System.currentTimeMillis()
        phaseStartTick = gameTick

        val phase = ScenarioConfig.phases[phaseIndex]

        ResourceSpawner.setMultiplier(this, phase.resourceMultiplier)

        phaseBossBar.create(phaseIndex)

        setPlayersGlowing(false)
        if (phase.glowPlayers) {
            setPlayersGlowing(true)
        }

        try {
            dayNightCycle.updateCreakingPhaseBuffs()
        } catch (_: Exception) {
        }

        if (!phase.respawnEnabled) {
            val teamsToDestroy = teams.filter { it.players.isNotEmpty() && !it.coreDestroyed }

            teamsToDestroy.forEach { team ->
                handleCoreDestroyed(team, null)
            }

            if (teamsToDestroy.isNotEmpty()) {
                broadcastMessage("§c§l>>> §6Все ядра были уничтожены! §cРеспавн отключен!")
            }
        }

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

        val spectatorLocation = org.bukkit.Location(arena.world, 0.0, 75.0, 0.0)

        org.bukkit.Bukkit.getScheduler().runTaskLater(ru.joutak.creakywars.utils.PluginManager.getPlugin(), Runnable {
            if (!player.isOnline) return@Runnable

            player.spigot().respawn()

            player.gameMode = GameMode.SPECTATOR

            player.teleport(spectatorLocation)

            if (isDebugMode) {
                startRespawnTimer(player, spectatorLocation)
                return@Runnable
            }

            if (team.coreDestroyed) {
                data.isAlive = false

                MessageUtils.sendMessage(player, "§c§lВы выбыли из игры!")
                player.sendTitle("§cИГРА ОКОНЧЕНА", "§7Ваше ядро было уничтожено", 10, 70, 20)

                if (killer != null) {
                    val killerData = getPlayerData(killer)
                    killerData?.addFinalKill()
                    broadcastMessage("§c☠ §e${killer.name} §6совершил финальное убийство!")
                }

                checkWinCondition()
                return@Runnable
            }

            if (team.canRespawn()) {
                team.decrementLives()
                startRespawnTimer(player, spectatorLocation)
            } else {
                data.isAlive = false
                MessageUtils.sendMessage(player, "§c§lВы выбыли из игры!")
                player.sendTitle("§cВЫ ПОГИБЛИ", "§7Жизни закончились", 10, 70, 20)

                if (killer != null) {
                    val killerData = getPlayerData(killer)
                    killerData?.addFinalKill()
                    broadcastMessage("§c☠ §e${killer.name} §6совершил финальное убийство!")
                }

                checkWinCondition()
            }
        }, 1L)
    }

    private fun transferResourcesToKiller(victim: Player, killer: Player) {
        val resourceTypes = ru.joutak.creakywars.config.GameConfig.resourceTypes.values
        val materialToResourceType = resourceTypes.associateBy { it.material }

        var totalTransferred = 0
        val transferredItems = mutableListOf<Pair<String, Int>>()

        for (i in 0 until victim.inventory.size) {
            val item = victim.inventory.getItem(i)
            if (item == null) continue

            val resourceType = materialToResourceType[item.type]
            if (resourceType != null) {
                val itemToTransfer = item.clone()
                val amountBefore = item.amount

                val leftover = killer.inventory.addItem(itemToTransfer)

                val transferredAmount = amountBefore - leftover.values.sumOf { it.amount }

                if (transferredAmount > 0) {
                    totalTransferred += transferredAmount

                    transferredItems.add(resourceType.displayName to transferredAmount)

                    val victimItem = victim.inventory.getItem(i)
                    if (victimItem != null) {
                        if (victimItem.amount <= transferredAmount) {
                            victim.inventory.setItem(i, null)
                        } else {
                            victimItem.amount -= transferredAmount
                        }
                    }
                }
            }
        }

        if (totalTransferred > 0) {
            val messageParts = transferredItems
                .groupBy { it.first }
                .map { (displayName, pairs) ->
                    val sum = pairs.sumOf { it.second }
                    "§e$sum§7x $displayName"
                }

            val itemsList = messageParts.joinToString(", ")
        }
    }


    private fun startRespawnTimer(player: Player, deathLocation: org.bukkit.Location) {
        respawnTimers[player.uniqueId]?.cancel()

        val data = getPlayerData(player) ?: return
        val team = data.team ?: return

        var delaySeconds = GameConfig.respawnDelaySeconds

        if (team.hasFastRespawn) {
            val fastRespawnTime = GameConfig.upgradeSettings["respawn_time"] as? Int ?: 10
            delaySeconds = fastRespawnTime
        }

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

    private fun checkWinCondition(phasesFinished: Boolean = false) {
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
            phasesFinished && aliveTeams.size > 1 -> {
                PluginManager.getLogger().info("Игра #${arena.id}: ничья по истечении фаз")
                endGame(null)
            }
        }
    }


    private fun endGame(winnerTeam: Team?) {
        // Allow re-enter: if something went wrong with scheduled cleanup, we can reschedule it.
        if (arena.state == ArenaState.ENDING) {
            cleanupTask?.cancel()
            cleanupTask = null
        }

        arena.state = ArenaState.ENDING

        try {
            startingTask?.cancel()
        } catch (_: Exception) {
        }
        startingTask = null

        try {
            gameTask?.cancel()
        } catch (_: Exception) {
        }
        gameTask = null

        respawnTimers.values.toList().forEach {
            try {
                it.cancel()
            } catch (_: Exception) {
            }
        }
        respawnTimers.clear()

        try {
            dayNightCycle.stop()
        } catch (_: Exception) {
        }

        try {
            ResourceSpawner.stopSpawning(this)
        } catch (_: Exception) {
        }

        try {
            TraderManager.removeTraders(this)
        } catch (_: Exception) {
        }

        try {
            BrainStationManager.remove(this)
        } catch (_: Exception) {
        }

        try {
            disableListeners()
        } catch (_: Exception) {
        }

        if (winnerTeam != null) {
            val winnerName = "${winnerTeam.color}${winnerTeam.name}"

            // Titles should be personal: winners see "Победа", everyone else sees "Поражение" / neutral.
            getAudiencePlayers().forEach { player ->
                val data = getPlayerData(player)
                when {
                    data?.team?.id == winnerTeam.id -> MessageUtils.sendTitle(player, "§6Победа!", "§e$winnerName")
                    spectators.contains(player.uniqueId) || data?.team == null ->
                        MessageUtils.sendTitle(player, "§eИгра окончена", "§7Победитель: §e$winnerName")
                    else -> MessageUtils.sendTitle(player, "§cПоражение!", "§7Победитель: §e$winnerName")
                }
            }

            broadcastMessage("§a▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬")
            broadcastMessage("§6§lПОБЕДИТЕЛЬ: $winnerName")
            displayStats()
            broadcastMessage("§a▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬")

            winnerTeam.getOnlinePlayers().forEach { player ->
                try {
                    player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
                } catch (_: Exception) {
                }
            }
        } else {
            broadcastTitle("§eНичья!", "§7Никто не победил")
            broadcastMessage("§7Игра завершилась ничьей!")
            displayStats()
        }

        // Ensure the game always proceeds to full cleanup and removal from GameManager.
        cleanupTask = Bukkit.getScheduler().runTaskLater(PluginManager.getPlugin(), Runnable {
            cleanup()
        }, 200L)
    }

    fun disableListeners() {
        HandlerList.unregisterAll(teamChestListener)
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
        if (cleanupStarted) return
        cleanupStarted = true
        cleanupTask = null

        // Expose to /creakywars games that we're actually cleaning up.
        arena.state = ArenaState.RESETTING

        try {
            BrainStationManager.removeWorld(arena.world.name)
        } catch (_: Exception) {
        }

        // Snapshot players before we clear internal maps.
        val playersSnapshot = players.toList()
        val spectatorsSnapshot = spectatorsOnline.toList()

        // Always attempt to remove the game from GameManager even if some cleanup step fails.
        try {
            // Restore / eject spectators first (their backups may depend on current world state).
            spectatorsSnapshot.forEach { spectator ->
                try {
                    removeSpectator(spectator, silent = true, forceLobby = true)
                } catch (_: Exception) {
                }
            }

            val lobbyWorld = Bukkit.getWorlds().firstOrNull()
            val lobbySpawn = lobbyWorld?.spawnLocation

            playersSnapshot.forEach { player ->
                try {
                    teamScoreboard.removePlayer(player)
                } catch (_: Exception) {
                }

                try {
                    if (lobbySpawn != null) {
                        player.teleport(lobbySpawn)
                    }
                } catch (_: Exception) {
                }

                try {
                    player.gameMode = GameMode.ADVENTURE
                    player.inventory.clear()
                    player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
                    player.health = 20.0
                    player.foodLevel = 20
                    player.saturation = 20f
                    player.fireTicks = 0
                } catch (_: Exception) {
                }
            }

            // Mark players as no longer participating in the running match (API side),
            // so they can queue again and lobby items can be restored.
            playersSnapshot.forEach { player ->
                try {
                    MatchmakingManager.removePlayer(player)
                } catch (_: Exception) {
                }
            }

            try {
                phaseBossBar.remove()
            } catch (_: Exception) {
            }

            try {
                CoreManager.clearCores(this)
            } catch (_: Exception) {
            }

            try {
                disableListeners()
            } catch (_: Exception) {
            }

            playerData.clear()
            spectators.clear()
            spectatorBackups.clear()

        } finally {
            try {
                GameManager.onGameEnd(this)
            } catch (_: Exception) {
            }
        }
    }

    fun forceEnd() {
        broadcastMessage("§cИгра принудительно завершена администратором!")
        endGame(null)
    }

    fun broadcastMessage(message: String) {
        MessageUtils.broadcastMessage(message, getAudiencePlayers())
    }

    private fun broadcastTitle(title: String, subtitle: String) {
        getAudiencePlayers().forEach { MessageUtils.sendTitle(it, title, subtitle) }
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
            broadcastTitle("${team.color}${team.name}", "§eядро уничтожено ${destroyer.name}!")
            broadcastMessage("§c§l⚠ §6Ядро команды ${team.color}${team.name} §6уничтожено! §c§l⚠")
        } else {
            broadcastTitle("§c${team.name}", "§eядро уничтожено!")
        }

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

        getAudiencePlayers().forEach {
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

        checkWinCondition()

        if (playerData.isEmpty()) {
            PluginManager.getLogger().info("Все игроки покинули игру на арене #${arena.id}")
            forceEnd()
        }
    }

    private data class SpectatorBackup(
        val location: Location,
        val gameMode: GameMode,
        val health: Double,
        val foodLevel: Int,
        val saturation: Float,
        val fireTicks: Int,
        val exp: Float,
        val level: Int,
        val contents: Array<ItemStack?>,
        val armorContents: Array<ItemStack?>,
        val offHand: ItemStack,
        val effects: List<PotionEffect>
    ) {
        companion object {
            fun fromPlayer(player: Player): SpectatorBackup {
                val contents = player.inventory.contents.map { it?.clone() }.toTypedArray()
                val armor = player.inventory.armorContents.map { it?.clone() }.toTypedArray()
                val offHand = player.inventory.itemInOffHand?.clone() ?: ItemStack(Material.AIR)
                val effects = player.activePotionEffects.map { it }

                return SpectatorBackup(
                    location = player.location.clone(),
                    gameMode = player.gameMode,
                    health = player.health,
                    foodLevel = player.foodLevel,
                    saturation = player.saturation,
                    fireTicks = player.fireTicks,
                    exp = player.exp,
                    level = player.level,
                    contents = contents,
                    armorContents = armor,
                    offHand = offHand,
                    effects = effects
                )
            }
        }

        fun restoreTo(player: Player, forceLobby: Boolean) {
            try {
                player.inventory.clear()
            } catch (_: Exception) {
            }

            try {
                player.inventory.contents = contents.map { it?.clone() }.toTypedArray()
                player.inventory.armorContents = armorContents.map { it?.clone() }.toTypedArray()
                player.inventory.setItemInOffHand(offHand.clone())
            } catch (_: Exception) {
            }

            try {
                player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
                effects.forEach { player.addPotionEffect(it) }
            } catch (_: Exception) {
            }

            try {
                player.health = health.coerceAtMost(player.maxHealth)
                player.foodLevel = foodLevel
                player.saturation = saturation
                player.fireTicks = fireTicks
                player.exp = exp
                player.level = level
                player.gameMode = gameMode
            } catch (_: Exception) {
            }

            val fallback = Bukkit.getWorlds().firstOrNull()?.spawnLocation
            val target = when {
                forceLobby -> fallback
                location.world == null -> fallback
                location.world!!.name.startsWith("cw_game_") -> fallback
                else -> location
            }

            if (target != null) {
                try {
                    player.teleport(target)
                } catch (_: Exception) {
                }
            }
        }
    }
}
