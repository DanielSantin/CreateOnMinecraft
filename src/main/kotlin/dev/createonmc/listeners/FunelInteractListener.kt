package dev.createonmc.listeners

import dev.createonmc.axle.AxlePos
import dev.createonmc.gear.GearManager
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.Plugin
import java.util.UUID

class FunelInteractListener(private val gearManager: GearManager, private val plugin: Plugin) : Listener {

    private val funelModel = NamespacedKey("ssggearmachine", "funel")
    private val recentInteracts = mutableMapOf<UUID, Long>()

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onRightClickBlock(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != EquipmentSlot.HAND) return
        val player = event.player
        val block = event.clickedBlock ?: return

        // ── Shift + funel item + right-click on container → place funel ──────
        if (block.state is org.bukkit.block.Container && player.isSneaking && isFunelItem(player)) {
            if (debounce(player.uniqueId)) return
            event.isCancelled = true
            val containerPos = AxlePos(block.world.name, block.x, block.y, block.z)
            if (!gearManager.placeFunel(block.world, containerPos, event.blockFace))
                player.sendMessage("§cNenhuma esteira encontrada adjacente ao contêiner.")
            return
        }

        // ── Right-click on funel barrier → toggle state ───────────────────────
        if (block.type == Material.BARRIER) {
            val funel = findFunelAt(block.location)
            if (funel != null) {
                if (debounce(player.uniqueId)) return
                event.isCancelled = true
                gearManager.toggleFunel(funel)
                return
            }
        }
    }

    /** Searches for a funel at [blockLoc] (barrier block). Returns its UUID or null. */
    private fun findFunelAt(blockLoc: Location): UUID? {
        val pos = AxlePos(blockLoc.world?.name ?: return null, blockLoc.blockX, blockLoc.blockY, blockLoc.blockZ)
        return gearManager.findFunelAtBarrier(pos)
    }

    private fun isFunelItem(player: Player): Boolean =
        player.inventory.itemInMainHand.itemMeta?.itemModel == funelModel

    private fun debounce(uuid: UUID): Boolean {
        val now = System.currentTimeMillis()
        if (now - (recentInteracts[uuid] ?: 0L) < 100L) return true
        recentInteracts[uuid] = now
        return false
    }
}
