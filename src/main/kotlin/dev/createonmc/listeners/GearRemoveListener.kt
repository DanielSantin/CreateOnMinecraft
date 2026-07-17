package dev.createonmc.listeners

import dev.createonmc.gear.GearManager
import org.bukkit.entity.ItemDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityRemoveEvent

/**
 * Limpa o estado de uma engrenagem quando o ItemDisplay dela é removido do mundo
 * (/kill, explosão, outro plugin). Substitui a varredura de displays mortos que
 * rodava a cada tick no GearManager.
 */
class GearRemoveListener(private val gearManager: GearManager) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntityRemove(event: EntityRemoveEvent) {
        val display = event.entity as? ItemDisplay ?: return
        // UNLOAD preserva o estado em memória/PDC para restauração no chunk reload
        if (event.cause == EntityRemoveEvent.Cause.UNLOAD) return
        gearManager.onDisplayRemoved(display)
    }
}
