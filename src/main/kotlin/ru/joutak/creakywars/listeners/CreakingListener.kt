package ru.joutak.creakywars.listeners

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Creaking
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitTask
import ru.joutak.creakywars.config.GameConfig
import ru.joutak.creakywars.game.Game
import ru.joutak.creakywars.game.GameManager
import ru.joutak.creakywars.arenas.ArenaState
import ru.joutak.creakywars.utils.PluginManager
import java.lang.reflect.Method
import java.util.UUID

class CreakingListener : Listener {

    companion object {
        private const val AI_PERIOD_TICKS = 2

        // Взгляд учитываем только со стороны текущей цели (ближайшего игрока).
        private const val LOOK_DISTANCE = 20.0
        private const val LOOK_DOT_THRESHOLD = 0.82

        private var creakingAITask: BukkitTask? = null

        private data class Candidate(
            val distSq: Double,
            val creakingId: UUID,
            val playerId: UUID
        )

        private data class StuckState(
            var lastLoc: Location,
            var notMovedTicks: Int
        )

        private val stuck = mutableMapOf<UUID, StuckState>()

        // Мы принудительно ведём скрипунов на выбранную цель, не полагаясь на ванильный выбор таргета
        // (у скрипуна он зависит от "кто на него смотрит", что нам не подходит для мини-игры).
        private val desiredTarget = mutableMapOf<UUID, UUID>()

        // Paper Pathfinder (рефлексией, чтобы не привязываться к API-типам)
        private var pathfinderGetter: Method? = null
        private var pathfinderMoveTo: Method? = null
        private var pathfinderStop: Method? = null

        private val anchorWorldKey by lazy { NamespacedKey(PluginManager.getPlugin(), "creaking_anchor_world") }
        private val anchorXKey by lazy { NamespacedKey(PluginManager.getPlugin(), "creaking_anchor_x") }
        private val anchorYKey by lazy { NamespacedKey(PluginManager.getPlugin(), "creaking_anchor_y") }
        private val anchorZKey by lazy { NamespacedKey(PluginManager.getPlugin(), "creaking_anchor_z") }

        private val ignoreTeamIdKey by lazy { NamespacedKey(PluginManager.getPlugin(), "creaking_ignore_team_id") }

        fun init() {
            if (creakingAITask == null) {
                creakingAITask = Bukkit.getScheduler().runTaskTimer(PluginManager.getPlugin(), Runnable {
                    GameManager.getActiveGames().forEach { game ->
                        if (game.arena.state == ArenaState.IN_GAME) {
                            updateGameCreakingAI(game)
                        }
                    }
                }, 0L, AI_PERIOD_TICKS.toLong())
                PluginManager.getLogger().info("✓ Creaking AI Task started.")
            }
        }

        fun shutdown() {
            creakingAITask?.cancel()
            creakingAITask = null
            stuck.clear()
            desiredTarget.clear()
        }

        fun setAnchor(creaking: Creaking, anchor: Location) {
            val w = anchor.world ?: return
            val pdc = creaking.persistentDataContainer
            pdc.set(anchorWorldKey, PersistentDataType.STRING, w.name)
            pdc.set(anchorXKey, PersistentDataType.INTEGER, anchor.blockX)
            pdc.set(anchorYKey, PersistentDataType.INTEGER, anchor.blockY)
            pdc.set(anchorZKey, PersistentDataType.INTEGER, anchor.blockZ)
        }

        fun clearAnchor(creaking: Creaking) {
            val pdc = creaking.persistentDataContainer
            pdc.remove(anchorWorldKey)
            pdc.remove(anchorXKey)
            pdc.remove(anchorYKey)
            pdc.remove(anchorZKey)
        }

        /**
         * "Спавн за команду": команда с этим id будет игнорироваться скрипуном.
         * По умолчанию (если не задано) скрипун агрится на всех.
         */
        fun setIgnoreTeamId(creaking: Creaking, teamId: Int) {
            creaking.persistentDataContainer.set(ignoreTeamIdKey, PersistentDataType.INTEGER, teamId)
        }

        fun clearIgnoreTeamId(creaking: Creaking) {
            creaking.persistentDataContainer.remove(ignoreTeamIdKey)
        }

        private fun getIgnoreTeamId(creaking: Creaking): Int? {
            return creaking.persistentDataContainer.get(ignoreTeamIdKey, PersistentDataType.INTEGER)
        }

        private fun getAnchor(creaking: Creaking): Location? {
            val pdc = creaking.persistentDataContainer
            val worldName = pdc.get(anchorWorldKey, PersistentDataType.STRING) ?: return null
            val world = Bukkit.getWorld(worldName) ?: return null
            val x = pdc.get(anchorXKey, PersistentDataType.INTEGER) ?: return null
            val y = pdc.get(anchorYKey, PersistentDataType.INTEGER) ?: return null
            val z = pdc.get(anchorZKey, PersistentDataType.INTEGER) ?: return null
            return Location(world, x + 0.5, y.toDouble(), z + 0.5)
        }

        private fun isTerracotta(mat: Material): Boolean = mat.name.endsWith("TERRACOTTA")
        private fun isWool(mat: Material): Boolean = mat.name.endsWith("WOOL")

        private fun calculateBreakTime(material: Material): Long {
            val baseHardness = when {
                material == Material.OBSIDIAN -> 100L
                material == Material.END_STONE -> 60L
                material == Material.CLAY -> 40L
                material == Material.OAK_PLANKS -> 30L

                isTerracotta(material) -> 25L
                isWool(material) -> 10L

                else -> 20L
            }

            return (baseHardness / GameConfig.creakingBreakSpeed).toLong().coerceAtLeast(1L)
        }

        private fun getChaseSpeed(): Double {
            // IMPORTANT:
            // Скорость скрипунов уже регулируется эффектом SPEED (см. DayNightCycle.updateCreakingPhaseBuffs)
            // через ScenarioConfig.creaking-speed-amplifier.
            // Если здесь дополнительно масштабировать скорость — получится "двойное ускорение".
            return 0.9
        }

        private fun moveTo(creaking: Creaking, target: Location, speed: Double) {
            val mob = creaking as? Mob ?: return
            val pf = getPathfinder(mob) ?: return
            val move = pathfinderMoveTo ?: resolveMoveTo(pf).also { pathfinderMoveTo = it } ?: return
            runCatching { move.invoke(pf, target, speed) }
        }

        private fun stopPathfinding(creaking: Creaking) {
            val mob = creaking as? Mob ?: return
            val pf = getPathfinder(mob) ?: return
            val stop = pathfinderStop ?: resolveStop(pf).also { pathfinderStop = it } ?: return
            runCatching { stop.invoke(pf) }
        }

        private fun getPathfinder(mob: Mob): Any? {
            val getter = pathfinderGetter ?: mob.javaClass.methods
                .firstOrNull { it.name == "getPathfinder" && it.parameterCount == 0 }
                ?.also { pathfinderGetter = it }
            return runCatching { getter?.invoke(mob) }.getOrNull()
        }

        private fun resolveMoveTo(pf: Any): Method? {
            return pf.javaClass.methods.firstOrNull { m ->
                m.name == "moveTo" && m.parameterCount == 2 &&
                    Location::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                    (m.parameterTypes[1] == java.lang.Double.TYPE || m.parameterTypes[1] == java.lang.Double::class.java)
            }
        }

        private fun resolveStop(pf: Any): Method? {
            return pf.javaClass.methods.firstOrNull { m ->
                (m.name == "stopPathfinding" || m.name == "stop") && m.parameterCount == 0
            }
        }

        private fun isWatchedByTarget(creaking: Creaking, target: Player): Boolean {
            if (target.world != creaking.world) return false
            if (target.gameMode == GameMode.SPECTATOR || target.isDead) return false

            val distSq = LOOK_DISTANCE * LOOK_DISTANCE
            if (target.location.distanceSquared(creaking.location) > distSq) return false
            if (!target.hasLineOfSight(creaking)) return false

            val creakingEye = creaking.location.clone().add(0.0, 1.5, 0.0)
            val dir = target.eyeLocation.direction.normalize()
            val toCreaking = creakingEye.toVector().subtract(target.eyeLocation.toVector()).normalize()
            return dir.dot(toCreaking) >= LOOK_DOT_THRESHOLD
        }

        private fun updateGameCreakingAI(game: Game) {
            val world = game.arena.world
            val creakings = world.entities.filterIsInstance<Creaking>()
            if (creakings.isEmpty()) return

            val aggroRadius = GameConfig.creakingAggroRadius
            val aggroRadiusSq = aggroRadius * aggroRadius
            val maxPerPlayer = GameConfig.creakingMaxAggroPerPlayer.coerceAtLeast(1)
            val voidKillY = GameConfig.voidKillHeight.toDouble()

            val eligiblePlayers = game.activePlayers
                .asSequence()
                .filter { it.world == world }
                .filter { it.gameMode == GameMode.SURVIVAL || it.gameMode == GameMode.ADVENTURE }
                .filter { !it.isDead }
                .toList()

            if (eligiblePlayers.isEmpty()) {
                creakings.forEach { creaking ->
                    val anchor = getAnchor(creaking)
                    desiredTarget.remove(creaking.uniqueId)
                    creaking.target = null
                    stopPathfinding(creaking)
                    creaking.setAI(true)

                    if (anchor != null) {
                        moveTo(creaking, anchor, getChaseSpeed())
                        handleStuckAndBreak(creaking, anchor)
                    } else {
                        stuck.remove(creaking.uniqueId)
                    }
                }
                return
            }

            val playersById = eligiblePlayers.associateBy { it.uniqueId }
            val teamByPlayer = eligiblePlayers.associate { it.uniqueId to game.getPlayerData(it)?.team?.id }

            val candidates = ArrayList<Candidate>(creakings.size * eligiblePlayers.size)
            val hasAnyCandidate = HashSet<UUID>()

            creakings.forEach { creaking ->
                // Если скрипуна скинули с карты, он не должен "улетать в вечность" — возвращаем на якорь
                // на том же пороге, что и void-kill для игроков.
                if (creaking.location.y <= voidKillY) {
                    val anchor = getAnchor(creaking)
                    desiredTarget.remove(creaking.uniqueId)
                    creaking.target = null
                    stopPathfinding(creaking)
                    creaking.setAI(true)
                    creaking.velocity = creaking.velocity.multiply(0.0)
                    creaking.fallDistance = 0f
                    resetStuck(creaking)

                    if (anchor != null && anchor.world == creaking.world) {
                        creaking.teleport(anchor)
                    }
                    return@forEach
                }

                val ignoreTeamId = getIgnoreTeamId(creaking)
                val cLoc = creaking.location

                eligiblePlayers.forEach { player ->
                    if (ignoreTeamId != null) {
                        val teamId = teamByPlayer[player.uniqueId]
                        if (teamId != null && teamId == ignoreTeamId) return@forEach
                    }

                    val distSq = player.location.distanceSquared(cLoc)
                    if (distSq <= aggroRadiusSq) {
                        candidates.add(Candidate(distSq, creaking.uniqueId, player.uniqueId))
                        hasAnyCandidate.add(creaking.uniqueId)
                    }
                }
            }

            candidates.sortBy { it.distSq }

            val assigned = HashMap<UUID, UUID>(creakings.size)
            val counts = HashMap<UUID, Int>(eligiblePlayers.size)

            for (c in candidates) {
                if (assigned.containsKey(c.creakingId)) continue
                val cnt = counts[c.playerId] ?: 0
                if (cnt >= maxPerPlayer) continue
                assigned[c.creakingId] = c.playerId
                counts[c.playerId] = cnt + 1
            }

            creakings.forEach { creaking ->
                val assignedPlayerId = assigned[creaking.uniqueId]
                val assignedPlayer = assignedPlayerId?.let { playersById[it] }

                if (assignedPlayer != null) {
                    updateCreakingChase(creaking, assignedPlayer)
                } else {
                    val anchor = getAnchor(creaking)

                    desiredTarget.remove(creaking.uniqueId)
                    creaking.target = null
                    stopPathfinding(creaking)

                    if (hasAnyCandidate.contains(creaking.uniqueId)) {
                        // Есть игроки в радиусе, но слот агра заполнен — лишних замораживаем, чтобы они не "подхватывали" ванильный таргет.
                        creaking.setAI(false)
                        creaking.velocity = creaking.velocity.multiply(0.0)
                        resetStuck(creaking)
                        return@forEach
                    }

                    // Никого рядом — можно жить своей жизнью (якорь/простой).
                    creaking.setAI(true)
                    if (anchor != null) {
                        moveTo(creaking, anchor, getChaseSpeed())
                        handleStuckAndBreak(creaking, anchor)
                    } else {
                        stuck.remove(creaking.uniqueId)
                    }
                }
            }
        }

        private fun updateCreakingChase(creaking: Creaking, targetPlayer: Player) {
            creaking.setAI(true)

            desiredTarget[creaking.uniqueId] = targetPlayer.uniqueId
            forceSetTarget(creaking, targetPlayer)

            // Скрипун "замирает" только если на него смотрит его текущая цель.
            if (isWatchedByTarget(creaking, targetPlayer)) {
                stopPathfinding(creaking)
                resetStuck(creaking)
                return
            }

            moveTo(creaking, targetPlayer.location, getChaseSpeed())
            handleStuckAndBreak(creaking, targetPlayer.location)
            sabotageBridges(creaking, targetPlayer.location)
        }

        private fun forceSetTarget(creaking: Creaking, player: Player) {
            val current = creaking.target
            if (current == null || current.uniqueId != player.uniqueId) {
                creaking.target = null
                creaking.target = player
            } else {
                // Перестраховка: ванильный скрипун может сам пытаться сбрасывать таргет.
                creaking.target = player
            }
        }

        private fun resetStuck(creaking: Creaking) {
            val state = stuck[creaking.uniqueId]
            if (state != null) {
                state.lastLoc = creaking.location.clone()
                state.notMovedTicks = 0
            } else {
                stuck[creaking.uniqueId] = StuckState(creaking.location.clone(), 0)
            }
        }

        private fun handleStuckAndBreak(creaking: Creaking, target: Location) {
            val id = creaking.uniqueId
            val current = creaking.location
            val state = stuck.getOrPut(id) { StuckState(current.clone(), 0) }

            val moved = state.lastLoc.world == current.world && state.lastLoc.distanceSquared(current) > 0.12 * 0.12
            if (moved) {
                state.lastLoc = current.clone()
                state.notMovedTicks = 0
                return
            }

            state.notMovedTicks += AI_PERIOD_TICKS
            if (state.notMovedTicks < 40) return // ~2 секунды
            state.notMovedTicks = 0

            tryBreakTowards(creaking, target)
        }

        private fun tryBreakTowards(creaking: Creaking, target: Location) {
            if (creaking.hasMetadata("breaking")) return

            val from = creaking.location.toVector()
            val to = target.toVector()
            val dir = to.subtract(from)
            if (dir.lengthSquared() < 0.001) return
            dir.normalize()

            val base = creaking.location.clone().add(dir.clone().multiply(1.0))

            // Скрипун высокий (≈3 блока), ломаем коридор 3-high.
            val blocksToBreak = listOf(
                base.clone().add(0.0, 0.0, 0.0).block,
                base.clone().add(0.0, 1.0, 0.0).block,
                base.clone().add(0.0, 2.0, 0.0).block
            ).filter { it.type.isSolid && GameConfig.allowedBlocks.contains(it.type) }

            if (blocksToBreak.isEmpty()) return

            val breakTime = blocksToBreak.maxOf { calculateBreakTime(it.type) }
            creaking.setMetadata("breaking", FixedMetadataValue(PluginManager.getPlugin(), true))

            Bukkit.getScheduler().runTaskLater(PluginManager.getPlugin(), Runnable {
                if (creaking.isDead) {
                    creaking.removeMetadata("breaking", PluginManager.getPlugin())
                    return@Runnable
                }

                var brokeAny = false
                blocksToBreak.forEach { b ->
                    if (b.type != Material.AIR) {
                        b.type = Material.AIR
                        brokeAny = true
                    }
                }

                if (brokeAny) {
                    creaking.world.playSound(creaking.location, Sound.BLOCK_STONE_BREAK, 1.0f, 1.0f)
                }
                creaking.removeMetadata("breaking", PluginManager.getPlugin())
            }, breakTime)
        }

        private fun sabotageBridges(creaking: Creaking, target: Location) {
            // Ненавязчивый саботаж "дорог к центру": ломаем мосты из разрешённых блоков над воздухом.
            if (creaking.hasMetadata("breaking")) return
            if (creaking.location.distanceSquared(target) < 12.0 * 12.0) return

            val now = System.currentTimeMillis()
            val meta = creaking.getMetadata("sabotage_until").firstOrNull { it.owningPlugin == PluginManager.getPlugin() }
            val until = meta?.asLong() ?: 0L
            if (until > now) return
            creaking.setMetadata("sabotage_until", FixedMetadataValue(PluginManager.getPlugin(), now + 2500L))

            val under = creaking.location.clone().add(0.0, -1.0, 0.0).block
            val under2 = creaking.location.clone().add(0.0, -2.0, 0.0).block
            if (!GameConfig.allowedBlocks.contains(under.type)) return
            if (under2.type != Material.AIR) return

            val breakTime = calculateBreakTime(under.type)
            creaking.setMetadata("breaking", FixedMetadataValue(PluginManager.getPlugin(), true))
            Bukkit.getScheduler().runTaskLater(PluginManager.getPlugin(), Runnable {
                if (!creaking.isDead && under.type != Material.AIR) {
                    under.type = Material.AIR
                    creaking.world.playSound(under.location, Sound.BLOCK_STONE_BREAK, 0.9f, 0.9f)
                }
                creaking.removeMetadata("breaking", PluginManager.getPlugin())
            }, breakTime)
        }
    }

    @EventHandler
    fun onCreakingTarget(event: EntityTargetLivingEntityEvent) {
        val creaking = event.entity as? Creaking ?: return

        val game = GameManager.getActiveGames().firstOrNull {
            it.arena.world == creaking.world && it.arena.state == ArenaState.IN_GAME
        } ?: return

        val desiredId = desiredTarget[creaking.uniqueId]
        if (desiredId == null) {
            // Если наша AI-логика сейчас не выбрала цель — запрещаем скрипуну "держать" старую.
            if (event.target is Player) {
                event.target = null
                event.isCancelled = true
            }
            return
        }

        val desiredPlayer = Bukkit.getPlayer(desiredId)
        if (desiredPlayer == null || desiredPlayer.world != creaking.world || desiredPlayer.isDead) {
            desiredTarget.remove(creaking.uniqueId)
            event.isCancelled = true
            creaking.target = null
            return
        }

        // Наблюдатели/админы не должны становиться ванильной целью даже если desiredTarget сломался.
        if (desiredPlayer.gameMode != GameMode.SURVIVAL && desiredPlayer.gameMode != GameMode.ADVENTURE) {
            desiredTarget.remove(creaking.uniqueId)
            event.target = null
            event.isCancelled = true
            creaking.target = null
            return
        }

        // Учитываем ignoreTeamId на всякий случай (на случай ручного setIgnoreTeamId).
        val ignoreTeamId = getIgnoreTeamId(creaking)
        if (ignoreTeamId != null) {
            val teamId = game.getPlayerData(desiredPlayer)?.team?.id
            if (teamId != null && teamId == ignoreTeamId) {
                event.isCancelled = true
                creaking.target = null
                return
            }
        }

        if (event.target == null || (event.target as? Player)?.uniqueId != desiredId) {
            event.target = desiredPlayer
        }
    }

    @EventHandler
    fun onCreakingDamage(event: EntityDamageByEntityEvent) {
        val creaking = event.damager as? Creaking ?: return
        val victim = event.entity as? Player ?: return

        val game = GameManager.getActiveGames().firstOrNull {
            it.arena.world == creaking.world && it.arena.state == ArenaState.IN_GAME
        } ?: return

        // Скрипун должен бить только текущую "желаемую" цель (ближайшего игрока),
        // иначе ванильная механика иногда продолжает наносить урон по старой жертве.
        val desiredId = desiredTarget[creaking.uniqueId]
        val victimData = game.getPlayerData(victim)

        // Никогда не бьём наблюдателей/внеигровых.
        if (victim.gameMode != GameMode.SURVIVAL && victim.gameMode != GameMode.ADVENTURE) {
            event.isCancelled = true
            return
        }
        if (victimData?.isAlive != true) {
            event.isCancelled = true
            return
        }

        // "Спавн за команду": игнорируем урон по запрещённой команде.
        val ignoreTeamId = getIgnoreTeamId(creaking)
        if (ignoreTeamId != null && victimData.team?.id == ignoreTeamId) {
            event.isCancelled = true
            return
        }

        if (desiredId == null || victim.uniqueId != desiredId) {
            event.isCancelled = true

            // Перестраховка: немедленно возвращаем таргет на желаемого.
            val desiredPlayer = desiredId?.let { Bukkit.getPlayer(it) }
            if (desiredPlayer != null && desiredPlayer.world == creaking.world && !desiredPlayer.isDead) {
                forceSetTarget(creaking, desiredPlayer)
            } else {
                creaking.target = null
            }
        }
    }

    @EventHandler
    fun onCreakingChangeBlock(event: EntityChangeBlockEvent) {
        val creaking = event.entity as? Creaking ?: return

        val block = event.block

        if (GameConfig.allowedBlocks.contains(block.type)) {
            event.isCancelled = true
        } else {
            event.isCancelled = true
        }
    }
}