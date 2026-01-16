package ru.joutak.creakywars.game

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.entity.Creaking
import org.bukkit.entity.EntityType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import ru.joutak.creakywars.config.GameConfig
import ru.joutak.creakywars.utils.PluginManager
import ru.joutak.creakywars.utils.SpawnLocation
import kotlin.math.cos
import kotlin.math.sin
import ru.joutak.creakywars.listeners.CreakingListener

@Suppress("DEPRECATION", "SameParameterValue")
class DayNightCycle(private val game: Game) {

    private var cycleTask: BukkitTask? = null
    private var creakingCheckTask: BukkitTask? = null
    private var particleTask: BukkitTask? = null
    private var isNight = false
    private var currentTicks = 0L

    private val eyeblossomBlocks = mutableMapOf<SpawnLocation, EyeblossomState>()
    private val creakings = mutableListOf<Creaking>()

    private val DAY_START = 0L
    private val NIGHT_START = 14000L
    private val MINECRAFT_DAY_TICKS = 24000L

    private val EYEBLOSSOM_OPEN_DURATION = 1200L

    private val WAVE_CENTER = Location(game.arena.world, 0.0, 70.0, 0.0)
    private val WAVE_MAX_RADIUS = 30.0
    private val WAVE_DURATION = 100L
    private var waveTask: BukkitTask? = null

    /**
     * Admin/testing helper: fast-forward the internal day/night clock without running per-tick side effects.
     * Used by /cw phase skip so phases and night events don't desync.
     */
    fun fastForward(ticks: Long) {
        if (ticks <= 0L) return
        if (cycleTask == null) return

        val dayDur = GameConfig.dayDurationTicks.coerceAtLeast(1L)
        val nightDur = GameConfig.nightDurationTicks.coerceAtLeast(1L)

        var remaining = ticks
        var night = isNight
        var ct = currentTicks.coerceAtLeast(0L)

        // Compute final cycle state (night/day + ticks within the current segment).
        while (remaining > 0L) {
            val dur = if (night) nightDur else dayDur
            val rem = (dur - ct).coerceAtLeast(0L)
            if (rem == 0L) {
                // Safety against bad state.
                night = !night
                ct = 0L
                continue
            }

            if (remaining < rem) {
                ct += remaining
                remaining = 0L
            } else {
                remaining -= rem
                night = !night
                ct = 0L
            }
        }

        // Apply final state with minimal side effects.
        if (night != isNight) {
            try {
                waveTask?.cancel()
            } catch (_: Exception) {
            }
            waveTask = null

            if (night) {
                // Entering night: schedule eyeblossoms + spawn creakings.
                try {
                    startNight()
                } catch (_: Exception) {
                }
            } else {
                // Entering day: close flowers and despawn creakings (no cleansing wave during fast-forward).
                try {
                    closeEyeblossoms()
                } catch (_: Exception) {
                }
                try {
                    stopCreakingCheck()
                } catch (_: Exception) {
                }
                try {
                    despawnAllCreakings()
                } catch (_: Exception) {
                }
            }
        }

        isNight = night
        currentTicks = ct

        updateWorldTime()
        if (isNight) {
            try {
                updateEyeblossoms()
            } catch (_: Exception) {
            }
        }
    }

    data class EyeblossomState(
        var isOpen: Boolean = false,
        var openAt: Long = 0L,
        var closeAt: Long = 0L
    )

    fun start() {
        game.arena.mapConfig.eyeblossomLocations.forEach { loc ->
            eyeblossomBlocks[loc] = EyeblossomState()
        }

        placeAllEyeblossoms(false)

        game.arena.world.time = DAY_START
        game.arena.world.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false)
        game.arena.world.setGameRule(org.bukkit.GameRule.RANDOM_TICK_SPEED, 0)

        cycleTask = Bukkit.getScheduler().runTaskTimer(
            PluginManager.getPlugin(),
            Runnable { tick() },
            0L,
            1L
        )

        startParticleTask()

        PluginManager.getLogger().info("Цикл дня/ночи запущен для арены #${game.arena.id}")
    }

    fun stop() {
        cycleTask?.cancel()
        cycleTask = null

        stopParticleTask()

        stopCreakingCheck()
        despawnAllCreakings()

        waveTask?.cancel()
        waveTask = null

        eyeblossomBlocks.keys.forEach { loc ->
            val block = loc.toLocation(game.arena.world).block
            block.type = Material.AIR
        }
        eyeblossomBlocks.clear()

        PluginManager.getLogger().info("Цикл дня/ночи остановлен для арены #${game.arena.id}")
    }

    private fun tick() {
        currentTicks++

        val cycleDuration = if (isNight) {
            GameConfig.nightDurationTicks
        } else {
            GameConfig.dayDurationTicks
        }

        updateWorldTime()

        if (isNight) {
            updateEyeblossoms()
        }

        if (currentTicks >= cycleDuration) {
            toggleDayNight()
            currentTicks = 0
        }
    }

    private fun updateWorldTime() {
        val cycleDuration = if (isNight) {
            GameConfig.nightDurationTicks
        } else {
            GameConfig.dayDurationTicks
        }

        val progress = currentTicks.toDouble() / cycleDuration.toDouble()

        val newTime = if (isNight) {
            val nightDuration = (MINECRAFT_DAY_TICKS - NIGHT_START + DAY_START)
            val timeOffset = (nightDuration * progress).toLong()
            ((NIGHT_START + timeOffset) % MINECRAFT_DAY_TICKS)
        } else {
            val dayDuration = NIGHT_START - DAY_START
            val timeOffset = (dayDuration * progress).toLong()
            DAY_START + timeOffset
        }

        game.arena.world.time = newTime
    }

    private fun toggleDayNight() {
        isNight = !isNight

        if (isNight) {
            startNight()
        } else {
            startDay()
        }
    }

    private fun startNight() {
        eyeblossomBlocks.values.forEach { state ->
            state.isOpen = false
            state.openAt = 0L
            state.closeAt = 0L
        }
        scheduleEyeblossomOpenings()
        spawnCreakings()
    }

    private fun startDay() {
        closeEyeblossoms()
        stopCreakingCheck()

        startCleansingWave()

        Bukkit.getScheduler().runTaskLater(PluginManager.getPlugin(), Runnable {
            despawnAllCreakings()
        }, WAVE_DURATION + 20L)
    }

    private fun placeAllEyeblossoms(allOpen: Boolean) {
        eyeblossomBlocks.keys.forEach { loc ->
            val block = loc.toLocation(game.arena.world).block
            block.type = if (allOpen) {
                Material.OPEN_EYEBLOSSOM
            } else {
                Material.CLOSED_EYEBLOSSOM
            }
        }
    }

    private fun scheduleEyeblossomOpenings() {
        val nightDuration = GameConfig.nightDurationTicks
        val openCount = (eyeblossomBlocks.size * GameConfig.eyeblossomOpenPercent).toInt()

        val maxOpenStartTime = nightDuration - EYEBLOSSOM_OPEN_DURATION

        val shuffled = eyeblossomBlocks.keys.shuffled().take(openCount)

        shuffled.forEach { loc ->
            val state = eyeblossomBlocks[loc]!!

            val openAt = (Math.random() * maxOpenStartTime).toLong()
            val closeAt = openAt + EYEBLOSSOM_OPEN_DURATION

            state.openAt = openAt
            state.closeAt = closeAt
            state.isOpen = false
        }

        PluginManager.getLogger().info("[Арена #${game.arena.id}] Запланировано открытие $openCount/${eyeblossomBlocks.size} цветков")
    }

    private fun updateEyeblossoms() {
        eyeblossomBlocks.forEach { (loc, state) ->
            val block = loc.toLocation(game.arena.world).block

            if (currentTicks >= state.openAt && currentTicks < state.closeAt && !state.isOpen) {
                state.isOpen = true
                block.type = Material.OPEN_EYEBLOSSOM

                val particleLoc = loc.toLocation(game.arena.world).add(0.5, 0.5, 0.5)
                game.arena.world.spawnParticle(
                    Particle.GLOW,
                    particleLoc,
                    20,
                    0.3,
                    0.3,
                    0.3,
                    0.1
                )
            }

            if (currentTicks >= state.closeAt && state.isOpen) {
                state.isOpen = false
                block.type = Material.CLOSED_EYEBLOSSOM

                val particleLoc = loc.toLocation(game.arena.world).add(0.5, 0.5, 0.5)
                game.arena.world.spawnParticle(
                    Particle.SMOKE,
                    particleLoc,
                    10,
                    0.2,
                    0.2,
                    0.2,
                    0.02
                )
            }
        }
    }

    private fun closeEyeblossoms() {
        eyeblossomBlocks.forEach { (loc, state) ->
            state.isOpen = false
            state.openAt = 0L
            state.closeAt = 0L

            val block = loc.toLocation(game.arena.world).block
            block.type = Material.CLOSED_EYEBLOSSOM
        }

        PluginManager.getLogger().info("[Арена #${game.arena.id}] Все цветки закрыты")
    }

    private fun startParticleTask() {
        particleTask = Bukkit.getScheduler().runTaskTimer(
            PluginManager.getPlugin(),
            Runnable {
                spawnEyeblossomParticles()
            },
            0L,
            10L
        )
    }

    private fun stopParticleTask() {
        particleTask?.cancel()
        particleTask = null
    }

    private fun spawnEyeblossomParticles() {
        eyeblossomBlocks.forEach { (loc, state) ->
            if (state.isOpen) {
                val blockLoc = loc.toLocation(game.arena.world)
                val block = blockLoc.block

                if (block.type == Material.OPEN_EYEBLOSSOM) {
                    val particleLoc = blockLoc.clone().add(0.5, 1.2, 0.5)
                    game.arena.world.spawnParticle(
                        Particle.END_ROD,
                        particleLoc,
                        2,
                        0.15,
                        0.1,
                        0.15,
                        0.01
                    )

                    game.arena.world.spawnParticle(
                        Particle.GLOW,
                        particleLoc,
                        1,
                        0.1,
                        0.05,
                        0.1,
                        0.0
                    )

                    if (Math.random() < 0.3) {
                        game.arena.world.spawnParticle(
                            Particle.ELECTRIC_SPARK,
                            particleLoc,
                            1,
                            0.05,
                            0.05,
                            0.05,
                            0.0
                        )
                    }
                }
            }
        }
    }

    fun tryHarvestEyeblossom(loc: SpawnLocation): Boolean {
        val state = eyeblossomBlocks[loc] ?: return false

        if (!state.isOpen) return false

        val block = loc.toLocation(game.arena.world).block
        block.type = Material.CLOSED_EYEBLOSSOM

        state.isOpen = false

        state.openAt = Long.MAX_VALUE
        state.closeAt = Long.MAX_VALUE

        val particleLoc = loc.toLocation(game.arena.world).add(0.5, 0.5, 0.5)
        game.arena.world.spawnParticle(
            Particle.GLOW,
            particleLoc,
            20,
            0.3,
            0.3,
            0.3,
            0.1
        )
        game.arena.world.spawnParticle(
            Particle.END_ROD,
            particleLoc,
            10,
            0.2,
            0.2,
            0.2,
            0.05
        )

        return true
    }

    private fun spawnCreakings() {
        val spawnLocations = game.arena.mapConfig.creakingSpawnLocations

        if (spawnLocations.isEmpty()) {
            PluginManager.getLogger().warning("[Арена #${game.arena.id}] Нет точек спавна для Скрипунов!")
            return
        }

        stopCreakingCheck()
        despawnAllCreakings()

        spawnLocations.forEach { spawnLoc ->
            val location = spawnLoc.toLocation(game.arena.world)
            val entity = game.arena.world.spawnEntity(location, EntityType.CREAKING) as? Creaking

            if (entity != null) {
                creakings.add(entity)

                @Suppress("DEPRECATION")
                entity.customName = "§5Ночной страж"
                entity.isCustomNameVisible = true
                entity.setAI(true)
                entity.isInvulnerable = true
                entity.isPersistent = true

                entity.maxHealth = 1000.0
                entity.health = 1000.0
                entity.fireTicks = 0
                entity.setGravity(true)

                entity.addPotionEffect(
                    PotionEffect(PotionEffectType.SPEED, Int.MAX_VALUE, game.getCurrentCreakingSpeedAmplifier(), false, false)
                )
                entity.addPotionEffect(
                    PotionEffect(PotionEffectType.STRENGTH, Int.MAX_VALUE, 0, false, false)
                )

                // Якорь нужен для возврата скрипуна на точку возрождения (например, если его скинули с карты).
                CreakingListener.setAnchor(entity, location)

                PluginManager.getLogger().info("[Арена #${game.arena.id}] Скрипун заспавнен в ${spawnLoc.x}, ${spawnLoc.y}, ${spawnLoc.z}")
            }
        }

        if (creakings.isNotEmpty()) {
            updateCreakingPhaseBuffs()
            startCreakingRangeCheck()
        }
    }

    fun updateCreakingPhaseBuffs() {
        val amp = game.getCurrentCreakingSpeedAmplifier()

        creakings.removeIf { !it.isValid }

        creakings.forEach { entity ->
            try {
                entity.removePotionEffect(PotionEffectType.SPEED)
                entity.addPotionEffect(
                    PotionEffect(PotionEffectType.SPEED, Int.MAX_VALUE, amp, false, false)
                )
            } catch (_: Exception) {
            }
        }
    }


    private fun despawnAllCreakings() {
        creakings.forEach { entity ->
            if (entity.isValid) {
                entity.remove()
            }
        }
        creakings.clear()
        PluginManager.getLogger().info("[Арена #${game.arena.id}] Все Скрипуны удалены")
    }

    private fun startCreakingRangeCheck() {
        creakingCheckTask = Bukkit.getScheduler().runTaskTimer(
            PluginManager.getPlugin(),
            Runnable {
                val spawnLocations = game.arena.mapConfig.creakingSpawnLocations

                creakings.removeIf { !it.isValid }

                if (creakings.size < spawnLocations.size) {
                    PluginManager.getLogger().warning("[Арена #${game.arena.id}] Скрипун исчез, респавним...")
                    spawnCreakings()
                    return@Runnable
                }

                val centerLocation = WAVE_CENTER

                creakings.forEachIndexed { index, creakingEntity ->
                    if (!creakingEntity.isInvulnerable) {
                        creakingEntity.isInvulnerable = true
                    }

                    if (!creakingEntity.isPersistent) {
                        creakingEntity.isPersistent = true
                    }

                    if (creakingEntity.health < creakingEntity.maxHealth) {
                        creakingEntity.health = creakingEntity.maxHealth
                    }

                    creakingEntity.fireTicks = 0

                    val distanceFromCenter = creakingEntity.location.distance(centerLocation)

                    if (distanceFromCenter > GameConfig.creakingAggroRadius) {
                        val originalSpawnLoc = spawnLocations.getOrNull(index)
                        if (originalSpawnLoc != null) {
                            val respawnPoint = originalSpawnLoc.toLocation(game.arena.world)
                            creakingEntity.teleport(respawnPoint)
                        }
                    }
                }
            },
            0L,
            20L
        )
    }

    private fun stopCreakingCheck() {
        creakingCheckTask?.cancel()
        creakingCheckTask = null
    }

    private fun startCleansingWave() {
        var currentRadius = 0.0
        val radiusIncrement = WAVE_MAX_RADIUS / WAVE_DURATION.toDouble()
        var ticks = 0L

        creakings.removeIf { creaking ->
            // Очистка скрипунов, которые оказались внутри волны
            if (creaking.isValid && creaking.location.distance(WAVE_CENTER) <= currentRadius) {
                creaking.remove()

                creaking.world.spawnParticle(
                    Particle.SOUL,
                    creaking.location.add(0.0, 1.0, 0.0),
                    20,
                    0.4,
                    0.6,
                    0.4,
                    0.05
                )

                true
            } else false
        }

        waveTask = Bukkit.getScheduler().runTaskTimer(
            PluginManager.getPlugin(),
            Runnable {
                ticks++
                currentRadius += radiusIncrement


                spawnWaveParticles(currentRadius)

                breakBlocksInRadius(currentRadius, radiusIncrement)

                if (ticks >= WAVE_DURATION) {
                    waveTask?.cancel()
                    waveTask = null
                    PluginManager.getLogger().info("[Арена #${game.arena.id}] Волна очищения завершена")
                }
            },
            0L,
            1L
        )

        PluginManager.getLogger().info("[Арена #${game.arena.id}] Запущена волна очищения")
    }

    private fun spawnWaveParticles(radius: Double) {
        val particleCount = (radius * 2).toInt().coerceIn(30, 160)
        val angleStep = (2 * Math.PI) / particleCount

        val minY = WAVE_CENTER.y - 5
        val maxY = WAVE_CENTER.y + 5

        for (i in 0 until particleCount) {
            val angle = i * angleStep
            val x = WAVE_CENTER.x + radius * cos(angle)
            val z = WAVE_CENTER.z + radius * sin(angle)

            for (y in minY.toInt()..maxY.toInt()) {

                val particleLoc = Location(game.arena.world, x, y.toDouble(), z)

                game.arena.world.spawnParticle(
                    Particle.SOUL_FIRE_FLAME,
                    particleLoc,
                    30,
                    0.1,
                    0.2,
                    0.1,
                    0.01
                )

                if (i % 6 == 0) {
                    game.arena.world.spawnParticle(
                        Particle.END_ROD,
                        particleLoc,
                        1,
                        0.0,
                        0.15,
                        0.0,
                        0.0
                    )
                }
            }
        }
    }

    private fun breakBlocksInRadius(currentRadius: Double, increment: Double) {
        val minY = -60
        val maxY = 300

        val centerX = WAVE_CENTER.blockX
        val centerZ = WAVE_CENTER.blockZ

        val minRadiusSq = (currentRadius - increment).let { it * it }
        val maxRadiusSq = currentRadius * currentRadius

        for (x in (centerX - currentRadius.toInt())..(centerX + currentRadius.toInt())) {
            for (z in (centerZ - currentRadius.toInt())..(centerZ + currentRadius.toInt())) {
                val distanceSq = ((x - centerX) * (x - centerX) + (z - centerZ) * (z - centerZ)).toDouble()
                if (distanceSq >= minRadiusSq && distanceSq <= maxRadiusSq) {
                    for (y in minY..maxY) {
                        val block = game.arena.world.getBlockAt(x, y, z)

                        if (GameConfig.allowedBlocks.contains(block.type)) {
                            val blockLoc = block.location.add(0.5, 0.5, 0.5)
                            game.arena.world.spawnParticle(
                                Particle.BLOCK,
                                blockLoc,
                                10,
                                0.3,
                                0.3,
                                0.3,
                                0.1,
                                block.blockData
                            )

                            block.type = Material.AIR
                        }
                    }
                }
            }
        }
    }

    fun isNightTime(): Boolean = isNight

    fun getEyeblossomLocation(block: org.bukkit.block.Block): SpawnLocation? {
        return eyeblossomBlocks.keys.firstOrNull { loc ->
            val blockLoc = loc.toLocation(game.arena.world)
            blockLoc.blockX == block.x && blockLoc.blockY == block.y && blockLoc.blockZ == block.z
        }
    }
}
