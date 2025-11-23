package ru.joutak.creakywars.listeners

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.EntityType
import org.bukkit.entity.Fireball
import org.bukkit.entity.Player
import org.bukkit.entity.TNTPrimed
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.metadata.MetadataValue
import org.bukkit.scheduler.BukkitTask // Импорт для явного указания типа task
import ru.joutak.creakywars.arenas.ArenaManager
import ru.joutak.creakywars.config.GameConfig
import ru.joutak.creakywars.game.Game
import ru.joutak.creakywars.game.GameManager
import ru.joutak.creakywars.game.Team
import ru.joutak.creakywars.utils.PluginManager
import kotlin.math.min

class ExplosivesListener : Listener {

    @EventHandler
    fun onTNTPlace(event: BlockPlaceEvent) {
        val player = event.player
        val game = GameManager.getGame(player)

        if (game == null || event.block.type != Material.TNT || !ArenaManager.isArena(event.block.world)) return

        event.isCancelled = true

        val tntLocation = event.block.location.toCenterLocation().add(0.0, 0.5, 0.0)
        event.block.type = Material.AIR

        event.block.world.spawn(tntLocation, TNTPrimed::class.java).apply {
            fuseTicks = 40
            setMetadata("EXPLOSION_CREATOR_UUID", FixedMetadataValue(PluginManager.getPlugin(), player.uniqueId.toString()))
            velocity = player.location.direction.multiply(0.2)
        }

        val item = player.inventory.itemInMainHand
        if (item.type == Material.TNT) {
            if (item.amount > 1) {
                item.amount--
            } else {
                player.inventory.setItemInMainHand(null)
            }
        }
    }

    @EventHandler
    fun onFireballLaunch(event: PlayerInteractEvent) {
        val player = event.player
        val game = GameManager.getGame(player)

        if (game == null || !ArenaManager.isArena(player.world)) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return

        val item = player.inventory.itemInMainHand
        if (item.type != Material.FIRE_CHARGE) return

        event.isCancelled = true

        if (item.amount > 1) {
            item.amount--
        } else {
            player.inventory.setItemInMainHand(null)
        }

        val fireball = player.launchProjectile(Fireball::class.java).apply {
            shooter = player
            yield = 0.0f

            setMetadata("EXPLOSION_CREATOR_UUID", FixedMetadataValue(PluginManager.getPlugin(), player.uniqueId.toString()))
            setMetadata("FIREBALL_START_LOC", FixedMetadataValue(PluginManager.getPlugin(), location))
        }

        startFireballTracking(fireball)

        player.world.playSound(player.location, org.bukkit.Sound.ENTITY_GHAST_SHOOT, 1.0f, 1.0f)
    }

    @EventHandler
    fun onProjectileHit(event: ProjectileHitEvent) {
        val fireball = event.entity as? Fireball ?: return

        if (!fireball.hasMetadata("EXPLOSION_CREATOR_UUID")) return

        val hitLoc = event.hitBlock?.location?.toCenterLocation() ?: event.hitEntity?.location ?: fireball.location

        fireball.world.createExplosion(hitLoc, 3.0f, false, true)
        fireball.remove()
    }

    private fun startFireballTracking(fireball: Fireball) {
        val plugin = PluginManager.getPlugin()
        val maxDistanceSquared = 150.0 * 150.0

        var task: BukkitTask? = null

        val runnable = Runnable {
            if (task == null) return@Runnable

            if (fireball.isDead || !fireball.isValid || !fireball.hasMetadata("FIREBALL_START_LOC")) {
                task?.cancel()
                return@Runnable
            }

            val startLocMeta = fireball.getMetadata("FIREBALL_START_LOC").firstOrNull() ?: return@Runnable
            val startLoc = startLocMeta.value() as? Location ?: return@Runnable

            if (fireball.location.distanceSquared(startLoc) > maxDistanceSquared) {
                fireball.remove()
                task?.cancel()
            }
        }

        task = Bukkit.getScheduler().runTaskTimer(plugin, runnable, 0L, 5L)
    }


    @EventHandler
    fun onEntityExplode(event: EntityExplodeEvent) {
        val game = GameManager.getGame(event.entity.world) ?: return
        if (!ArenaManager.isArena(event.entity.world)) return

        event.isCancelled = true

        val explosionLocation = event.location

        val actualPower = event.yield.coerceAtLeast(2.0f)

        var sourceTeam: Team? = null
        val creatorMeta: List<MetadataValue> = event.entity.getMetadata("EXPLOSION_CREATOR_UUID")
        if (creatorMeta.isNotEmpty()) {
            val creatorUUID = creatorMeta[0].asString()
            val creatorPlayer = Bukkit.getPlayer(java.util.UUID.fromString(creatorUUID))
            sourceTeam = creatorPlayer?.let { game.getTeam(it) }
        }

        handleCustomExplosion(game, explosionLocation, actualPower, sourceTeam, event.blockList())

        if (event.entity is TNTPrimed || event.entity is Fireball) {
            event.entity.remove()
        }
    }

    @EventHandler
    fun onBlockExplode(event: BlockExplodeEvent) {
        if (ArenaManager.isArena(event.block.world)) {
            event.isCancelled = true
        }
    }

    private fun handleCustomExplosion(
        game: Game,
        location: Location,
        power: Float,
        sourceTeam: Team?,
        blocksToBreak: MutableList<Block>
    ) {
        val allowedBlocks = GameConfig.allowedBlocks

        blocksToBreak.removeIf { block ->
            !allowedBlocks.contains(block.type)
        }

        blocksToBreak.forEach { it.type = Material.AIR }

        val radius = power * 2.0
        val explosionVector = location.toVector()

        game.players.forEach { player ->
            if (player.gameMode == org.bukkit.GameMode.SPECTATOR) return@forEach

            val playerTeam = game.getTeam(player)

            if (sourceTeam != null && playerTeam == sourceTeam) {
                return@forEach
            }

            val distance = player.location.distance(location)

            if (distance <= radius) {
                val eyeLocation = player.getEyeLocation()
                val direction = location.toVector().subtract(eyeLocation.toVector()).normalize()

                val rayTraceResult = player.world.rayTraceBlocks(
                    eyeLocation,
                    direction,
                    distance
                )

                val exposure = if (rayTraceResult?.hitBlock != null) 0.1f else 1.0f

                val relativeDistance = (distance / radius).toFloat()
                val damage = (power * (1.0f - relativeDistance) * exposure * 7.0f).toDouble()

                if (damage > 0.0) {
                    player.damage(damage)

                    val velocity = player.location.toVector().subtract(explosionVector).normalize()
                    velocity.multiply(min(0.7 + power * 0.1, 1.5))
                    player.velocity = velocity
                }
            }
        }

        location.world.spawnParticle(org.bukkit.Particle.EXPLOSION, location, 1)
        location.world.playSound(location, org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 4.0f, 1.0f)
    }
}