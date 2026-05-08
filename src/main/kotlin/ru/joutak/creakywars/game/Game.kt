package ru.joutak.creakywars.game

import org.bukkit.*
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Directional
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import ru.joutak.creakywars.arenas.Arena
import ru.joutak.creakywars.arenas.ArenaManager
import ru.joutak.creakywars.arenas.ArenaState
import ru.joutak.creakywars.ceremony.CeremonyController
import ru.joutak.creakywars.ceremony.CeremonyPodium
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
import ru.joutak.minigames.MiniGamesAPI
import ru.joutak.minigames.managers.MatchmakingManager
import ru.joutak.minigames.results.model.MatchResult
import ru.joutak.minigames.results.model.Metric
import ru.joutak.minigames.results.model.PlayerResult
import ru.joutak.minigames.results.model.TeamResult
import ru.joutak.minigames.ui.LobbyScoreboardManager
import ru.joutak.minigames.ui.QueueBossBarManager
import java.util.*

@Suppress("DEPRECATION")
class Game(
    val arena: Arena,
    val teams: List<Team>,
    val apiInstance: ru.joutak.minigames.domain.GameInstance,
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
    private val teamScoreboard = TeamScoreboard(this)
    private val teamChestListener: TeamChestListener

    private val respawnTimers = mutableMapOf<UUID, BukkitTask>()

    private val pendingLastChanceRespawn = mutableSetOf<UUID>()
    private val awaitingRespawnSetup = mutableSetOf<UUID>()

    private val spectators = mutableSetOf<UUID>()
    private val spectatorBackups = mutableMapOf<UUID, SpectatorBackup>()

    private val spectatorEnsureGamemodeTasks = mutableMapOf<UUID, BukkitTask>()

    private var ceremonyWorldName: String? = null
    private var teamPlacementsAtEnd: Map<Int, Int>? = null


    // Results recording (MiniGamesAPI shared DB)
    val matchId: java.util.UUID = java.util.UUID.randomUUID()
    val startedAtMs: Long = System.currentTimeMillis()
    private var resultsSent: Boolean = false
    private var pendingMatchResult: MatchResult? = null

    private val joinedAtMs = mutableMapOf<java.util.UUID, Long>()
    private val leftAtMs = mutableMapOf<java.util.UUID, Long>()
    private val lastKnownName = mutableMapOf<java.util.UUID, String>()
    private val departedStats = mutableMapOf<java.util.UUID, RecordedPlayerStats>()

    private var participantsSnapshot: List<java.util.UUID>? = null
    private val teamByPlayerAtStart = mutableMapOf<java.util.UUID, Int>()
    private val teamEliminatedAtMs = mutableMapOf<Int, Long>()

    val infestedBlocks = mutableSetOf<Location>()

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

        teamByPlayerAtStart.putIfAbsent(player.uniqueId, team.id)

        // Track for results even if player leaves later.
        lastKnownName[player.uniqueId] = player.name
        joinedAtMs.putIfAbsent(player.uniqueId, System.currentTimeMillis())
    }

    fun getPlayerData(player: Player): PlayerData? {
        return playerData[player.uniqueId]
    }

    fun getTeam(player: Player): Team? {
        return getPlayerData(player)?.team
    }

    fun isSpectator(uuid: UUID): Boolean = spectators.contains(uuid)

    fun isAwaitingRespawn(uuid: UUID): Boolean {
        return respawnTimers.containsKey(uuid) || pendingLastChanceRespawn.contains(uuid) || awaitingRespawnSetup.contains(
            uuid
        )
    }

    fun hasPendingLastChanceRespawn(uuid: UUID): Boolean {
        return pendingLastChanceRespawn.contains(uuid) || awaitingRespawnSetup.contains(uuid)
    }

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

        fastForwardToCurrentPhaseEnd()

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

    private fun fastForwardToCurrentPhaseEnd() {
        if (arena.state != ArenaState.IN_GAME) return
        if (currentPhaseIndex < 0 || currentPhaseIndex >= ScenarioConfig.phases.size) return

        val phase = ScenarioConfig.phases[currentPhaseIndex]

        val targetTick = (phase.endAtTick ?: run {
            val totalTicks = (phase.durationSeconds * 20L).coerceAtLeast(0L)
            phaseStartTick + totalTicks
        }).coerceAtLeast(gameTick)

        val delta = (targetTick - gameTick).coerceAtLeast(0L)
        if (delta <= 0L) return

        // Apply border shrink for the skipped time window (in whole seconds).
        if (phase.borderShrink) {
            try {
                val border = arena.world.worldBorder
                val steps = (delta / 20L).coerceAtLeast(0L)
                val shrink = steps.toDouble() * phase.borderShrinkSpeed
                if (shrink > 0.0) {
                    border.size = maxOf(phase.borderFinalSize, border.size - shrink)
                }
            } catch (_: Exception) {
            }
        }

        try {
            dayNightCycle.fastForward(delta)
        } catch (_: Exception) {
        }

        gameTick = targetTick

        try {
            // ensure bossbar looks sane after skip
            dayNightCycle.timeBossBar.updateProgress(0L, 1L)
        } catch (_: Exception) {
        }
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

        // Remove lobby UI instantly (MiniGamesAPI) before attaching match UI.
        try {
            QueueBossBarManager.remove(player)
        } catch (_: Exception) {
        }
        try {
            LobbyScoreboardManager.remove(player)
        } catch (_: Exception) {
        }

        spectators.add(uuid)

        // If the player was previously bound to a ceremony region, ensure it doesn't leak into admin spectate.
        try {
            CeremonyController.clearPlayer(player)
        } catch (_: Exception) {
        }

        spectatorEnsureGamemodeTasks.remove(uuid)?.cancel()

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
            player.teleport(getSpectatorViewLocation(), PlayerTeleportEvent.TeleportCause.PLUGIN)
        } catch (_: Exception) {
        }

        // Some servers/plugins may override gamemode on teleport/world change.
        // Keep enforcing spectator mode for a short time.
        var ticks = 0
        val task = Bukkit.getScheduler().runTaskTimer(PluginManager.getPlugin(), Runnable {
            val p = Bukkit.getPlayer(uuid)
            if (p == null || !p.isOnline || !spectators.contains(uuid) || GameManager.getSpectatingGame(p) != this) {
                spectatorEnsureGamemodeTasks.remove(uuid)?.cancel()
                return@Runnable
            }

            try {
                if (p.gameMode != GameMode.SPECTATOR) p.gameMode = GameMode.SPECTATOR
                p.isCollidable = false
            } catch (_: Exception) {
            }

            ticks++
            // Some Multiverse/world plugins can keep overriding GM for a moment after teleport.
            // Enforce for a bit longer to be safe.
            if (ticks >= 120) {
                spectatorEnsureGamemodeTasks.remove(uuid)?.cancel()
            }
        }, 1L, 1L)
        spectatorEnsureGamemodeTasks[uuid] = task

        teamScoreboard.addPlayer(player)
        teamScoreboard.update(0L)
        dayNightCycle.timeBossBar.addPlayer(player)

        try {
            ru.joutak.minigames.MiniGamesAPI.allowVoiceSpectator(player, apiInstance)
        } catch (_: Exception) {
        }

        MessageUtils.sendMessage(
            player,
            "§aВы наблюдаете за игрой §e#${arena.id}§a."
        )
        return true
    }

    fun removeSpectator(player: Player, silent: Boolean = false, forceLobby: Boolean = true) {
        val uuid = player.uniqueId
        if (!spectators.contains(uuid)) return

        spectatorEnsureGamemodeTasks.remove(uuid)?.cancel()

        // Drop any ceremony bounds if they were ever applied.
        try {
            CeremonyController.clearPlayer(player)
        } catch (_: Exception) {
        }

        try {
            ru.joutak.minigames.MiniGamesAPI.revokeVoiceSpectator(player, apiInstance)
        } catch (_: Exception) {
        }

        spectators.remove(uuid)

        try {
            teamScoreboard.removePlayer(player)
        } catch (_: Exception) {
        }
        try {
            dayNightCycle.timeBossBar.removePlayer(player)
        } catch (_: Exception) {
        }

        val backup = spectatorBackups.remove(uuid)
        if (backup != null) {
            backup.restoreTo(player, forceLobby)
        } else {
            // Fallback reset
            val mainWorld = Bukkit.getWorlds().firstOrNull()
            if (mainWorld != null) {
                player.teleport(mainWorld.spawnLocation, PlayerTeleportEvent.TeleportCause.PLUGIN)
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
        }, 0L, 20L)
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

        captureParticipantsSnapshot()

        val activeTeams = teams.filter { it.players.isNotEmpty() }

        if (AdminConfig.debugMode) {
            PluginManager.getLogger().info("[DEBUG] Активных команд: ${activeTeams.size} из ${teams.size}")
        }

        val teamCount = mutableMapOf<Team, Int>()

        activeTeams.forEachIndexed { index, team ->
            val spawnLocation = arena.mapConfig.teamSpawns.getOrNull(teams.indexOf(team))
            if (spawnLocation != null) {
                team.getOnlinePlayers().forEach { player ->
                    player.teleport(spawnLocation.toLocation(arena.world))
                    player.gameMode = GameMode.SURVIVAL
                    player.health = 20.0
                    player.foodLevel = 20
                    player.inventory.clear()
                    player.enderChest.clear()

                    val playerData = getPlayerData(player)
                    if (playerData != null) {
                        if (team in teamCount.keys) {
                            teamCount[team] = teamCount[team]!!.plus(1)
                        } else {
                            teamCount[team] = 1
                        }
                        val loadout = PlayerLoadout(player, team)
                        playerData.loadout = loadout
                        loadout.giveDefaultLoadout()
                    }
                }
            }
        }

        val countAmplifier = if (teamCount.isEmpty()) {
            1.0
        } else {
            teamCount.values.maxOrNull()!! / 4.0
        }

        PluginManager.getLogger().info("Установлен множитель по кол-ву игроков в команде: $countAmplifier")

        getAudiencePlayers().forEach { player ->
            teamScoreboard.addPlayer(player)
        }

        teamScoreboard.update(0L)

        setupTeamChests()

        CoreManager.initializeCores(this, teams)

        ResourceSpawner.startSpawning(this, countAmplifier)
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
            PluginManager.getLogger()
                .warning("Количество команд (${teams.size}) не совпадает с количеством командных сундуков (${arena.mapConfig.teamChestLocations.size}) в конфиге!")
            return
        }

        teams.forEachIndexed { index, team ->
            val chestLoc = arena.mapConfig.teamChestLocations.getOrNull(index)?.toLocation(arena.world)
            if (chestLoc != null) {
                val block = chestLoc.block
                block.type = Material.CHEST
                applyFacing(block, chestLoc.yaw)
                teamChestListener.addTeamChest(block, team)
            }
        }

        arena.mapConfig.enderChestLocations.forEach { spawnLoc ->
            val loc = spawnLoc.toLocation(arena.world)
            val block = loc.block
            block.type = Material.ENDER_CHEST
            applyFacing(block, loc.yaw)
        }
    }


    private fun applyFacing(block: Block, yaw: Float) {
        val data = block.blockData
        if (data is Directional) {
            data.facing = yawToFace(yaw)
            block.blockData = data
        }
    }

    private fun yawToFace(yaw: Float): BlockFace {
        var rot = yaw % 360f
        if (rot < 0f) rot += 360f

        return when {
            rot >= 315f || rot < 45f -> BlockFace.SOUTH
            rot < 135f -> BlockFace.WEST
            rot < 225f -> BlockFace.NORTH
            else -> BlockFace.EAST
        }
    }

    fun getActiveTeams(): List<Team> {
        return teams.filter { it.players.isNotEmpty() }
    }

    private fun tick() {
        gameTick++

        var remainingTicks = 0L

        if (currentPhaseIndex < ScenarioConfig.phases.size) {
            val currentPhase = ScenarioConfig.phases[currentPhaseIndex]

            val endAt = currentPhase.endAtTick

            val totalTicks: Long

            val phaseFinished: Boolean

            if (endAt != null) {
                remainingTicks = (endAt - gameTick).coerceAtLeast(0L)
                phaseFinished = gameTick >= endAt
            } else {
                totalTicks = (currentPhase.durationSeconds * 20L).coerceAtLeast(1L)
                val elapsedTicks = (gameTick - phaseStartTick).coerceAtLeast(0L)
                remainingTicks = (totalTicks - elapsedTicks).coerceAtLeast(0L)
                phaseFinished = elapsedTicks >= totalTicks
            }

            if (gameTick % 10 == 0L || phaseFinished) {
                teamScoreboard.update(remainingTicks)
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

        teamScoreboard.update(0L)

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

                teamScoreboard.update(0L)
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

                teamScoreboard.update(0L)
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

            // TODO: itemsList was computed but never used — message was likely intended here
        }
    }


    private fun startRespawnTimer(player: Player, deathLocation: org.bukkit.Location) {
        respawnTimers[player.uniqueId]?.cancel()

        val data = getPlayerData(player) ?: return
        val team = data.team ?: return

        awaitingRespawnSetup.add(player.uniqueId)

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
            val uuid = player.uniqueId
            val hasLastChance = pendingLastChanceRespawn.contains(uuid)

            if (team.coreDestroyed && !hasLastChance) {
                respawnTimers[uuid]?.cancel()
                respawnTimers.remove(uuid)

                data.isAlive = false
                player.gameMode = GameMode.SPECTATOR

                MessageUtils.sendMessage(player, "§c§lВаше ядро уничтожено!")
                MessageUtils.sendTitle(player, "§c☠ ВЫБЫЛИ ☠", "§eВаше ядро уничтожено")

                teamScoreboard.update(0L)
                checkWinCondition()
                return@Runnable
            }

            if (!team.canRespawn() && !hasLastChance) {
                respawnTimers[uuid]?.cancel()
                respawnTimers.remove(uuid)

                data.isAlive = false
                player.gameMode = GameMode.SPECTATOR

                MessageUtils.sendMessage(player, "§c§lУ команды закончились жизни!")

                teamScoreboard.update(0L)
                checkWinCondition()
                return@Runnable
            }
            if (remainingSeconds <= 0) {
                respawnTimers[uuid]?.cancel()
                respawnTimers.remove(uuid)

                pendingLastChanceRespawn.remove(uuid)
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

        awaitingRespawnSetup.remove(player.uniqueId)
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
        trackTeamEliminations()

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

        pendingLastChanceRespawn.clear()
        awaitingRespawnSetup.clear()

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
            prepareResults(winnerTeam)
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
            prepareResults(null)
        }

        val ceremonyStarted = tryStartFinalCeremony()
        val cleanupDelayTicks = if (ceremonyStarted) {
            (AdminConfig.ceremonyDurationSeconds.coerceAtLeast(1) * 20L)
        } else {
            200L
        }

        // Ensure the game always proceeds to full cleanup and removal from GameManager.
        cleanupTask = Bukkit.getScheduler().runTaskLater(PluginManager.getPlugin(), Runnable {
            cleanup()
        }, cleanupDelayTicks)
    }

    fun disableListeners() {
        HandlerList.unregisterAll(teamChestListener)
    }

    private fun tryStartFinalCeremony(): Boolean {
        if (!AdminConfig.ceremonyEnabled) return false
        val placements = teamPlacementsAtEnd ?: return false

        val templateName = AdminConfig.ceremonyTemplateWorldName
        if (templateName.isBlank()) return false

        val cloneName = "cw_ceremony_${arena.id}_${System.currentTimeMillis() % 1000000L}"
        val world = try {
            ArenaManager.cloneWorld(templateName, cloneName)
        } catch (e: Exception) {
            PluginManager.getLogger().warning("Ceremony clone failed: ${e.message}")
            null
        } ?: return false

        ceremonyWorldName = world.name

        try {
            world.pvp = false
            world.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, false)
            world.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false)
            world.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false)
            world.setGameRule(org.bukkit.GameRule.ANNOUNCE_ADVANCEMENTS, false)
        } catch (_: Exception) {
        }

        val podiums = AdminConfig.ceremonyPodiums
        val spectatorAreas = AdminConfig.ceremonySpectators

        var spectatorSlot = 0
        val teamSlots = mutableMapOf<Int, Int>()

        // Place participants by their team placement.
        for (player in players) {
            val data = getPlayerData(player)
            val teamId = data?.team?.id
            val place = if (teamId != null) placements[teamId] else null
            val podium = if (place != null) podiums.getOrNull((place - 1).coerceAtLeast(0)) else null

            if (podium == null) {
                // If something is odd (no team), treat as spectator.
                val area =
                    if (spectatorAreas.isNotEmpty()) spectatorAreas[spectatorSlot % spectatorAreas.size] else null
                if (area == null) {
                    // No configured spectator areas; skip placing this player
                    continue
                }
                teleportToCeremonySpot(player, world, area, spectatorSlot, isSpectator = true)
                spectatorSlot++
                continue
            }

            val slot = if (teamId != null) {
                val current = teamSlots[teamId] ?: 0
                teamSlots[teamId] = current + 1
                current
            } else {
                0
            }
            teleportToCeremonySpot(player, world, podium, slot = slot, isSpectator = false)
        }

        // Place spectators in their own configured areas.
        for (spectator in spectatorsOnline) {
            val area = if (spectatorAreas.isNotEmpty()) spectatorAreas[spectatorSlot % spectatorAreas.size] else null
            if (area == null) continue
            teleportToCeremonySpot(spectator, world, area, spectatorSlot, isSpectator = true)
            spectatorSlot++
        }

        broadcastMessage("§6Церемония награждения началась!")
        return true
    }

    private fun teleportToCeremonySpot(
        player: Player,
        world: org.bukkit.World,
        podium: CeremonyPodium,
        slot: Int,
        isSpectator: Boolean
    ) {
        try {
            if (isSpectator) {
                player.gameMode = GameMode.SPECTATOR
                player.isCollidable = false
            } else {
                player.gameMode = GameMode.ADVENTURE
                player.isCollidable = true
                player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
                player.health = player.maxHealth
                player.foodLevel = 20
                player.saturation = 20f
                player.fireTicks = 0

                val charges = AdminConfig.ceremonyWindCharges
                if (charges > 0) {
                    player.inventory.addItem(ItemStack(Material.WIND_CHARGE, charges))
                }
            }
        } catch (_: Exception) {
        }

        val spawn = podium.getSpawnLocation(world, slot)
        try {
            player.teleport(spawn, PlayerTeleportEvent.TeleportCause.PLUGIN)
        } catch (_: Exception) {
        }

        try {
            CeremonyController.setPlayerRegion(player, world.name, podium.toBounds())
        } catch (_: Exception) {
        }
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

    private fun captureParticipantsSnapshot() {
        if (participantsSnapshot != null) return

        // Snapshot participants and their teamId at the moment the match starts.
        // This must not depend on who is still online by the end of the match.
        val snapshot = LinkedHashSet<java.util.UUID>()
        // Prefer team membership (stable in this game) so that offline players are still included.
        teams.forEach { team ->
            team.players.forEach { uuid ->
                snapshot.add(uuid)
                teamByPlayerAtStart.putIfAbsent(uuid, team.id)
            }
        }
        // Fallback: any playerData not present in teams (should not happen).
        playerData.keys.forEach { uuid ->
            snapshot.add(uuid)
        }

        participantsSnapshot = snapshot.toList()
    }

    private fun trackTeamEliminations() {
        // Record elimination times once, to build stable placements (1..4).
        val now = System.currentTimeMillis()
        teams.forEach { team ->
            if (teamEliminatedAtMs.containsKey(team.id)) return@forEach
            if (team.isEliminated(this)) {
                teamEliminatedAtMs[team.id] = now
            }
        }
    }

    private fun computeTeamPlacements(
        winnerTeamId: Int?,
        endedAtMs: Long,
        teamKills: Map<Int, Int>,
    ): Map<Int, Int> {
        // Always return placements for ids 0..3 (even if some teams are empty).
        val allTeamIds = (0..3).toList()

        fun eliminationScore(teamId: Int): Long {
            val recorded = teamEliminatedAtMs[teamId]
            if (recorded != null) return recorded
            val team = teams.firstOrNull { it.id == teamId }
            return if (team != null && team.isEliminated(this)) endedAtMs else Long.MAX_VALUE
        }

        var order = allTeamIds.sortedWith(
            compareByDescending<Int> { eliminationScore(it) }
                .thenByDescending { teamKills[it] ?: 0 }
                .thenBy { it }
        )

        if (winnerTeamId != null) {
            // Force the declared winner to be first (placement=1).
            if (order.firstOrNull() != winnerTeamId) {
                order = listOf(winnerTeamId) + order.filter { it != winnerTeamId }
            }
        }

        val placements = HashMap<Int, Int>(4)
        order.forEachIndexed { idx, teamId ->
            placements[teamId] = idx + 1
        }
        return placements
    }

    private fun prepareResults(winnerTeam: Team?) {
        if (pendingMatchResult != null) return

        val endedAtMs = System.currentTimeMillis()
        val winnerTeamId = winnerTeam?.id

        captureParticipantsSnapshot()

        // Snapshot current player stats (online at end)
        val currentSnapshots = playerData.values.map { data ->
            val uuid = data.uuid
            val playerName = Bukkit.getPlayer(uuid)?.name ?: lastKnownName[uuid]
            lastKnownName[uuid] = playerName ?: (lastKnownName[uuid] ?: "Unknown")

            joinedAtMs.putIfAbsent(uuid, startedAtMs)

            RecordedPlayerStats(
                playerUuid = uuid,
                playerName = playerName,
                teamId = data.team?.id,
                kills = data.kills,
                deaths = data.deaths,
                finalKills = data.finalKills,
                resourcesCollected = data.resourcesCollected,
                joinedAtMs = joinedAtMs[uuid],
                leftAtMs = leftAtMs[uuid],
                aliveAtEnd = data.isAlive,
            )
        }

        val allPlayers = LinkedHashMap<java.util.UUID, RecordedPlayerStats>()
        currentSnapshots.forEach { allPlayers[it.playerUuid] = it }
        departedStats.values.forEach { s -> allPlayers.putIfAbsent(s.playerUuid, s) }

        val participants = participantsSnapshot ?: allPlayers.keys.toList()

        // Kills-by-team for ranking and metrics
        val teamKills = HashMap<Int, Int>(4)
        participants.forEach { uuid ->
            val st = allPlayers[uuid]
            val teamId = teamByPlayerAtStart[uuid] ?: st?.teamId
            if (teamId != null) {
                teamKills[teamId] = (teamKills[teamId] ?: 0) + (st?.kills ?: 0)
            }
        }

        val placements = computeTeamPlacements(
            winnerTeamId = winnerTeamId,
            endedAtMs = endedAtMs,
            teamKills = teamKills,
        )

        teamPlacementsAtEnd = placements

        val teamResults = (0..3).map { teamId ->
            val team = teams.firstOrNull { it.id == teamId }

            val isWinner = winnerTeamId != null && teamId == winnerTeamId

            // Aggregate team metrics from participants snapshot (NOT from online-only list).
            val teamPlayerStats = participants.mapNotNull { allPlayers[it] }.filter {
                (teamByPlayerAtStart[it.playerUuid] ?: it.teamId) == teamId
            }

            val killsTotal = teamPlayerStats.sumOf { it.kills }.toLong()
            val deathsTotal = teamPlayerStats.sumOf { it.deaths }.toLong()
            val finalKillsTotal = teamPlayerStats.sumOf { it.finalKills }.toLong()
            val playersTotal = teamPlayerStats.size.toLong()

            TeamResult(
                teamId = teamId,
                placement = placements[teamId],
                isWinner = isWinner,
                score = null,
                metrics = listOf(
                    Metric.int("players_total", playersTotal),
                    Metric.int("kills_total", killsTotal),
                    Metric.int("deaths_total", deathsTotal),
                    Metric.int("final_kills_total", finalKillsTotal),
                    Metric.int("core_destroyed", if (team?.coreDestroyed == true) 1 else 0),
                    Metric.int("lives_remaining", (team?.livesRemaining ?: -1).toLong()),
                    Metric.int("forge_tier", (team?.forgeTier ?: 0).toLong()),
                    Metric.int("protection_level", (team?.protectionLevel ?: 0).toLong()),
                    Metric.int("sharpness_level", (team?.sharpnessLevel ?: 0).toLong()),
                    Metric.int("efficiency_level", (team?.efficiencyLevel ?: 0).toLong()),
                    Metric.int("fast_respawn", if (team?.hasFastRespawn == true) 1 else 0),
                    Metric.int("trap_active", if (team?.trapActive == true) 1 else 0),
                ),
            )
        }

        val playerResults = participants.map { uuid ->
            val st = allPlayers[uuid]

            val resolvedName = st?.playerName ?: lastKnownName[uuid] ?: Bukkit.getPlayer(uuid)?.name
            if (resolvedName != null) lastKnownName[uuid] = resolvedName

            val resolvedTeamId = teamByPlayerAtStart[uuid] ?: st?.teamId ?: 0
            val isWinner = winnerTeamId != null && resolvedTeamId == winnerTeamId

            PlayerResult(
                playerUuid = uuid,
                playerName = resolvedName,
                teamId = resolvedTeamId,
                isWinner = isWinner,
                joinedAtMs = st?.joinedAtMs ?: joinedAtMs[uuid] ?: startedAtMs,
                leftAtMs = st?.leftAtMs ?: leftAtMs[uuid],
                metrics = listOf(
                    Metric.int("kills", (st?.kills ?: 0).toLong()),
                    Metric.int("deaths", (st?.deaths ?: 0).toLong()),
                    Metric.int("final_kills", (st?.finalKills ?: 0).toLong()),
                    Metric.int("resources_collected", (st?.resourcesCollected ?: 0).toLong()),
                    Metric.int("alive_end", if (st?.aliveAtEnd == true) 1 else 0),
                ),
            )
        }

        val result = MatchResult(
            matchId = matchId,
            startedAtMs = startedAtMs,
            endedAtMs = endedAtMs,
            mapKey = arena.mapConfig.mapName,
            teams = teamResults,
            players = playerResults,
        )
        pendingMatchResult = result
    }

    private fun flushResultsIfNeeded() {
        if (resultsSent) return
        val result = pendingMatchResult ?: return
        resultsSent = true
        try {
            MiniGamesAPI.recordMatchResult(result)
        } catch (e: Exception) {
            PluginManager.getLogger().warning("Failed to record match result: ${e.message}")
        }
    }

    private data class RecordedPlayerStats(
        val playerUuid: UUID,
        val playerName: String?,
        val teamId: Int?,
        val kills: Int,
        val deaths: Int,
        val finalKills: Int,
        val resourcesCollected: Int,
        val joinedAtMs: Long?,
        val leftAtMs: Long?,
        val aliveAtEnd: Boolean,
    )


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

        val ceremonyWorldSnapshot = ceremonyWorldName

        if (ceremonyWorldSnapshot != null) {
            (playersSnapshot + spectatorsSnapshot).forEach { p ->
                try {
                    CeremonyController.clearPlayer(p)
                } catch (_: Exception) {
                }
            }
        }

        // Send results to MiniGamesAPI only when we're ready to let it kick/teleport players (after end screen / ceremony).
        flushResultsIfNeeded()

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
                        player.teleport(lobbySpawn, PlayerTeleportEvent.TeleportCause.PLUGIN)
                    }
                } catch (_: Exception) {
                }

                try {
                    player.gameMode = GameMode.ADVENTURE
                    player.inventory.clear()
                    player.enderChest.clear()
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
                dayNightCycle.timeBossBar.remove()
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

            if (ceremonyWorldSnapshot != null) {
                try {
                    CeremonyController.clearWorld(ceremonyWorldSnapshot)
                } catch (_: Exception) {
                }
                try {
                    ArenaManager.deleteWorldByName(ceremonyWorldSnapshot)
                } catch (_: Exception) {
                }
                ceremonyWorldName = null
            }

            pendingLastChanceRespawn.clear()
            awaitingRespawnSetup.clear()
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

        val pendingNow = team.players.filter { uuid ->
            respawnTimers.containsKey(uuid) || awaitingRespawnSetup.contains(uuid)
        }.toSet()
        if (pendingNow.isNotEmpty()) {
            pendingLastChanceRespawn.addAll(pendingNow)
        }

        team.getOnlinePlayers().forEach { player ->
            val data = getPlayerData(player)

            val uuid = player.uniqueId
            if (pendingNow.contains(uuid)) {
                MessageUtils.sendMessage(player, "§c§l⚠ ВАШЕ ЯДРО УНИЧТОЖЕНО!")
                MessageUtils.sendMessage(player, "§6Это ваш последний респавн.")
                player.playSound(player.location, Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 0.8f)
                return@forEach
            }

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
            if (!pendingNow.contains(uuid)) {
                respawnTimers[uuid]?.cancel()
                respawnTimers.remove(uuid)
            }
        }

        teamScoreboard.update(0L)

        getAudiencePlayers().forEach {
            it.playSound(it.location, Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1f)
        }

        checkWinCondition()
    }

    fun getDayNightCycle(): DayNightCycle = dayNightCycle

    fun removePlayer(player: Player) {
        val uuid = player.uniqueId

        // Snapshot stats for results before removal.
        val data = playerData[uuid]
        if (data != null) {
            lastKnownName[uuid] = player.name
            joinedAtMs.putIfAbsent(uuid, startedAtMs)
            leftAtMs[uuid] = System.currentTimeMillis()

            departedStats[uuid] = RecordedPlayerStats(
                playerUuid = uuid,
                playerName = lastKnownName[uuid],
                teamId = data.team?.id,
                kills = data.kills,
                deaths = data.deaths,
                finalKills = data.finalKills,
                resourcesCollected = data.resourcesCollected,
                joinedAtMs = joinedAtMs[uuid],
                leftAtMs = leftAtMs[uuid],
                aliveAtEnd = false,
            )
        }

        playerData.remove(uuid)

        respawnTimers[uuid]?.cancel()
        respawnTimers.remove(uuid)

        pendingLastChanceRespawn.remove(uuid)
        awaitingRespawnSetup.remove(uuid)

        val team = teams.firstOrNull { it.hasPlayer(uuid) }
        team?.removePlayer(uuid)

        teamScoreboard.removePlayer(player)

        val mainWorld = Bukkit.getWorlds().firstOrNull()
        if (mainWorld != null) {
            player.teleport(mainWorld.spawnLocation)
        }

        player.inventory.clear()
        player.enderChest.clear()
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
                    player.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN)
                } catch (_: Exception) {
                }
            }
        }
    }
}
