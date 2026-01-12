package ru.joutak.creakywars.listeners

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Creaking
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
import java.util.UUID

class CreakingListener : Listener {

    companion object {
        private const val AI_PERIOD_TICKS = 2
        private var creakingAITask: BukkitTask? = null

        private data class StuckState(
            var lastLoc: Location,
            var notMovedTicks: Int
        )

        private val stuck = mutableMapOf<UUID, StuckState>()

        // Мы принудительно ведём скрипунов на выбранную цель, не полагаясь на ванильный выбор таргета
        // (у скрипуна он зависит от "кто на него смотрит", что нам не подходит для мини-игры).
        private val desiredTarget = mutableMapOf<UUID, UUID>()

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
                            game.arena.world.entities
                                .filterIsInstance<Creaking>()
                                .forEach { creaking ->
                                    updateCreakingAI(creaking, game)
                                }
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

        private fun updateCreakingAI(creaking: Creaking, game: Game) {
            val aggroRadius = GameConfig.creakingAggroRadius
            val aggroRadiusSq = aggroRadius * aggroRadius

            val ignoreTeamId = getIgnoreTeamId(creaking)

            val anchor = getAnchor(creaking)

            val targetPlayer = game.activePlayers
                .asSequence()
                .filter { it.world == creaking.world }
                .filter { it.location.distanceSquared(creaking.location) <= aggroRadiusSq }
                .filter { player ->
                    if (ignoreTeamId == null) return@filter true
                    val teamId = game.getPlayerData(player)?.team?.id
                    teamId == null || teamId != ignoreTeamId
                }
                .minByOrNull { it.location.distanceSquared(creaking.location) }

            if (targetPlayer != null) {
                // Не доверяем ванильной логике скрипуна (она зависит от "кто на него смотрит").
                // Всегда ведём на ближайшего, быстро переагриваясь.
                desiredTarget[creaking.uniqueId] = targetPlayer.uniqueId
                forceSetTarget(creaking, targetPlayer)
                applyChaseForce(creaking, targetPlayer.location)

                handleStuckAndBreak(creaking, targetPlayer.location)
                sabotageBridges(creaking, targetPlayer.location)
                return
            }

            // Нет цели в радиусе — сбрасываем агр, чтобы не "запоминал" прошлую жертву.
            desiredTarget.remove(creaking.uniqueId)
            creaking.target = null

            if (anchor != null) {
                // Если в будущем появится "призыв на базу" — без игроков рядом он будет тянуться к якорю.
                applyChaseForce(creaking, anchor)
                handleStuckAndBreak(creaking, anchor)
                return
            }

            stuck.remove(creaking.uniqueId)
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

        private fun applyChaseForce(creaking: Creaking, target: Location) {
            // Лёгкий push к цели каждый AI-такт, чтобы:
            // 1) не зависеть от ванильной "заморозки" когда на него смотрят
            // 2) быстро перестраивать траекторию при смене цели
            if (creaking.hasMetadata("breaking")) return

            val v = target.toVector().subtract(creaking.location.toVector())
            if (v.lengthSquared() < 1.0) return

            val desired = v.normalize().multiply(0.18)
            // не пихаем вверх/вниз, иначе будет странно на лестницах
            desired.y = creaking.velocity.y.coerceIn(-0.15, 0.15)

            val vel = creaking.velocity
            creaking.velocity = vel.multiply(0.35).add(desired)
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

        val game = GameManager.getActiveGames().firstOrNull {
            it.arena.world == creaking.world
        } ?: return

        event.isCancelled = false
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