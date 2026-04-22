@file:Suppress("DEPRECATION")

package ru.joutak.creakywars.config

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.EntityType
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.joutak.creakywars.resources.ResourceType
import ru.joutak.creakywars.trading.Trade
import ru.joutak.creakywars.utils.PluginManager
import ru.joutak.creakywars.trading.ShopCategory
import java.io.File

object GameConfig {
    private lateinit var config: FileConfiguration
    private lateinit var file: File

    val resourceTypes = mutableMapOf<String, ResourceType>()
    val trades = mutableListOf<Trade>()
    val shopCategories = mutableMapOf<String, ShopCategory>()

    var dayDurationTicks: Long = 6000L
    var nightDurationTicks: Long = 6000L
    var eyeblossomOpenPercent: Double = 0.5
    var respawnDelaySeconds: Int = 15
    var respawnSpectatorMode: Boolean = true
    var creakingAggroRadius: Double = 60.0
    var creakingBreakSpeed: Double = 1.0
    var creakingMaxAggroPerPlayer: Int = 2

    var protectionRadius: Double = 3.0
    var voidKillHeight: Int = 50
    var infiniteFood: Boolean = true
    val allowedBlocks = mutableSetOf<Material>()

    var infestedEntity: EntityType = EntityType.SILVERFISH
    var infestedSpawnChance: Double = 0.7
    var maxInfested: Int = 5

    data class UpgradeCost(val currency: String, val amount: Int)

    val upgradeSettings = mutableMapOf<String, Any>()

    fun init() {
        val plugin = PluginManager.getPlugin()
        file = File(plugin.dataFolder, "game-config.yml")

        if (!file.exists()) {
            plugin.saveResource("game-config.yml", false)
            PluginManager.getLogger().info("✓ Создан game-config.yml из шаблона")
        }

        load()
    }

    fun load() {
        val plugin = PluginManager.getPlugin()
        if (!::file.isInitialized) {
            file = File(plugin.dataFolder, "game-config.yml")

            if (!file.exists()) {
                plugin.saveResource("game-config.yml", false)
            }
        }

        config = YamlConfiguration.loadConfiguration(file)

        loadGlobalSettings()
        loadDayNightCycle()
        loadRespawnSettings()
        loadResources()
        loadShopCategories()
        loadTrades(plugin)
        loadUpgrades()
        loadInfestation()

        PluginManager.getLogger().info("✓ Игровой конфиг загружен!")
        PluginManager.getLogger().info("  - Разрешенных блоков: ${allowedBlocks.size}")
        PluginManager.getLogger().info("  - Ресурсов: ${resourceTypes.size}")
        PluginManager.getLogger().info("  - Трейдов: ${trades.size}")
    }

    private fun loadGlobalSettings() {
        protectionRadius = config.getDouble("settings.protection-radius", 3.0)
        voidKillHeight = config.getInt("settings.void-kill-height", 50)
        infiniteFood = config.getBoolean("settings.infinite-food", true)

        allowedBlocks.clear()
        val blocksList = config.getStringList("allowed-blocks")
        for (blockName in blocksList) {
            try {
                val mat = Material.valueOf(blockName.uppercase())
                allowedBlocks.add(mat)
            } catch (e: IllegalArgumentException) {
                PluginManager.getLogger().warning("⚠ Неверный материал в allowed-blocks: $blockName")
            }
        }
        PluginManager.getLogger().info("  [Настройки]")
        PluginManager.getLogger().info("    Радиус защиты: $protectionRadius")
        PluginManager.getLogger().info("    Смерть в бездне: Y < $voidKillHeight")
        PluginManager.getLogger().info("    Бесконечная еда: $infiniteFood")
    }

    private fun loadDayNightCycle() {
        dayDurationTicks = config.getLong("day-night-cycle.day-duration-ticks", 6000L)
        nightDurationTicks = config.getLong("day-night-cycle.night-duration-ticks", 6000L)
        eyeblossomOpenPercent = config.getDouble("day-night-cycle.eyeblossom-open-percent", 0.5)
        creakingAggroRadius = config.getDouble("day-night-cycle.creaking-aggro-radius", 60.0)
        creakingMaxAggroPerPlayer = config.getInt("day-night-cycle.creaking-max-per-player", 2).coerceAtLeast(1)
        creakingBreakSpeed = if (config.contains("day-night-cycle.creaking-break-speed")) {
            config.getDouble("day-night-cycle.creaking-break-speed", 1.5)
        } else {
            // legacy key in older configs
            config.getDouble("day-night-cycle.creakingBreakSpeed", 1.5)
        }
    }

    private fun loadRespawnSettings() {
        respawnDelaySeconds = config.getInt("respawn.delay-seconds", 15)
        respawnSpectatorMode = config.getBoolean("respawn.spectator-mode", true)
    }


    private fun loadResources() {
        resourceTypes.clear()
        val resourcesSection = config.getConfigurationSection("resources") ?: return
        for (key in resourcesSection.getKeys(false)) {
            val section = resourcesSection.getConfigurationSection(key) ?: continue
            try {
                val materialStr = section.getString("material", "SLIME_BALL")!!.uppercase()
                val material = Material.valueOf(materialStr)
                val displayName = section.getString("display-name", key)!!
                val spawnPeriod = section.getLong("spawn-period", 60L)
                val tier = section.getInt("tier", 1)
                resourceTypes[key] = ResourceType(key, material, displayName, spawnPeriod, tier)
            } catch (e: Exception) {
                // Log error
            }
        }
    }

    private fun loadInfestation() {
        val infestationSection = config.getConfigurationSection("infestation") ?: return

        try {
            infestedEntity = EntityType.valueOf(infestationSection.getString("entity", "SILVERFISH")!!)
            infestedSpawnChance = infestationSection.getDouble("chance-next", 0.7)
            maxInfested = infestationSection.getInt("max-spawned", 5)
        } catch (e: Exception) {
            // Log error
        }
    }

    private fun loadShopCategories(){
        shopCategories.clear()

        val categoriesSection = config.getConfigurationSection("shop-categories") ?: return
        val usedSlots = mutableSetOf<Int>()
        for (key in categoriesSection.getKeys(false)) {
            val section = config.getConfigurationSection(key) ?: continue
            try {
                val displayName = section.getString("display-name", key)!!
                val iconName = section.getString("icon", "STONE")!!.uppercase()
                val icon = Material.valueOf(iconName)
                val slot = section.getInt("slot", 45)

                if (slot !in 0..53) { continue }
                if (!usedSlots.add(slot)){
                    continue
                }
                shopCategories[key] = ShopCategory(
                    id = key,
                    displayName = displayName,
                    icon = icon,
                    slot = slot
                )

            } catch (_: Exception){
            }
        }
    }

    private fun loadTrades(plugin: Plugin) {
        trades.clear()
        val tradesSection = config.getConfigurationSection("trades") ?: return
        for (key in tradesSection.getKeys(false)) {
            val section = tradesSection.getConfigurationSection(key) ?: continue
            try {
                val cost = parseCost(section.getString("cost", "rubber_low:4")!!)
                val result = parseItem(section.getString("result", "STONE_SWORD:1")!!)
                val displayName = section.getString("display-name", key)!!
                val category = section.getString("category", "special")!!
                if (!shopCategories.containsKey(category)) { continue }
                PluginManager.getLogger().warning("Trade $key skipped: category $category was not found")
                val enchantments = section.getStringList("enchantments")
                if (enchantments.isNotEmpty()) {
                    applyEnchantments(result, enchantments)
                }
                val metaSection = section.getConfigurationSection("item-meta")
                if (metaSection != null) {
                    applyMeta(key, result, metaSection, plugin)
                }
                trades.add(Trade(key, cost, result, displayName, category))
            } catch (e: Exception) {
                // Log error
            }
        }
    }

    private fun applyMeta(key: String?, item: ItemStack, section: ConfigurationSection, plugin: Plugin) {
        val meta = item.itemMeta ?: return

        if (key != null) {
            meta.persistentDataContainer.set(NamespacedKey(plugin, key), PersistentDataType.BOOLEAN, true)
        }

        val nameString = section.getString("name")
        if (nameString != null) {
            meta.displayName(Component.text(nameString).decoration(TextDecoration.ITALIC, false).color(NamedTextColor.YELLOW))
        }

        val descriptionString = section.getString("description")
        if (descriptionString != null) {
            meta.lore(listOf(Component.text(descriptionString)))
        }

        item.itemMeta = meta
    }

    private fun loadUpgrades() {
        upgradeSettings.clear()
        val section = config.getConfigurationSection("upgrades") ?: return

        fun parseCost(sec: ConfigurationSection?): UpgradeCost {
            if (sec == null) return UpgradeCost("rubber_low", 999)
            val costStr = sec.getString("cost", "rubber_low:999")!!
            val parts = costStr.split(":")
            return UpgradeCost(parts[0], parts.getOrNull(1)?.toIntOrNull() ?: 1)
        }

        upgradeSettings["forge_1_cost"] = parseCost(section.getConfigurationSection("forge.tier-1"))
        upgradeSettings["forge_1_mult"] = section.getDouble("forge.tier-1.multiplier", 1.25)

        upgradeSettings["forge_2_cost"] = parseCost(section.getConfigurationSection("forge.tier-2"))
        upgradeSettings["forge_2_mult"] = section.getDouble("forge.tier-2.multiplier", 1.5)

        upgradeSettings["forge_3_cost"] = parseCost(section.getConfigurationSection("forge.tier-3"))
        upgradeSettings["forge_3_mult"] = section.getDouble("forge.tier-3.multiplier", 2.0)

        upgradeSettings["forge_4_cost"] = parseCost(section.getConfigurationSection("forge.tier-4"))
        upgradeSettings["forge_4_mult"] = section.getDouble("forge.tier-4.multiplier", 3.0)

        upgradeSettings["prot_1_cost"] = parseCost(section.getConfigurationSection("protection.tier-1"))
        upgradeSettings["prot_2_cost"] = parseCost(section.getConfigurationSection("protection.tier-2"))
        upgradeSettings["prot_3_cost"] = parseCost(section.getConfigurationSection("protection.tier-3"))

        upgradeSettings["sharp_cost"] = parseCost(section.getConfigurationSection("sharpness"))

        upgradeSettings["eff_1_cost"] = parseCost(section.getConfigurationSection("efficiency.tier-1"))
        upgradeSettings["eff_2_cost"] = parseCost(section.getConfigurationSection("efficiency.tier-2"))

        upgradeSettings["respawn_cost"] = parseCost(section.getConfigurationSection("fast-respawn"))
        upgradeSettings["respawn_time"] = section.getInt("fast-respawn.delay", 10)

        upgradeSettings["trap_cost"] = parseCost(section.getConfigurationSection("trap"))
        upgradeSettings["trap_range"] = section.getInt("trap.range", 15)
    }

    private fun parseCost(costStr: String): Pair<String, Int> {
        val parts = costStr.split(":")
        val resourceId = parts[0]
        val amount = parts.getOrNull(1)?.toIntOrNull() ?: 1
        return Pair(resourceId, amount)
    }

    private fun parseItem(itemStr: String): ItemStack {
        val parts = itemStr.split(":")
        val materialStr = parts[0].uppercase()
        val material = Material.valueOf(materialStr)
        val amount = parts.getOrNull(1)?.toIntOrNull() ?: 1
        return ItemStack(material, amount)
    }

    private fun applyEnchantments(item: ItemStack, enchantments: List<String>) {
        val meta = item.itemMeta ?: return
        enchantments.forEach { enchantStr ->
            try {
                val parts = enchantStr.split(":")
                val enchantName = parts[0].uppercase()
                val level = parts.getOrNull(1)?.toIntOrNull() ?: 1
                val enchantment = Enchantment.getByName(enchantName)
                if (enchantment != null) {
                    meta.addEnchant(enchantment, level, true)
                }
            } catch (_: Exception) {
            }
        }
        item.itemMeta = meta
    }

    fun reload() {
        if (!file.exists()) return
        load()
    }
}