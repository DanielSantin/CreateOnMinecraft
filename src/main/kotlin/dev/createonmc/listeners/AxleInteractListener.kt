package dev.createonmc.listeners

import dev.createonmc.axle.AxleAxis
import dev.createonmc.axle.AxlePos
import dev.createonmc.commands.GearStressCommand
import dev.createonmc.commands.SSGItemCommand
import dev.createonmc.gear.GearManager
import dev.createonmc.gear.MillstoneData
import dev.createonmc.gear.GearType
import dev.createonmc.util.AxleUtil
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID

class AxleInteractListener(
    private val gearManager: GearManager,
    private val stressCommand: GearStressCommand,
    private val ssgItemCommand: SSGItemCommand
) : Listener {

    private val rpmKey = NamespacedKey("createonmc", "motor_rpm")
    private val esteiraModel = NamespacedKey("ssggearmachine", "esteira")
    // Guard against Paper firing PlayerInteractEvent for both hands in the same tick
    private val recentPlacements = mutableMapOf<UUID, Long>()
    // Preview displays: barrier pos → ItemDisplay UUID (in-memory only, no persistence needed)
    private val previewByPos = mutableMapOf<AxlePos, UUID>()
    // Belt tool: first axle selection per player
    private val beltSelections = mutableMapOf<UUID, AxlePos>()

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
            // Belt gap: right-click with axle on a barrier that has esteira_fixed but no gear
            if (entry == null && gearManager.hasBeltAt(pos) && heldGearType(player) == GearType.AXLE) {
                val now = System.currentTimeMillis()
                if (now - (recentPlacements[player.uniqueId] ?: 0L) < 100L) return
                recentPlacements[player.uniqueId] = now
                event.isCancelled = true
                gearManager.addAxleToBelt(block.world, pos)
                return
            }

            if (entry != null) {
                val heldGear = heldGearType(player)

                // Holding a gear item → placement mode: inherit axis and place adjacent
                if (heldGear != null) {
                    val now = System.currentTimeMillis()
                    if (now - (recentPlacements[player.uniqueId] ?: 0L) < 100L) return
                    recentPlacements[player.uniqueId] = now
                    event.isCancelled = true
                    val target = block.getRelative(event.blockFace)
                    val (orientQ, axis) = if (heldGear == GearType.MILLSTONE)
                        AxleUtil.orientFromFace(org.bukkit.block.BlockFace.UP)
                    else
                        entry.orientQ to entry.axis
                    val (isMotor, rpm) = motorParams(heldGear, player)
                    spawnLine(player, target.x, target.y, target.z, orientQ, axis, heldGear, isMotor, rpm)
                    return
                }

                // Belt tool: select axle A or B
                if (player.inventory.itemInMainHand.itemMeta?.itemModel == esteiraModel) {
                    event.isCancelled = true
                    handleBeltSelection(player, pos, entry)
                    return
                }

                // Millstone UI: only when not holding a placeable item
                if (entry.gearType == GearType.MILLSTONE) {
                    event.isCancelled = true
                    handleMillstoneInteract(player, pos)
                    return
                }

                // Any other gear: let vanilla handle it (player can place blocks on the barrier face)
            }
        }

        if (player.inventory.itemInMainHand.type != Material.STICK) return

        // Blocos interativos (baú, alavanca, porta…): a ação vanilla vence a
        // colocação, a menos que o jogador esteja agachado — mesma regra do jogo.
        if (!player.isSneaking && isInteractive(block)) return

        // ── Preview item placement ────────────────────────────────────────────
        val previewModelId = player.inventory.itemInMainHand.itemMeta
            ?.persistentDataContainer?.get(ssgItemCommand.previewModelKey, PersistentDataType.STRING)
        if (previewModelId != null) {
            val now = System.currentTimeMillis()
            if (now - (recentPlacements[player.uniqueId] ?: 0L) < 100L) return
            recentPlacements[player.uniqueId] = now
            event.isCancelled = true
            val target = block.getRelative(event.blockFace)
            val pos = AxlePos(player.world.name, target.x, target.y, target.z)
            if (previewByPos.containsKey(pos) || gearManager.getEntry(pos) != null) return
            val loc = Location(player.world, target.x + 0.5, target.y + 0.5, target.z + 0.5)
            val display = player.world.spawn(loc, ItemDisplay::class.java) { e ->
                e.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
                e.interpolationDuration = 0
                e.transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(1f, 1f, 1f), Quaternionf())
            }
            val previewStack = ItemStack(Material.STICK)
            val previewMeta: ItemMeta = previewStack.itemMeta!!
            previewMeta.setItemModel(NamespacedKey("ssggearmachine", previewModelId))
            previewStack.itemMeta = previewMeta
            display.setItemStack(previewStack)
            target.type = Material.BARRIER
            previewByPos[pos] = display.uniqueId
            return
        }

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

    private fun handleBeltSelection(player: Player, pos: AxlePos, entry: dev.createonmc.gear.GearEntry) {
        if (entry.gearType != dev.createonmc.gear.GearType.AXLE) {
            player.sendMessage("§c[Esteira] Selecione um eixo.")
            beltSelections.remove(player.uniqueId)
            return
        }

        val selA = beltSelections[player.uniqueId]
        if (selA == null) {
            beltSelections[player.uniqueId] = pos
            player.sendMessage("§7[Esteira] Eixo A: §f(${pos.bx}, ${pos.by}, ${pos.bz})§7. Clique no Eixo B.")
            return
        }

        if (selA == pos) {
            beltSelections.remove(player.uniqueId)
            player.sendMessage("§7[Esteira] Seleção cancelada.")
            return
        }

        beltSelections.remove(player.uniqueId)
        if (gearManager.attachBelt(selA, pos)) {
            player.sendMessage("§a[Esteira] Conectada!")
        } else {
            player.sendMessage("§c[Esteira] Falhou. Os eixos devem ser paralelos, mesmo eixo rotacional, mesma altura, e a direção da cinta deve ser perpendicular ao eixo.")
        }
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
                    "§7[Millstone] Input: §f${ms.inputCount}x ${ms.inputItem?.name} §7→ §f${recipe.primary.item.name}"
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

    // Hit a barrier block to remove whatever is there (preview, funel, belt, or gear)
    @EventHandler
    fun onHitBarrier(event: PlayerInteractEvent) {
        if (event.action != Action.LEFT_CLICK_BLOCK) return
        if (event.hand != org.bukkit.inventory.EquipmentSlot.HAND) return
        val block = event.clickedBlock ?: return
        if (block.type != Material.BARRIER) return
        event.isCancelled = true

        val pos = AxlePos(block.world.name, block.x, block.y, block.z)

        val previewUuid = previewByPos.remove(pos)
        if (previewUuid != null) {
            block.type = Material.AIR
            block.world.getEntity(previewUuid)?.remove()
            return
        }

        // Funel barrier — remove funel entity and drop item
        val funelUuid = gearManager.findFunelAtBarrier(pos)
        if (funelUuid != null) {
            gearManager.removeFunel(funelUuid)
            return
        }

        // Belt-only barrier (air-gap position with no gear entry) — break the whole belt
        if (gearManager.getEntry(pos) == null && gearManager.hasBeltAt(pos)) {
            gearManager.detachBelt(pos)
            return
        }

        gearManager.removeGear(block.world, block.x, block.y, block.z, dropItem = true)

        // Fallback: if the barrier is still there after removeGear (orphaned — no tracked entry),
        // just remove it so players are never stuck with an unbreakable block.
        if (block.type == Material.BARRIER) block.type = Material.AIR
    }

    /**
     * Blocos cujo clique direito executa uma ação no vanilla (abrir, alternar, usar).
     * Interfaces de BlockData cobrem famílias inteiras (todas as portas, botões…);
     * o set cobre os blocos de estação que não têm interface própria.
     */
    private fun isInteractive(block: org.bukkit.block.Block): Boolean {
        val data = block.blockData
        if (data is org.bukkit.block.data.Openable) return true          // portas, trapdoors, portões
        if (data is org.bukkit.block.data.type.Switch) return true       // alavancas e botões
        if (data is org.bukkit.block.data.type.Bed) return true
        val state = block.state
        if (state is org.bukkit.block.Container) return true             // baús, fornalhas, hoppers…
        if (state is org.bukkit.block.Sign) return true                  // clique direito edita
        return block.type in INTERACTIVE_BLOCKS
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

    companion object {
        private val INTERACTIVE_BLOCKS = setOf(
            Material.CRAFTING_TABLE, Material.ENDER_CHEST, Material.ENCHANTING_TABLE,
            Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL,
            Material.GRINDSTONE, Material.STONECUTTER, Material.SMITHING_TABLE,
            Material.CARTOGRAPHY_TABLE, Material.LOOM, Material.BELL,
            Material.NOTE_BLOCK, Material.JUKEBOX, Material.COMPOSTER,
            Material.REPEATER, Material.COMPARATOR, Material.DAYLIGHT_DETECTOR,
            Material.LECTERN, Material.BEACON, Material.RESPAWN_ANCHOR,
            Material.CRAFTER
        )
    }
}
