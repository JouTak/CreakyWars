@file:Suppress("DEPRECATION")

package ru.joutak.creakywars.upgrades

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import ru.joutak.creakywars.config.GameConfig
import ru.joutak.creakywars.game.Game
import ru.joutak.creakywars.game.Team
import ru.joutak.creakywars.resources.ResourceSpawner
import ru.joutak.creakywars.utils.MessageUtils
import ru.joutak.creakywars.utils.PluginManager

object UpgradeGui : Listener {

    private const val TITLE = "§bКомандные улучшения"

    init {
        Bukkit.getPluginManager().registerEvents(this, PluginManager.getPlugin())
    }

    fun init() {}

    fun open(player: Player, game: Game) {
        val team = game.getTeam(player) ?: return
        val inventory = Bukkit.createInventory(null, 27, TITLE)

        inventory.setItem(10, createForgeItem(team))
        inventory.setItem(11, createProtectionItem(team))
        inventory.setItem(12, createSharpnessItem(team))
        inventory.setItem(13, createEfficiencyItem(team))
        inventory.setItem(15, createRespawnItem(team))
        inventory.setItem(16, createTrapItem(team))

        val filler = ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
            val meta = itemMeta!!
            meta.setDisplayName(" ")
            itemMeta = meta
        }
        for (i in 0 until 27) {
            if (inventory.getItem(i) == null) inventory.setItem(i, filler)
        }

        player.openInventory(inventory)
    }


    private fun createForgeItem(team: Team): ItemStack {
        val tier = team.forgeTier + 1
        val isMax = tier > 4

        val costData = if (!isMax) GameConfig.upgradeSettings["forge_${tier}_cost"] as? GameConfig.UpgradeCost else null

        val mat = Material.FURNACE // Оставил как было
        val item = ItemStack(mat)
        val meta = item.itemMeta!!

        meta.setDisplayName(if (isMax) "§aНебесная кузница (МАКС)" else "§eНебесная кузница $tier")
        val lore = mutableListOf<String>()
        lore.add("§7Увеличивает скорость спавна")
        lore.add("§7ресурсов на вашей базе.")
        lore.add("")

        if (isMax) {
            lore.add("§aКуплено полностью!")
            meta.addEnchant(Enchantment.LURE, 1, true)
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
        } else {
            val resName = GameConfig.resourceTypes[costData!!.currency]?.displayName ?: "Ресурс"
            val has = costData.amount
            lore.add("§7Цена: §f${has}x $resName")
            lore.add("§eНажмите для улучшения!")
        }

        meta.lore = lore
        item.itemMeta = meta
        return item
    }

    private fun createProtectionItem(team: Team): ItemStack {
        val tier = team.protectionLevel + 1
        val isMax = tier > 3

        val costData = if (!isMax) GameConfig.upgradeSettings["prot_${tier}_cost"] as? GameConfig.UpgradeCost else null

        val item = ItemStack(Material.IRON_CHESTPLATE)
        val meta = item.itemMeta!!
        meta.setDisplayName(if (isMax) "§aУкрепленная броня (МАКС)" else "§eУкрепленная броня $tier")
        val lore = mutableListOf<String>()
        lore.add("§7Ваша команда получает")
        lore.add("§7Защиту $tier на всю броню.")
        lore.add("")

        if (isMax) {
            lore.add("§aКуплено полностью!")
            meta.addEnchant(Enchantment.PROTECTION, 1, true)
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
        } else {
            val resName = GameConfig.resourceTypes[costData!!.currency]?.displayName ?: "Ресурс"
            lore.add("§7Цена: §f${costData.amount}x $resName")
            lore.add("§eНажмите для улучшения!")
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        meta.lore = lore
        item.itemMeta = meta
        return item
    }

    private fun createSharpnessItem(team: Team): ItemStack {
        val isBought = team.sharpnessLevel >= 1
        val costData = GameConfig.upgradeSettings["sharp_cost"] as? GameConfig.UpgradeCost

        val item = ItemStack(Material.IRON_SWORD)
        val meta = item.itemMeta!!
        meta.setDisplayName(if (isBought) "§aОстрые клинки (МАКС)" else "§eОстрые клинки")
        val lore = mutableListOf<String>()
        lore.add("§7Ваша команда получает")
        lore.add("§7Остроту I на все мечи.")
        lore.add("")

        if (isBought) {
            lore.add("§aКуплено!")
            meta.addEnchant(Enchantment.SHARPNESS, 1, true)
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
        } else {
            val resName = GameConfig.resourceTypes[costData!!.currency]?.displayName ?: "Ресурс"
            lore.add("§7Цена: §f${costData.amount}x $resName")
            lore.add("§eНажмите для улучшения!")
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        meta.lore = lore
        item.itemMeta = meta
        return item
    }

    private fun createEfficiencyItem(team: Team): ItemStack {
        val tier = team.efficiencyLevel + 1
        val isMax = tier > 2
        val costData = if (!isMax) GameConfig.upgradeSettings["eff_${tier}_cost"] as? GameConfig.UpgradeCost else null

        val item = ItemStack(Material.GOLDEN_PICKAXE)
        val meta = item.itemMeta!!
        meta.setDisplayName(if (isMax) "§aЭффективность (МАКС)" else "§eЭффективность $tier")
        val lore = mutableListOf<String>()
        lore.add("§7Ускоряет добычу блоков")
        lore.add("§7для всей команды.")
        lore.add("")

        if (isMax) {
            lore.add("§aКуплено полностью!")
            meta.addEnchant(Enchantment.EFFICIENCY, 1, true)
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
        } else {
            val resName = GameConfig.resourceTypes[costData!!.currency]?.displayName ?: "Ресурс"
            lore.add("§7Цена: §f${costData.amount}x $resName")
            lore.add("§eНажмите для улучшения!")
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        meta.lore = lore
        item.itemMeta = meta
        return item
    }

    private fun createRespawnItem(team: Team): ItemStack {
        val isBought = team.hasFastRespawn
        val costData = GameConfig.upgradeSettings["respawn_cost"] as? GameConfig.UpgradeCost
        val time = GameConfig.upgradeSettings["respawn_time"] as? Int ?: 10

        val item = ItemStack(Material.LEATHER_BOOTS)
        val meta = item.itemMeta!!
        meta.setDisplayName(if (isBought) "§aБыстрый респавн (МАКС)" else "§eБыстрый респавн")
        val lore = mutableListOf<String>()
        lore.add("§7Уменьшает время возрождения")
        lore.add("§7до $time секунд.")
        lore.add("")

        if (isBought) {
            lore.add("§aКуплено!")
            meta.addEnchant(Enchantment.PROTECTION, 1, true)
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
        } else {
            val resName = GameConfig.resourceTypes[costData!!.currency]?.displayName ?: "Ресурс"
            lore.add("§7Цена: §f${costData.amount}x $resName")
            lore.add("§eНажмите для улучшения!")
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        meta.lore = lore
        item.itemMeta = meta
        return item
    }

    private fun createTrapItem(team: Team): ItemStack {
        val isBought = team.trapActive
        val costData = GameConfig.upgradeSettings["trap_cost"] as? GameConfig.UpgradeCost

        val item = ItemStack(Material.TRIPWIRE_HOOK)
        val meta = item.itemMeta!!
        meta.setDisplayName(if (isBought) "§cЛовушка заряжена!" else "§eЛовушка")
        val lore = mutableListOf<String>()
        lore.add("§7Предупреждает о врагах")
        lore.add("§7рядом с базой.")
        lore.add("§7Сбрасывается после срабатывания.")
        lore.add("")

        if (isBought) {
            lore.add("§cУже активна!")
            meta.addEnchant(Enchantment.UNBREAKING, 1, true)
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
        } else {
            val resName = GameConfig.resourceTypes[costData!!.currency]?.displayName ?: "Ресурс"
            lore.add("§7Цена: §f${costData.amount}x $resName")
            lore.add("§eНажмите для покупки!")
        }
        meta.lore = lore
        item.itemMeta = meta
        return item
    }


    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.title == TITLE) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        if (event.view.title != TITLE) return
        event.isCancelled = true

        val player = event.whoClicked as? Player ?: return
        val currentInventory = event.clickedInventory

        if (currentInventory != event.view.topInventory) return

        val game = ru.joutak.creakywars.game.GameManager.getGame(player) ?: return
        val team = game.getTeam(player) ?: return

        val slot = event.slot

        when (slot) {
            10 -> buyForge(player, game, team)
            11 -> buyProtection(player, game, team)
            12 -> buySharpness(player, game, team)
            13 -> buyEfficiency(player, game, team)
            15 -> buyRespawn(player, game, team)
            16 -> buyTrap(player, game, team)
        }

        open(player, game)
    }

    private fun attemptBuy(player: Player, cost: GameConfig.UpgradeCost): Boolean {
        val resourceType = GameConfig.resourceTypes[cost.currency] ?: return false
        val mat = resourceType.material
        val amount = cost.amount

        if (!player.inventory.contains(mat, amount)) {
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
            MessageUtils.sendMessage(player, "§cНедостаточно ресурсов!")
            return false
        }

        var remaining = amount
        player.inventory.contents.forEach { item ->
            if (item != null && item.type == mat && remaining > 0) {
                if (item.amount > remaining) {
                    item.amount -= remaining
                    remaining = 0
                } else {
                    remaining -= item.amount
                    item.amount = 0
                }
            }
        }
        player.inventory.storageContents = player.inventory.storageContents.map {
            if (it != null && it.amount <= 0) null else it
        }.toTypedArray()

        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2f)
        return true
    }

    private fun notifyTeam(game: Game, team: Team, upgradeName: String) {
        team.getOnlinePlayers().forEach { p ->
            MessageUtils.sendMessage(p, "§aКомандное улучшение §e$upgradeName §aприобретено!")
            p.playSound(p.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f)
            game.getPlayerData(p)?.loadout?.refreshLoadout()
        }
    }

    private fun buyForge(player: Player, game: Game, team: Team) {
        if (team.forgeTier >= 4) return
        val tier = team.forgeTier + 1
        val cost = GameConfig.upgradeSettings["forge_${tier}_cost"] as GameConfig.UpgradeCost
        val mult = GameConfig.upgradeSettings["forge_${tier}_mult"] as Double

        if (attemptBuy(player, cost)) {
            team.forgeTier = tier
            ResourceSpawner.updateTeamMultiplier(game, team, mult)
            notifyTeam(game, team, "Небесная кузница $tier")
        }
    }

    private fun buyProtection(player: Player, game: Game, team: Team) {
        if (team.protectionLevel >= 3) return
        val tier = team.protectionLevel + 1
        val cost = GameConfig.upgradeSettings["prot_${tier}_cost"] as GameConfig.UpgradeCost

        if (attemptBuy(player, cost)) {
            team.protectionLevel = tier
            notifyTeam(game, team, "Укрепленная броня $tier")
        }
    }

    private fun buySharpness(player: Player, game: Game, team: Team) {
        if (team.sharpnessLevel >= 1) return
        val cost = GameConfig.upgradeSettings["sharp_cost"] as GameConfig.UpgradeCost

        if (attemptBuy(player, cost)) {
            team.sharpnessLevel = 1
            notifyTeam(game, team, "Острые клинки")
        }
    }

    private fun buyEfficiency(player: Player, game: Game, team: Team) {
        if (team.efficiencyLevel >= 2) return
        val tier = team.efficiencyLevel + 1
        val cost = GameConfig.upgradeSettings["eff_${tier}_cost"] as GameConfig.UpgradeCost

        if (attemptBuy(player, cost)) {
            team.efficiencyLevel = tier
            notifyTeam(game, team, "Эффективность $tier")
        }
    }

    private fun buyRespawn(player: Player, game: Game, team: Team) {
        if (team.hasFastRespawn) return
        val cost = GameConfig.upgradeSettings["respawn_cost"] as GameConfig.UpgradeCost

        if (attemptBuy(player, cost)) {
            team.hasFastRespawn = true
            notifyTeam(game, team, "Быстрый респавн")
        }
    }

    private fun buyTrap(player: Player, game: Game, team: Team) {
        if (team.trapActive) return
        val cost = GameConfig.upgradeSettings["trap_cost"] as GameConfig.UpgradeCost

        if (attemptBuy(player, cost)) {
            team.trapActive = true
            notifyTeam(game, team, "Ловушка")
        }
    }
}