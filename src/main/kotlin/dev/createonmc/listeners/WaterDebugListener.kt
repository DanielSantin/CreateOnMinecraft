package dev.createonmc.listeners

import org.bukkit.Material
import org.bukkit.block.data.Levelled
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import java.util.logging.Logger

/**
 * Debug tool: segure uma GARRAFA DE VIDRO e clique com direito em qualquer bloco.
 * Printa water level no chat do jogador e uma linha resumida no console.
 */
class WaterDebugListener(private val log: Logger) : Listener {

    @EventHandler
    fun onRightClick(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != EquipmentSlot.HAND) return
        if (event.player.inventory.itemInMainHand.type != Material.GLASS_BOTTLE) return

        val clicked = event.clickedBlock ?: return
        val target = clicked.getRelative(event.blockFace)
        event.isCancelled = true

        val p = event.player

        if (target.type != Material.WATER) {
            val msg = "[WaterDebug] NOT_WATER ${target.type} @ (${target.x},${target.y},${target.z}) | clicked=${clicked.type} face=${event.blockFace}"
            p.sendMessage("§c$msg")
            log.info(msg)
            return
        }

        val level = (target.blockData as? Levelled)?.level ?: -1

        fun lvl(x: Int, y: Int, z: Int): Int {
            val b = target.world.getBlockAt(x, y, z)
            if (b.type != Material.WATER) return 8
            return (b.blockData as? Levelled)?.level ?: 8
        }

        val x = target.x; val y = target.y; val z = target.z

        val lE = lvl(x+1,y,z); val lW = lvl(x-1,y,z)
        val lS = lvl(x,y,z+1); val lN = lvl(x,y,z-1)
        val lU = lvl(x,y+1,z); val lD = lvl(x,y-1,z)

        val vx = lE - lW   // >0 = flui Leste
        val vz = lS - lN   // >0 = flui Sul

        val typeStr = when (level) { 0 -> "SOURCE"; in 1..7 -> "FLOWING"; 8 -> "FALLING"; else -> "?" }
        val xDir = when { vx > 0 -> "→E(+$vx)"; vx < 0 -> "←W($vx)"; else -> "X=0" }
        val zDir = when { vz > 0 -> "→S(+$vz)"; vz < 0 -> "←N($vz)"; else -> "Z=0" }

        // ── Console: tudo em uma linha, fácil de copiar ──────────────────────
        val consoleLine = "[WaterDebug] ($x,$y,$z) lvl=$level $typeStr | vx=$vx($xDir) vz=$vz($zDir) | E:$lE W:$lW S:$lS N:$lN U:$lU D:$lD | face=${event.blockFace}"
        log.info(consoleLine)

        // ── Chat: formatado para leitura ──────────────────────────────────────
        val typeTag = when (level) { 0 -> "§asource"; in 1..7 -> "§eflowing"; 8 -> "§bfalling"; else -> "§c?" }
        val xArrow  = when { vx > 0 -> "§b→E §7(+$vx)"; vx < 0 -> "§b←W §7($vx)"; else -> "§7X=0" }
        val zArrow  = when { vz > 0 -> "§b→S §7(+$vz)"; vz < 0 -> "§b←N §7($vz)"; else -> "§7Z=0" }

        p.sendMessage("§8─── §7WaterDebug §8───────────────────")
        p.sendMessage("§7Pos §f($x, $y, $z)  §7Type $typeTag §7lvl=§f$level")
        p.sendMessage("§7Flow  X $xArrow   Z $zArrow")
        p.sendMessage("§7E:§f$lE §7W:§f$lW §7S:§f$lS §7N:§f$lN §7U:§f$lU §7D:§f$lD")
        p.sendMessage("§8────────────────────────────────────")
    }
}
