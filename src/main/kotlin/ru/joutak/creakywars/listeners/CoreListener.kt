package ru.joutak.creakywars.listeners

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.entity.EntityExplodeEvent
import ru.joutak.creakywars.arenas.ArenaManager

class CoreListener : Listener {
    
    @EventHandler
    fun onBlockExplode(event: BlockExplodeEvent) {
        if (ArenaManager.isArena(event.block.world)) {
            event.isCancelled = true
        }
    }
    
    @EventHandler
    fun onEntityExplode(event: EntityExplodeEvent) {
        if (ArenaManager.isArena(event.entity.world)) {
            event.isCancelled = true
        }
    }
}