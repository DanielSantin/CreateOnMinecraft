package dev.createonmc.listeners

import dev.createonmc.axle.AxleAxis
import dev.createonmc.gear.GearManager
import dev.createonmc.gear.GearType
import dev.createonmc.util.AxleUtil
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.BlockFace
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector

class AxleInteractListener(
    private val gearManager: GearManager
) : Listener {

    constructor(plugin: dev.createonmc.CreateOnMinecraftPlugin, gearManager: GearManager)
        : this(gearManager)

    private val rpmKey = NamespacedKey("createonmc", "motor_rpm")
    // Guard against Paper firing PlayerInteractEvent for both hands in the same tick
    private val recentPlacements = mutableMapOf<java.util.UUID, Long>()

    @EventHandler
    fun onRightClickBlock(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != org.bukkit.inventory.EquipmentSlot.HAND) return
        val player = event.player
        if (player.inventory.itemInMainHand.type != Material.STICK) return
        val block = event.clickedBlock ?: return

        val gearType = heldGearType(player) ?: return

        // Deduplicate: Paper can fire this event twice per right-click (main + off hand
        // even with the hand check above, on some server versions). Ignore if same player
        // placed within the last 100 ms.
        val now = System.currentTimeMillis()
        if (now - (recentPlacements[player.uniqueId] ?: 0L) < 100L) return
        recentPlacements[player.uniqueId] = now

        event.isCancelled = true

        val target = block.getRelative(event.blockFace)
        val (orientQ, axis) = AxleUtil.orientFromFace(event.blockFace)
        val (isMotor, rpm) = motorParams(gearType, player)

        gearManager.spawnGear(player.world, target.x, target.y, target.z, orientQ, axis,
            gearType = gearType, isMotor = isMotor, rpm = rpm)
    }

    @EventHandler
    fun onRightClickGear(event: PlayerInteractEntityEvent) {
        val player = event.player
        if (player.inventory.itemInMainHand.type != Material.STICK) return
        val interaction = event.rightClicked as? Interaction ?: return
        val pos = gearManager.getPosForInteraction(interaction.uniqueId) ?: return
        val entry = gearManager.getEntry(pos) ?: return
        event.isCancelled = true

        val face = rayHitFace(player.eyeLocation.toVector(), player.location.direction,
                              pos.bx, pos.by, pos.bz) ?: return
        val (dx, dy, dz) = faceOffset(face)

        val gearType = heldGearType(player)
        val (isMotor, rpm) = motorParams(gearType, player)

        gearManager.spawnGear(
            player.world, pos.bx + dx, pos.by + dy, pos.bz + dz,
            entry.orientQ, entry.axis, gearType = gearType, isMotor = isMotor, rpm = rpm
        )
    }

    @EventHandler
    fun onAttackGear(event: EntityDamageByEntityEvent) {
        val player = event.damager as? Player ?: return
        if (player.inventory.itemInMainHand.type != Material.STICK) return
        val interaction = event.entity as? Interaction ?: return
        val pos = gearManager.getPosForInteraction(interaction.uniqueId) ?: return
        event.isCancelled = true
        gearManager.removeGear(player.world, pos.bx, pos.by, pos.bz, dropItem = true)
    }

    // Hit a barrier block to remove the water wheel (or any future gear with collision blocks)
    @EventHandler
    fun onHitBarrier(event: PlayerInteractEvent) {
        if (event.action != Action.LEFT_CLICK_BLOCK) return
        if (event.hand != org.bukkit.inventory.EquipmentSlot.HAND) return
        val block = event.clickedBlock ?: return
        if (block.type != Material.BARRIER) return
        event.isCancelled = true
        gearManager.removeGear(block.world, block.x, block.y, block.z, dropItem = true)
    }

    // Ray-AABB intersection against the unit cube [bx,bx+1]×[by,by+1]×[bz,bz+1]
    private fun rayHitFace(eye: Vector, dir: Vector, bx: Int, by: Int, bz: Int): BlockFace? {
        fun slabs(o: Double, d: Double, lo: Double, hi: Double): Pair<Double, Double> {
            if (d == 0.0) return if (o in lo..hi)
                Pair(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
            else
                Pair(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
            val t1 = (lo - o) / d; val t2 = (hi - o) / d
            return if (t1 < t2) Pair(t1, t2) else Pair(t2, t1)
        }

        val (txIn, txOut) = slabs(eye.x, dir.x, bx.toDouble(), bx + 1.0)
        val (tyIn, tyOut) = slabs(eye.y, dir.y, by.toDouble(), by + 1.0)
        val (tzIn, tzOut) = slabs(eye.z, dir.z, bz.toDouble(), bz + 1.0)

        val tIn  = maxOf(txIn, tyIn, tzIn)
        val tOut = minOf(txOut, tyOut, tzOut)
        if (tIn > tOut || tOut < 0) return null

        return when (tIn) {
            txIn -> if (dir.x > 0) BlockFace.WEST  else BlockFace.EAST
            tyIn -> if (dir.y > 0) BlockFace.DOWN   else BlockFace.UP
            tzIn -> if (dir.z > 0) BlockFace.NORTH  else BlockFace.SOUTH
            else -> null
        }
    }

    private fun heldGearType(player: Player): GearType {
        val model = player.inventory.itemInMainHand.itemMeta?.itemModel
        return when (model) {
            NamespacedKey("ssggearmachine", "biggear")        -> GearType.LARGE_COGWHEEL
            NamespacedKey("ssggearmachine", "eixo")           -> GearType.AXLE
            NamespacedKey("ssggearmachine", "motor")          -> GearType.MOTOR
            NamespacedKey("ssggearmachine", "water_wheel")     -> GearType.WATER_WHEEL
            else                                               -> GearType.COGWHEEL
        }
    }

    private fun motorParams(gearType: GearType, player: Player): Pair<Boolean, Float> = when (gearType) {
        GearType.MOTOR       -> Pair(true, heldMotorRpm(player))
        GearType.WATER_WHEEL -> Pair(true, GearManager.WATER_WHEEL_RPM)
        else                 -> Pair(false, 0f)
    }

    private fun heldMotorRpm(player: Player): Float =
        player.inventory.itemInMainHand.itemMeta
            ?.persistentDataContainer
            ?.get(rpmKey, PersistentDataType.FLOAT)
            ?: 10f

    private fun faceOffset(face: BlockFace): Triple<Int, Int, Int> = when (face) {
        BlockFace.UP    -> Triple( 0,  1,  0)
        BlockFace.DOWN  -> Triple( 0, -1,  0)
        BlockFace.NORTH -> Triple( 0,  0, -1)
        BlockFace.SOUTH -> Triple( 0,  0,  1)
        BlockFace.EAST  -> Triple( 1,  0,  0)
        BlockFace.WEST  -> Triple(-1,  0,  0)
        else            -> Triple( 0,  0,  0)
    }
}
