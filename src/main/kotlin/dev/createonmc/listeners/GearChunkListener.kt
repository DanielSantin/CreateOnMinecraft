package dev.createonmc.listeners

import dev.createonmc.gear.GearManager
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent

/**
 * Restaura engrenagens salvas em PDC quando um chunk é carregado.
 * Necessário porque restoreFromWorld() só vê chunks já carregados no startup;
 * chunks fora do spawn só são carregados quando jogadores chegam perto.
 */
class GearChunkListener(private val gearManager: GearManager) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkLoad(event: ChunkLoadEvent) {
        if (event.isNewChunk) return   // chunk novo nunca tem engrenagens
        gearManager.restoreFromChunk(event.chunk)
    }
}
