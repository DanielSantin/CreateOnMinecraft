package dev.createonmc.listeners

import dev.createonmc.axle.AxleAxis
import dev.createonmc.commands.GearStressCommand
import dev.createonmc.gear.GearManager
import dev.createonmc.gear.MillstoneData
import dev.createonmc.gear.GearType
import dev.createonmc.util.AxleUtil
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.persistence.PersistentDataType

class AxleInteractListener(
    private val gearManager: GearManager,
    private val stressCommand: GearStressCommand
) : Listener {

    private val rpmKey = NamespacedKey("createonmc", "motor_rpm")
    // Guard against Paper firing PlayerInteractEvent for both hands in the same tick
    private val recentPlacements = mutableMapOf<java.util.UUID, Long>()

    @EventHandler
    fun onRightClickBlock(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != org.bukkit.inventory.EquipmentSlot.HAND) return
        val player = event.player
        val block = event.clickedBlock ?: return

        // ── Right-click on a barrier covering a gear ─────────────────────────
        if (block.type == Material.BARRIER && !player.isSneaking) {
            val pos = dev.createonmc.axle.AxlePos(block.world.name, block.x, block.y, block.z)
            val entry = gearManager.getEntry(pos)
            if (entry != null) {
                event.isCancelled = true
                if (entry.gearType == GearType.MILLSTONE) {
                    handleMillstoneInteract(player, pos)
                    return
                }
                // Holding a placement item → place adjacent, inheriting this gear's axis
                if (player.inventory.itemInMainHand.type == Material.STICK) {
                    val gearType = heldGearType(player) ?: return
                    val now = System.currentTimeMillis()
                    if (now - (recentPlacements[player.uniqueId] ?: 0L) < 100L) return
                    recentPlacements[player.uniqueId] = now
                    val target = block.getRelative(event.blockFace)
                    val (orientQ, axis) = if (gearType == GearType.MILLSTONE)
                        AxleUtil.orientFromFace(org.bukkit.block.BlockFace.UP)
                    else
                        entry.orientQ to entry.axis
                    val (isMotor, rpm) = motorParams(gearType, player)
                    spawnLine(player, target.x, target.y, target.z, orientQ, axis, gearType, isMotor, rpm)
                }
                return
            }
        }

        if (player.inventory.itemInMainHand.type != Material.STICK) return

        val gearType = heldGearType(player) ?: return

        // Deduplicate: Paper can fire this event twice per right-click (main + off hand
        // even with the hand check above, on some server versions). Ignore if same player
        // placed within the last 100 ms.
        val now = System.currentTimeMillis()
        if (now - (recentPlacements[player.uniqueId] ?: 0L) < 100L) return
        recentPlacements[player.uniqueId] = now

        event.isCancelled = true

        val target = block.getRelative(event.blockFace)
        // MILLSTONE is always placed flat (Y-axis), regardless of clicked face
        val (orientQ, axis) = if (gearType == GearType.MILLSTONE)
            AxleUtil.orientFromFace(org.bukkit.block.BlockFace.UP)
        else
            AxleUtil.orientFromFace(event.blockFace)
        val (isMotor, rpm) = motorParams(gearType, player)

        spawnLine(player, target.x, target.y, target.z, orientQ, axis, gearType, isMotor, rpm)
    }

    private fun spawnLine(
        player: Player, x: Int, y: Int, z: Int,
        orientQ: org.joml.Quaternionf, axis: AxleAxis,
        gearType: GearType, isMotor: Boolean, rpm: Float
    ) {
        val count = stressCommand.multipliers[player.uniqueId] ?: 1
        val (dx, dy, dz) = when (axis) {
            AxleAxis.X -> Triple(1, 0, 0)
            AxleAxis.Y -> Triple(0, 1, 0)
            AxleAxis.Z -> Triple(0, 0, 1)
        }
        var placed = 0
        for (i in 0 until count) {
            if (gearManager.spawnGear(player.world, x + dx * i, y + dy * i, z + dz * i,
                    orientQ, axis, gearType = gearType, isMotor = isMotor, rpm = rpm)) placed++
        }
        if (count > 1) player.sendMessage("§7[GearStress] Colocadas §f$placed/$count §7gears.")
    }

    private fun handleMillstoneInteract(player: Player, pos: dev.createonmc.axle.AxlePos) {
        val ms = gearManager.millstoneData[pos] ?: return
        val held = player.inventory.itemInMainHand.clone()

        if (held.type != Material.AIR && !held.type.isAir) {
            // Right-click with item → try to add to input
            val original = player.inventory.itemInMainHand
            val added = ms.addInput(original)
            if (added) {
                if (original.amount <= 0) player.inventory.setItemInMainHand(org.bukkit.inventory.ItemStack(Material.AIR))
                val recipe = ms.currentRecipe
                val msg = if (recipe != null)
                    "§7[Millstone] Input: §f${ms.inputCount}x ${ms.inputItem?.name} §7→ §f${recipe.output.name}"
                else "§c[Millstone] No recipe for ${held.type.name}."
                player.sendMessage(msg)
            } else {
                val reason = when {
                    ms.inputItem != null && ms.inputItem != held.type -> "§c[Millstone] Already processing ${ms.inputItem?.name}."
                    ms.inputCount >= MillstoneData.MAX_INPUT           -> "§c[Millstone] Input full."
                    else                                               -> "§c[Millstone] No recipe for ${held.type.name}."
                }
                player.sendMessage(reason)
            }
        } else {
            // Empty hand → take output first, then input
            val taken = ms.takeOutput() ?: ms.takeInput()
            if (taken != null) {
                player.inventory.addItem(taken).values.forEach { overflow ->
                    player.world.dropItemNaturally(player.location, overflow)
                }
                player.sendMessage("§7[Millstone] Took §f${taken.amount}x ${taken.type.name}.")
            } else {
                val inputInfo = if (ms.inputItem != null) "§7Input: §f${ms.inputCount}x ${ms.inputItem?.name}" else "§7Input: §fempty"
                val recipe = ms.currentRecipe
                val progressInfo = if (recipe != null) " §7(${ms.progressTicks}/${recipe.processingTime})" else ""
                player.sendMessage("§7[Millstone] Empty. $inputInfo$progressInfo")
            }
        }
        // Persist updated state to the ItemDisplay entity
        gearManager.saveMillstoneState(pos)
    }

    // Hit a barrier block to remove the gear covering it
    @EventHandler
    fun onHitBarrier(event: PlayerInteractEvent) {
        if (event.action != Action.LEFT_CLICK_BLOCK) return
        if (event.hand != org.bukkit.inventory.EquipmentSlot.HAND) return
        val block = event.clickedBlock ?: return
        if (block.type != Material.BARRIER) return
        event.isCancelled = true
        gearManager.removeGear(block.world, block.x, block.y, block.z, dropItem = true)
    }

    private fun heldGearType(player: Player): GearType? {
        val model = player.inventory.itemInMainHand.itemMeta?.itemModel
        return when (model) {
            NamespacedKey("ssggearmachine", "gear")        -> GearType.COGWHEEL
            NamespacedKey("ssggearmachine", "biggear")     -> GearType.LARGE_COGWHEEL
            NamespacedKey("ssggearmachine", "eixo")        -> GearType.AXLE
            NamespacedKey("ssggearmachine", "motor")       -> GearType.MOTOR
            NamespacedKey("ssggearmachine", "water_wheel") -> GearType.WATER_WHEEL
            NamespacedKey("ssggearmachine", "millstone")   -> GearType.MILLSTONE
            else                                           -> null
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
}
