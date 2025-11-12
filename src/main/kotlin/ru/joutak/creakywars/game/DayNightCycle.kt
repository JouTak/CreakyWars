package ru.joutak.creakywars.game

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.entity.Creaking
import org.bukkit.entity.EntityType
import org.bukkit.scheduler.BukkitTask
import ru.joutak.creakywars.config.GameConfig
import ru.joutak.creakywars.utils.MessageUtils
import ru.joutak.creakywars.utils.PluginManager
import ru.joutak.creakywars.utils.SpawnLocation

class DayNightCycle(private val game: Game) {

    private var cycleTask: BukkitTask? = null
    private var creakingCheckTask: BukkitTask? = null
    private var particleTask: BukkitTask? = null
    private var isNight = false
    private var currentTicks = 0L

    private val eyeblossomBlocks = mutableMapOf<SpawnLocation, Boolean>()
    private var creaking: Creaking? = null

    private val DAY_START = 0L
    private val NIGHT_START = 14000L
    private val MINECRAFT_DAY_TICKS = 24000L

    fun start() {
        game.arena.mapConfig.eyeblossomLocations.forEach { loc ->
            eyeblossomBlocks[loc] = false
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
        despawnCreaking()

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
//        game.broadcastMessage("§5§l⭐ НАСТУПАЕТ НОЧЬ! §eEyeblossom открываются, Скрипун пробуждается...")
//        game.players.forEach { player ->
//            MessageUtils.sendTitle(player, "§5⭐ НОЧЬ", "§eОстерегайтесь Скрипуна!")
//            player.playSound(player.location, org.bukkit.Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 0.8f)
//        }

        openEyeblossoms()
        spawnCreaking()
    }

    private fun startDay() {
//        game.broadcastMessage("§e§l☀ НАСТУПАЕТ ДЕНЬ! §7Eyeblossom закрываются, Скрипун исчезает...")
//        game.players.forEach { player ->
//            MessageUtils.sendTitle(player, "§e☀ ДЕНЬ", "§7Время восстановиться")
//            player.playSound(player.location, org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.2f)
//        }

        closeEyeblossoms()
        stopCreakingCheck()
        despawnCreaking()
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

    private fun openEyeblossoms() {
        placeAllEyeblossoms(false)

        val openCount = (eyeblossomBlocks.size * GameConfig.eyeblossomOpenPercent).toInt()
        val shuffled = eyeblossomBlocks.keys.shuffled()

        var opened = 0
        for (loc in shuffled) {
            if (opened >= openCount) break

            eyeblossomBlocks[loc] = true

            val block = loc.toLocation(game.arena.world).block
            block.type = Material.OPEN_EYEBLOSSOM

            opened++
        }

        eyeblossomBlocks.keys.forEach { loc ->
            if (eyeblossomBlocks[loc] != true) {
                eyeblossomBlocks[loc] = false
            }
        }

        PluginManager.getLogger().info("[Арена #${game.arena.id}] Открыто $opened/${eyeblossomBlocks.size} цветков")
    }

    private fun closeEyeblossoms() {
        eyeblossomBlocks.keys.forEach { loc ->
            eyeblossomBlocks[loc] = false

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
        eyeblossomBlocks.forEach { (loc, isOpen) ->
            if (isOpen) {
                val blockLoc = loc.toLocation(game.arena.world)
                val block = blockLoc.block

                if (block.type == Material.OPEN_EYEBLOSSOM) {
                    val particleLoc = blockLoc.clone().add(0.5, 1.2, 0.5)
                    game.arena.world.spawnParticle(
                        Particle.END_ROD,           // Яркие белые частицы
                        particleLoc,
                        2,                          // Количество
                        0.15,                       // offset X
                        0.1,                        // offset Y
                        0.15,                       // offset Z
                        0.01                        // скорость
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
        val isOpen = eyeblossomBlocks[loc] ?: return false

        if (!isOpen) return false

        val block = loc.toLocation(game.arena.world).block
        block.type = Material.CLOSED_EYEBLOSSOM

        eyeblossomBlocks[loc] = false

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

    private fun spawnCreaking() {
        val spawnLoc = game.arena.mapConfig.creakingSpawnLocation ?: return

        stopCreakingCheck()
        despawnCreaking()

        val location = spawnLoc.toLocation(game.arena.world)
        val entity = game.arena.world.spawnEntity(location, EntityType.CREAKING) as? Creaking

        if (entity != null) {
            creaking = entity

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

            PluginManager.getLogger().info("[Арена #${game.arena.id}] Скрипун заспавнен в ${spawnLoc.x}, ${spawnLoc.y}, ${spawnLoc.z}")

            startCreakingRangeCheck()
        }
    }

    private fun despawnCreaking() {
        val entity = creaking
        if (entity != null && entity.isValid) {
            entity.remove()
            PluginManager.getLogger().info("[Арена #${game.arena.id}] Скрипун удален")
        }
        creaking = null
    }

    private fun startCreakingRangeCheck() {
        creakingCheckTask = Bukkit.getScheduler().runTaskTimer(
            PluginManager.getPlugin(),
            Runnable {
                val creakingEntity = creaking

                if (creakingEntity == null || !creakingEntity.isValid) {
                    PluginManager.getLogger().warning("[Арена #${game.arena.id}] Скрипун исчез, респавним...")
                    spawnCreaking()
                    return@Runnable
                }

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

                val spawnLoc = game.arena.mapConfig.creakingSpawnLocation ?: return@Runnable
                val spawnPoint = spawnLoc.toLocation(game.arena.world)

                val distance = creakingEntity.location.distance(spawnPoint)
                if (distance > GameConfig.creakingAggroRadius) {
                    creakingEntity.teleport(spawnPoint)
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

    fun isNightTime(): Boolean = isNight

    fun getEyeblossomLocation(block: org.bukkit.block.Block): SpawnLocation? {
        return eyeblossomBlocks.keys.firstOrNull { loc ->
            val blockLoc = loc.toLocation(game.arena.world)
            blockLoc.blockX == block.x && blockLoc.blockY == block.y && blockLoc.blockZ == block.z
        }
    }
}