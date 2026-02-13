package ru.joutak.creakywars.listeners

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Fireball
import org.bukkit.entity.TNTPrimed
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.metadata.MetadataValue
import org.bukkit.scheduler.BukkitTask
import ru.joutak.creakywars.arenas.ArenaManager
import ru.joutak.creakywars.config.GameConfig
import ru.joutak.creakywars.game.Game
import ru.joutak.creakywars.game.GameManager
import ru.joutak.creakywars.game.Team
import ru.joutak.creakywars.utils.PluginManager
import ru.joutak.creakywars.utils.UseSuppressor
import kotlin.math.min

class ExplosivesListener : Listener {

    // Even if these blocks are (accidentally) included in allowed-blocks, explosions must never break them.
    private val unbreakableByExplosion: Set<Material> = setOf(
        Material.OBSIDIAN,
        Material.CRYING_OBSIDIAN,
        Material.BEDROCK,
        Material.BARRIER,
        Material.END_PORTAL_FRAME,
        Material.COMMAND_BLOCK,
        Material.REPEATING_COMMAND_BLOCK,
        Material.CHAIN_COMMAND_BLOCK,
        Material.STRUCTURE_BLOCK,
        Material.STRUCTURE_VOID,
        Material.JIGSAW,
        // Our core block should not be destroyed by TNT/fireballs.
        Material.CREAKING_HEART,
    )

    @EventHandler
    fun onTNTPlace(event: BlockPlaceEvent) {
        val player = event.player
        val game = GameManager.getGame(player)

        if (game == null || event.block.type != Material.TNT || !ArenaManager.isArena(event.block.world)) return

        event.isCancelled = true

        val tntLocation = event.block.location.toCenterLocation().add(0.0, 0.5, 0.0)
        event.block.world.playSound(tntLocation, org.bukkit.Sound.ENTITY_TNT_PRIMED, 1.0f, 1.0f)
        event.block.type = Material.AIR

        event.block.world.spawn(tntLocation, TNTPrimed::class.java).apply {
            fuseTicks = 40
            setMetadata(
                "EXPLOSION_CREATOR_UUID",
                FixedMetadataValue(PluginManager.getPlugin(), player.uniqueId.toString())
            )
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
        if (UseSuppressor.isSuppressed(player.uniqueId)) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return

        val hand = event.hand ?: return
        if (hand != EquipmentSlot.HAND && hand != EquipmentSlot.OFF_HAND) return

        val invItem =
            if (hand == EquipmentSlot.OFF_HAND) player.inventory.itemInOffHand else player.inventory.itemInMainHand
        if (invItem.type != Material.FIRE_CHARGE) return

        event.isCancelled = true

        if (invItem.amount > 1) {
            invItem.amount--
        } else {
            if (hand == EquipmentSlot.OFF_HAND) {
                player.inventory.setItemInOffHand(null)
            } else {
                player.inventory.setItemInMainHand(null)
            }
        }

        val fireball = player.launchProjectile(Fireball::class.java).apply {
            shooter = player
            // Keep a normal explosion radius; we still filter/break only allowed blocks in our custom handler.
            yield = 2.0f
            setMetadata(
                "EXPLOSION_CREATOR_UUID",
                FixedMetadataValue(PluginManager.getPlugin(), player.uniqueId.toString())
            )
            setMetadata("FIREBALL_START_LOC", FixedMetadataValue(PluginManager.getPlugin(), location))
        }

        startFireballTracking(fireball)
        player.world.playSound(player.location, org.bukkit.Sound.ENTITY_GHAST_SHOOT, 1.0f, 1.0f)
    }

    @EventHandler
    fun onProjectileHit(event: ProjectileHitEvent) {
        val fireball = event.entity as? Fireball ?: return
        if (!fireball.hasMetadata("EXPLOSION_CREATOR_UUID")) return

        // Let the entity explode naturally; EntityExplodeEvent is handled below.
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

        // Wind charges (WIND_CHARGE / BREEZE_WIND_CHARGE) should keep vanilla "wind burst" behaviour.
        // Our custom explosion logic is only for TNT/Fireball.
        if (event.entity.type.name.endsWith("WIND_CHARGE")) {
            // Prevent block damage just in case, but do not replace the effect with a fireball-like explosion.
            event.blockList().clear()
            event.yield = 0.0f
            return
        }

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

        val radius = power * 2.0

        // We only ever break blocks from the "allowed" list (player bridges, etc.).
        // Vanilla blockList is sometimes "patchy" for explosions in air; for fireballs we want bridges
        // to be destroyed reliably, so we always scan the radius and union with the provided list.
        val blocks = LinkedHashSet<Block>(512)
        blocksToBreak.asSequence()
            .filter { allowedBlocks.contains(it.type) }
            .forEach { blocks.add(it) }

        collectAllowedBlocks(location, radius, allowedBlocks).forEach { blocks.add(it) }

        // Break blocks depending on their (blast) resistance.
        // This prevents unrealistic behaviour like TNT/fireballs breaking obsidian.
        val breakable = ArrayList<Block>(blocks.size)
        for (block in blocks) {
            val type = block.type
            val impact = blockImpact(power, block, location, radius)
            if (impact <= 0.0f) continue
            if (!canBreakByExplosion(type, impact)) continue
            breakable.add(block)
        }

        breakable.forEach { it.type = Material.AIR }

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

    private fun blockImpact(power: Float, block: Block, center: Location, radius: Double): Float {
        val dist = block.location.toCenterLocation().distance(center)
        if (dist > radius) return 0.0f
        val rel = (dist / radius).toFloat().coerceIn(0.0f, 1.0f)
        return power * (1.0f - rel)
    }

    private fun canBreakByExplosion(type: Material, impact: Float): Boolean {
        if (unbreakableByExplosion.contains(type)) return false

        val resistance = getBlastResistance(type)
        // Scale factor tuned for our custom explosion power values (we treat "power" as 2..N).
        // The closer to the center, the higher the impact, so dense blocks survive on the edges.
        val threshold = impact * 10.0f
        return resistance <= threshold
    }

    private fun getBlastResistance(type: Material): Float {
        // Prefer API value if available (Paper/Bukkit provide it on modern versions).
        val viaApi = runCatching {
            val method =
                type.javaClass.methods.firstOrNull { it.name == "getBlastResistance" && it.parameterCount == 0 }
                    ?: return@runCatching null
            val value = method.invoke(type)
            when (value) {
                is Float -> value
                is Double -> value.toFloat()
                is Number -> value.toFloat()
                else -> null
            }
        }.getOrNull()

        return viaApi ?: fallbackBlastResistance(type)
    }

    private fun fallbackBlastResistance(type: Material): Float {
        val n = type.name

        if (n.contains("OBSIDIAN")) return 1200.0f
        if (n.contains("ANCIENT_DEBRIS")) return 1200.0f

        // Common soft blocks
        if (n.contains("GLASS") || n.contains("LEAVES") || n.contains("FLOWER") || n.contains("CARPET")) return 0.3f
        if (n.contains("WOOL")) return 0.8f
        if (n.contains("SAND") || n.contains("DIRT") || n.contains("GRAVEL") || n.contains("CLAY")) return 0.5f

        // Common bridge/defence blocks
        if (n.contains("PLANKS") || n.endsWith("_LOG") || n.endsWith("_WOOD")) return 3.0f
        if (n.contains("TERRACOTTA") || n.contains("CONCRETE") || n.contains("BRICKS")) return 4.2f
        if (n.contains("COBBLESTONE") || n.contains("STONE") || n.contains("DEEPSLATE")) return 6.0f
        if (n.contains("END_STONE")) return 9.0f

        // Reasonable default for "build" blocks
        return 3.0f
    }

    private fun collectAllowedBlocks(center: Location, radius: Double, allowed: Set<Material>): MutableList<Block> {
        val world = center.world ?: return mutableListOf()
        val cx = center.x
        val cy = center.y
        val cz = center.z
        val r = radius
        val r2 = r * r

        val minX = (cx - r).toInt() - 1
        val maxX = (cx + r).toInt() + 1
        val minY = (cy - r).toInt() - 1
        val maxY = (cy + r).toInt() + 1
        val minZ = (cz - r).toInt() - 1
        val maxZ = (cz + r).toInt() + 1

        val result = mutableListOf<Block>()
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    val block = world.getBlockAt(x, y, z)
                    val type = block.type
                    if (!allowed.contains(type)) continue

                    val bx = x + 0.5
                    val by = y + 0.5
                    val bz = z + 0.5
                    val dx = bx - cx
                    val dy = by - cy
                    val dz = bz - cz
                    if (dx * dx + dy * dy + dz * dz > r2) continue

                    result.add(block)
                }
            }
        }
        return result
    }
}