package dev.createonmc.listeners

import dev.createonmc.axle.AxlePos
import dev.createonmc.gear.GearManager
import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.plugin.Plugin

class BeltBlockListener(
    private val gearManager: GearManager,
    private val plugin: Plugin
) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        updateAdjacentBelts(event.block)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val block = event.block
        // Delay 1 tick so the block is actually removed before we re-scan.
        Bukkit.getRegionScheduler().runDelayed(plugin, block.location, { updateAdjacentBelts(block) }, 1L)
    }

    private fun updateAdjacentBelts(block: Block) {
        val world = block.world
        for ((dx, dy, dz) in ADJACENT) {
            val pos = AxlePos(world.name, block.x + dx, block.y + dy, block.z + dz)
            val (belt, slotIndex) = gearManager.beltBlockPos[pos] ?: continue
            gearManager.updateInteractorAt(world, belt, slotIndex)
        }
    }

    companion object {
        private val ADJACENT = listOf(
            Triple( 0,  1,  0), Triple( 0, -1,  0),
            Triple( 1,  0,  0), Triple(-1,  0,  0),
            Triple( 0,  0,  1), Triple( 0,  0, -1),
        )
    }
}
