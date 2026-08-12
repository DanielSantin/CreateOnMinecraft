package dev.createonmc.gear

import dev.createonmc.CreateOnMinecraftPlugin
import dev.createonmc.axle.AxleAxis
import dev.createonmc.axle.AxlePos
import dev.createonmc.nexo.NexoCompat
import dev.createonmc.nexo.NexoIds
import dev.createonmc.util.RotationUtil
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID

/**
 * Owns belt state and all belt transport logic.
 *
 * [beltBlockPos] is a shared map also held by [FunelManager] — mutations here are visible there.
 * [onDropFunelsForBelt] and [onFunelAutoDirection] are callbacks into [FunelManager] to avoid
 * circular construction order.
 * [onGearItem]/[onTagDisplay] delegate display-related helpers that live in [GearManager].
 */
class BeltManager(
    private val plugin: CreateOnMinecraftPlugin,
    private val gearsByPos: MutableMap<AxlePos, GearEntry>,
    private val networkMgr: GearNetworkManager,
    private val beltsByAxle: MutableMap<AxlePos, BeltEntry>,
    val beltBlockPos: MutableMap<AxlePos, Pair<BeltEntry, Int>>,
    private val onDropFunelsForBelt: (BeltEntry) -> Unit,
    private val onGearItem: (GearType) -> ItemStack,
    private val onTagDisplay: (ItemDisplay, GearEntry) -> Unit,
    private val onFunelAutoDirection: (AxlePos, Boolean) -> Unit
) {
    private val beltDebug = false

    // Belt PDC keys (internal so GearManager can use them for chunk-load detection)
    internal val pdcBeltEndB      = NamespacedKey(plugin, "belt_end_b")
    internal val pdcBeltFixedPosA = NamespacedKey(plugin, "belt_fixed_posa")
    internal val pdcBeltItemPosA  = NamespacedKey(plugin, "belt_item_posa")
    private  val pdcBeltItemStack = NamespacedKey(plugin, "belt_item_stack2")
    // Gear position PDC keys (read-only during belt restore — same keys as GearManager's)
    private val pdcBX        = NamespacedKey(plugin, "bx")
    private val pdcBY        = NamespacedKey(plugin, "by")
    private val pdcBZ        = NamespacedKey(plugin, "bz")
    private val pdcWorldName = NamespacedKey(plugin, "world_name")

    companion object {
        private const val BELT_ITEM_Y_OFFSET = 0.375f
        private const val BELT_ITEM_SCALE    = 0.5f
        private const val BELT_SPEED_FACTOR  = 0.02f
        private const val BELT_ITEM_SPACING  = 1.0f
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    fun hasBeltAt(pos: AxlePos): Boolean = beltsByAxle.containsKey(pos)

    fun attachBelt(posA: AxlePos, posB: AxlePos): Boolean {
        val entryA = gearsByPos[posA] ?: return false
        val entryB = gearsByPos[posB] ?: return false
        if (entryA.gearType != GearType.AXLE || entryB.gearType != GearType.AXLE) return false
        if (entryA.axis != entryB.axis) return false
        if (posA.worldName != posB.worldName) return false
        if (beltsByAxle.containsKey(posA) || beltsByAxle.containsKey(posB)) return false

        val (ax, ay, az) = entryA.axis.positiveOffset()
        val deltaX = posB.bx - posA.bx
        val deltaY = posB.by - posA.by
        val deltaZ = posB.bz - posA.bz

        if (deltaY != 0) return false
        if (deltaX * ax + deltaZ * az != 0) return false
        if (deltaX != 0 && deltaZ != 0) return false

        val dist = kotlin.math.abs(deltaX) + kotlin.math.abs(deltaZ)
        if (dist == 0) return false

        val world = plugin.server.getWorld(posA.worldName) ?: return false
        val stepX = if (deltaX != 0) deltaX / kotlin.math.abs(deltaX) else 0
        val stepZ = if (deltaZ != 0) deltaZ / kotlin.math.abs(deltaZ) else 0

        val allPositions  = mutableListOf<AxlePos>()
        val axlePositions = mutableSetOf<AxlePos>()
        for (i in 0..dist) {
            val px  = posA.bx + stepX * i
            val pz  = posA.bz + stepZ * i
            val pos = AxlePos(posA.worldName, px, posA.by, pz)
            allPositions.add(pos)
            val existing = gearsByPos[pos]
            when {
                existing != null -> {
                    if (existing.gearType != GearType.AXLE || existing.axis != entryA.axis) return false
                    if (beltsByAxle.containsKey(pos)) return false
                    axlePositions.add(pos)
                }
                else -> { if (!world.getBlockAt(px, posA.by, pz).type.isAir) return false }
            }
        }

        val beltAngle = when { stepX > 0 -> 90f; stepX < 0 -> -90f; stepZ < 0 -> 180f; else -> 0f }
        val beltOrientQ = RotationUtil.axisAngle(0f, 1f, 0f, beltAngle)
        val spinItem    = beltSpinItem()
        val fixedItem   = beltFixedItem()
        val fixedUuids  = mutableListOf<UUID>()

        for (pos in allPositions) {
            if (pos in axlePositions) {
                val e = gearsByPos[pos] ?: continue
                val display = e.cachedDisplay ?: plugin.server.getEntity(e.displayUuid) as? ItemDisplay ?: continue
                display.setItemStack(spinItem)
            } else {
                world.getBlockAt(pos.bx, pos.by, pos.bz).type = Material.BARRIER
            }
            val loc   = Location(world, pos.bx + 0.5, pos.by + 0.5, pos.bz + 0.5)
            val fixed = world.spawn(loc, ItemDisplay::class.java) { e ->
                e.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
                e.interpolationDuration = 0
                e.transformation = Transformation(Vector3f(), Quaternionf(beltOrientQ), Vector3f(GearManager.SCALE), GearManager.IDENTITY_Q)
            }
            fixed.setItemStack(fixedItem)
            fixedUuids.add(fixed.uniqueId)
        }

        var primaryNet: GearNetwork? = null
        for (pos in axlePositions) {
            val e   = gearsByPos[pos] ?: continue
            val net = networkMgr.networks[e.networkId] ?: continue
            if (primaryNet == null) { primaryNet = net }
            else if (net !== primaryNet) {
                val refMult    = gearsByPos[posA]?.speedMultiplier ?: 1f
                val correction = if (e.speedMultiplier != 0f) refMult / e.speedMultiplier else 1f
                networkMgr.mergeInto(primaryNet!!, net, correction)
            }
        }

        val mergedNetworkId = primaryNet?.id ?: -1
        val belt = BeltEntry(allPositions, axlePositions, fixedUuids, mergedNetworkId)
        for (pos in allPositions) beltsByAxle[pos] = belt

        val eA    = gearsByPos[posA]!!
        val dispA = eA.cachedDisplay?.takeIf { it.isValid }
            ?: plugin.server.getEntity(eA.displayUuid) as? ItemDisplay
        if (dispA != null) {
            dispA.persistentDataContainer.set(
                pdcBeltEndB, PersistentDataType.STRING, "${posB.bx},${posB.by},${posB.bz}")
        } else {
            plugin.logger.warning("[Belt] Could not tag posA entity for persistence at $posA")
        }

        val posATag = "${posA.worldName},${posA.bx},${posA.by},${posA.bz}"
        fixedUuids.forEach { uuid ->
            (plugin.server.getEntity(uuid) as? ItemDisplay)
                ?.persistentDataContainer?.set(pdcBeltFixedPosA, PersistentDataType.STRING, posATag)
        }

        scanBeltInteractors(world, belt)
        return true
    }

    fun detachBelt(pos: AxlePos, clearPersistence: Boolean = true) {
        val belt  = beltsByAxle[pos] ?: return
        clearBeltInteractors(belt)
        val worldName = belt.allPositions.firstOrNull()?.worldName ?: return
        val world     = plugin.server.getWorld(worldName) ?: return

        val posA  = belt.allPositions.first()
        val posB  = belt.allPositions.last()
        val stepX = when { posB.bx > posA.bx -> 1; posB.bx < posA.bx -> -1; else -> 0 }
        val stepZ = when { posB.bz > posA.bz -> 1; posB.bz < posA.bz -> -1; else -> 0 }
        for (beltItem in belt.items) {
            val px = posA.bx + 0.5 + beltItem.beltPos * stepX
            val pz = posA.bz + 0.5 + beltItem.beltPos * stepZ
            (beltItem.cachedDisplay?.takeIf { it.isValid }
                ?: plugin.server.getEntity(beltItem.displayUuid) as? ItemDisplay)?.remove()
            world.dropItemNaturally(Location(world, px, posA.by + 0.875, pz), beltItem.item.clone())
        }
        belt.items.clear()

        if (clearPersistence) {
            gearsByPos[posA]?.let { e ->
                (e.cachedDisplay ?: plugin.server.getEntity(e.displayUuid) as? ItemDisplay)
                    ?.persistentDataContainer?.remove(pdcBeltEndB)
            }
        }

        belt.fixedDisplayUuids.forEach { plugin.server.getEntity(it)?.remove() }

        val axleItem = onGearItem(GearType.AXLE)
        for (p in belt.allPositions) {
            beltsByAxle.remove(p)
            if (p in belt.axlePositions) {
                val e       = gearsByPos[p] ?: continue
                val display = e.cachedDisplay ?: plugin.server.getEntity(e.displayUuid) as? ItemDisplay ?: continue
                display.setItemStack(axleItem)
            } else {
                val block = world.getBlockAt(p.bx, p.by, p.bz)
                if (block.type == Material.BARRIER) block.type = Material.AIR
            }
        }

        if (belt.mergedNetworkId != -1) networkMgr.rebuild(belt.mergedNetworkId)
    }

    fun addAxleToBelt(world: World, pos: AxlePos): Boolean {
        val belt       = beltsByAxle[pos] ?: return false
        if (pos in belt.axlePositions) return false
        val refAxlePos = belt.axlePositions.firstOrNull() ?: return false
        val refEntry   = gearsByPos[refAxlePos] ?: return false

        val loc     = Location(world, pos.bx + 0.5, pos.by + 0.5, pos.bz + 0.5)
        val display = world.spawn(loc, ItemDisplay::class.java) { e ->
            e.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
            e.interpolationDuration = 0
            e.interpolationDelay    = 0
            e.transformation = Transformation(
                Vector3f(), Quaternionf(refEntry.currentDisplayQ), Vector3f(GearManager.SCALE), GearManager.IDENTITY_Q)
        }
        display.setItemStack(beltSpinItem())

        val entry = GearEntry(
            displayUuid    = display.uniqueId,
            pos            = pos,
            axis           = refEntry.axis,
            gearType       = GearType.AXLE,
            orientQ        = Quaternionf(refEntry.orientQ),
            translation    = Vector3f(),
            currentDisplayQ = Quaternionf(refEntry.currentDisplayQ)
        )
        entry.cachedDisplay = display
        gearsByPos[pos] = entry
        onTagDisplay(display, entry)

        val refNet = networkMgr.networks[refEntry.networkId]
        if (refNet != null) networkMgr.assignToNetwork(entry, refNet, refEntry.speedMultiplier)
        belt.axlePositions.add(pos)
        return true
    }

    /** Removes all belts belonging to [worldName] without dropping items or firing callbacks. */
    fun clearForWorld(worldName: String) {
        val world = plugin.server.getWorld(worldName)
        val seen  = mutableSetOf<BeltEntry>()
        for ((pos, belt) in beltsByAxle.toMap()) {
            if (pos.worldName != worldName) continue
            if (!seen.add(belt)) continue
            belt.fixedDisplayUuids.forEach { plugin.server.getEntity(it)?.remove() }
            belt.items.forEach { (it.cachedDisplay ?: plugin.server.getEntity(it.displayUuid))?.remove() }
            world?.let { w ->
                for (p in belt.allPositions) {
                    if (p !in belt.axlePositions) {
                        val block = w.getBlockAt(p.bx, p.by, p.bz)
                        if (block.type == Material.BARRIER) block.type = Material.AIR
                    }
                }
            }
        }
        beltsByAxle.entries.removeIf { it.key.worldName == worldName }
    }

    // ─── Belt interaction points ──────────────────────────────────────────────

    internal fun scanBeltInteractors(world: World, belt: BeltEntry) {
        belt.allPositions.forEach { beltBlockPos.remove(it) }
        belt.interactors.clear()
        for ((index, pos) in belt.allPositions.withIndex()) {
            beltBlockPos[pos] = belt to index
            updateInteractorAt(world, belt, index)
        }
    }

    internal fun updateInteractorAt(world: World, belt: BeltEntry, slotIndex: Int) {
        val pos  = belt.allPositions.getOrNull(slotIndex) ?: return
        val list = mutableListOf<BeltInteractor>()

        val below = world.getBlockAt(pos.bx, pos.by - 1, pos.bz)
        if (below.type == Material.HOPPER)
            list += BeltInteractor.HopperExtract(AxlePos(world.name, pos.bx, pos.by - 1, pos.bz))

        val above = world.getBlockAt(pos.bx, pos.by + 1, pos.bz)
        when {
            above.type == Material.HOPPER -> {
                val data = above.blockData as? org.bukkit.block.data.type.Hopper
                if (data?.facing == org.bukkit.block.BlockFace.DOWN)
                    list += BeltInteractor.HopperInsert(AxlePos(world.name, pos.bx, pos.by + 1, pos.bz))
                else
                    list += BeltInteractor.Obstacle
            }
            !above.type.isAir && above.type != Material.BARRIER ->
                list += BeltInteractor.Obstacle
        }

        for ((dx, dz, face) in listOf(
            Triple( 1,  0, org.bukkit.block.BlockFace.WEST),
            Triple(-1,  0, org.bukkit.block.BlockFace.EAST),
            Triple( 0,  1, org.bukkit.block.BlockFace.NORTH),
            Triple( 0, -1, org.bukkit.block.BlockFace.SOUTH)
        )) {
            val side = world.getBlockAt(pos.bx + dx, pos.by, pos.bz + dz)
            if (side.type == Material.HOPPER) {
                val data = side.blockData as? org.bukkit.block.data.type.Hopper
                if (data?.facing == face)
                    list += BeltInteractor.HopperInsert(AxlePos(world.name, pos.bx + dx, pos.by, pos.bz + dz))
            }
        }

        belt.interactors[slotIndex]?.filterTo(list) {
            it is BeltInteractor.FunelOut || it is BeltInteractor.FunelIn || it is BeltInteractor.FunelAuto
        }

        if (list.isEmpty()) belt.interactors.remove(slotIndex)
        else belt.interactors[slotIndex] = list
    }

    // ─── Belt item transport ──────────────────────────────────────────────────

    fun tickBelts(tickCount: Int) {
        val seen = mutableSetOf<BeltEntry>()
        for (belt in beltsByAxle.values) {
            if (!seen.add(belt)) continue
            // Uma esteira é física/contígua, então fica dentro de uma única região na
            // prática — despachada pra região dona da sua primeira posição.
            val posA = belt.allPositions.firstOrNull() ?: continue
            val world = plugin.server.getWorld(posA.worldName) ?: continue
            val loc = Location(world, posA.bx.toDouble(), posA.by.toDouble(), posA.bz.toDouble())
            Bukkit.getRegionScheduler().run(plugin, loc) { tickBelt(belt, tickCount) }
        }
    }

    private fun tickBelt(belt: BeltEntry, tickCount: Int) {
        val posA  = belt.allPositions.firstOrNull() ?: return
        val posB  = belt.allPositions.lastOrNull()  ?: return
        val dist  = belt.allPositions.size - 1
        if (dist == 0) return
        val world = plugin.server.getWorld(posA.worldName) ?: return

        val stepX = when { posB.bx > posA.bx -> 1; posB.bx < posA.bx -> -1; else -> 0 }
        val stepZ = when { posB.bz > posA.bz -> 1; posB.bz < posA.bz -> -1; else -> 0 }

        val refEntry = gearsByPos[belt.axlePositions.firstOrNull() ?: return] ?: return
        val baseDpt  = networkMgr.networks[refEntry.networkId]?.lastBaseDpt ?: 0f
        val beltTag  = "belt[${posA.bx},${posA.by},${posA.bz}→${posB.bx},${posB.bz}]"

        val dirSign = when (refEntry.axis) {
            AxleAxis.X -> stepZ.toFloat()
            AxleAxis.Z -> -stepX.toFloat()
            AxleAxis.Y -> (stepX - stepZ).toFloat()
        }
        val signedSpeed = baseDpt * refEntry.speedMultiplier * dirSign * BELT_SPEED_FACTOR
        val forward     = signedSpeed > 0f

        // Funel display icons only need to change when direction actually flips (motor reversal,
        // water wheel flow change, belt rebuild, ...) — comparing against the cached value is free,
        // so this replaces re-scanning + re-setting every funel's texture on a fixed tick cadence.
        if (forward != belt.lastForward) {
            belt.lastForward = forward
            syncFunelAutoDisplays(belt, stepX, stepZ, forward)
        }

        if (tickCount % 4 == 0) pickupItemEntities(world, belt, posA, stepX, stepZ)
        if (tickCount % 8 == 0) tickBeltInteractorsInsert(world, belt, posA, stepX, stepZ, forward)

        if (signedSpeed == 0f || belt.items.isEmpty()) return

        val speed = kotlin.math.abs(signedSpeed)
        if (beltDebug && belt.items.isNotEmpty())
            plugin.logger.info("[BeltDBG] $beltTag tick=$tickCount items=${belt.items.size} speed=${"%.4f".format(speed)} forward=$forward")

        val endPos   = if (forward) posB  else posA
        val endBeltP = if (forward) dist.toFloat() else 0f
        val exitX    = if (forward) stepX else -stepX
        val exitZ    = if (forward) stepZ else -stepZ

        if (forward) belt.items.sortByDescending { it.beltPos } else belt.items.sortBy { it.beltPos }
        val toRemove = mutableListOf<BeltItem>()

        val exitCandidates = listOf(
            AxlePos(posA.worldName, endPos.bx + exitX,  endPos.by, endPos.bz + exitZ),
            AxlePos(posA.worldName, endPos.bx - exitZ,  endPos.by, endPos.bz + exitX),
            AxlePos(posA.worldName, endPos.bx + exitZ,  endPos.by, endPos.bz - exitX),
        )

        for (i in belt.items.indices) {
            val item   = belt.items[i]
            if (item in toRemove) continue
            val itemId = item.displayUuid.toString().takeLast(6)

            val pastEnd = if (forward) item.beltPos >= endBeltP else item.beltPos <= endBeltP
            if (pastEnd) {
                if (beltDebug) plugin.logger.info("[BeltDBG]   item[$itemId] PAST_END beltPos=${"%.3f".format(item.beltPos)} endBeltP=$endBeltP")

                var nextBelt: BeltEntry? = null
                var nextEntryIdx = 0
                for (candidate in exitCandidates) {
                    val nb  = beltsByAxle[candidate] ?: continue
                    if (nb === belt) continue
                    val idx = nb.allPositions.indexOf(candidate)
                    if (idx >= 0) { nextBelt = nb; nextEntryIdx = idx; break }
                }
                if (beltDebug) plugin.logger.info("[BeltDBG]   item[$itemId] nextBelt=${nextBelt != null} candidates=$exitCandidates")

                val gapLimit   = if (forward) endBeltP + 1f else endBeltP - 1f
                val crossedGap = if (forward) item.beltPos >= gapLimit else item.beltPos <= gapLimit

                if (nextBelt != null && crossedGap) {
                    val entryPos  = nextEntryIdx.toFloat()
                    val overshoot = if (forward) item.beltPos - gapLimit else gapLimit - item.beltPos
                    val newBeltPos = entryPos + overshoot
                    val blocked   = nextBelt.items.any { kotlin.math.abs(it.beltPos - entryPos) < BELT_ITEM_SPACING }
                    if (beltDebug) plugin.logger.info("[BeltDBG]   item[$itemId] TRANSFER→nextBelt newBeltPos=${"%.3f".format(newBeltPos)} blocked=$blocked")
                    if (!blocked) {
                        val nA  = nextBelt.allPositions.first()
                        val nB2 = nextBelt.allPositions.last()
                        val nSX = when { nB2.bx > nA.bx -> 1; nB2.bx < nA.bx -> -1; else -> 0 }
                        val nSZ = when { nB2.bz > nA.bz -> 1; nB2.bz < nA.bz -> -1; else -> 0 }
                        val disp = item.cachedDisplay?.takeIf { it.isValid }
                            ?: plugin.server.getEntity(item.displayUuid) as? ItemDisplay
                        if (disp != null) {
                            disp.persistentDataContainer.set(pdcBeltItemPosA, PersistentDataType.STRING,
                                "${nA.worldName},${nA.bx},${nA.by},${nA.bz}")
                            item.beltPos      = newBeltPos
                            item.cachedDisplay = disp
                            toRemove.add(item)
                            nextBelt.items.add(item)
                            updateBeltItemDisplay(item, nA, nSX, nSZ)
                        } else {
                            if (beltDebug) plugin.logger.warning("[BeltDBG]   item[$itemId] display entity MISSING at transfer, respawning")
                            toRemove.add(item)
                            nextBelt.items.add(spawnBeltItem(world, nA, nSX, nSZ, item.item.clone(), newBeltPos))
                        }
                    } else {
                        if (beltDebug) plugin.logger.info("[BeltDBG]   item[$itemId] HOLD at gapLimit=${"%.3f".format(gapLimit)} (dest blocked)")
                        item.beltPos = gapLimit
                    }
                } else if (nextBelt != null) {
                    val entryPos    = nextEntryIdx.toFloat()
                    val destBlocked = nextBelt.items.any { kotlin.math.abs(it.beltPos - entryPos) < BELT_ITEM_SPACING }
                    val advanceGap  = if (forward) minOf(speed, gapLimit - item.beltPos).coerceAtLeast(0f)
                                      else         minOf(speed, item.beltPos - gapLimit).coerceAtLeast(0f)
                    if (beltDebug) plugin.logger.info("[BeltDBG]   item[$itemId] GAP_CROSS beltPos=${"%.3f".format(item.beltPos)} gapLimit=${"%.3f".format(gapLimit)} destBlocked=$destBlocked advanceGap=${"%.4f".format(advanceGap)}")
                    if (!destBlocked && advanceGap > 0f) {
                        item.beltPos = if (forward) item.beltPos + advanceGap else item.beltPos - advanceGap
                        updateBeltItemDisplay(item, posA, stepX, stepZ)
                    }
                } else {
                    val lastSlot = endBeltP.toInt().coerceIn(0, belt.allPositions.size - 1)
                    if (tryExtractBeltItem(world, belt, item, lastSlot, toRemove, forward, stepX, stepZ)) continue

                    val endBlock = world.getBlockAt(endPos.bx + exitX, endPos.by, endPos.bz + exitZ)
                    if (beltDebug) plugin.logger.info("[BeltDBG]   item[$itemId] END solid=${endBlock.type.isSolid} block=${endBlock.type}")
                    if (!endBlock.type.isSolid) {
                        (item.cachedDisplay?.takeIf { it.isValid }
                            ?: plugin.server.getEntity(item.displayUuid) as? ItemDisplay)?.remove()
                        world.dropItemNaturally(
                            Location(world, endPos.bx + exitX + 0.5, endPos.by + 0.875, endPos.bz + exitZ + 0.5),
                            item.item.clone()
                        )
                        if (beltDebug) plugin.logger.info("[BeltDBG]   item[$itemId] DROPPED at (${endPos.bx+exitX},${endPos.by},${endPos.bz+exitZ})")
                        toRemove.add(item)
                    }
                }
                continue
            }

            val currentSlot = item.beltPos.toInt().coerceIn(0, belt.allPositions.size - 1)
            if (tryExtractBeltItem(world, belt, item, currentSlot, toRemove, forward, stepX, stepZ)) continue

            val frontItem = if (i > 0 && belt.items[i - 1] !in toRemove) belt.items[i - 1] else null
            val advance = if (frontItem != null) {
                val gap = if (forward) frontItem.beltPos - item.beltPos else item.beltPos - frontItem.beltPos
                val adv = if (gap <= BELT_ITEM_SPACING) 0f else minOf(speed, gap - BELT_ITEM_SPACING)
                if (adv == 0f && beltDebug) plugin.logger.info("[BeltDBG]   item[$itemId] BLOCKED_BY_FRONT gap=${"%.3f".format(gap)} spacing=$BELT_ITEM_SPACING front=${frontItem.displayUuid.toString().takeLast(6)}")
                adv
            } else {
                val wouldPassEnd = if (forward) item.beltPos + speed >= endBeltP else item.beltPos - speed <= endBeltP
                if (wouldPassEnd) {
                    val hasNextBelt = exitCandidates.any { c -> beltsByAxle[c]?.let { it !== belt } == true }
                    if (hasNextBelt) speed
                    else {
                        val endBlock = world.getBlockAt(endPos.bx + exitX, endPos.by, endPos.bz + exitZ)
                        if (endBlock.type.isSolid) {
                            val remaining = if (forward) endBeltP - item.beltPos else item.beltPos - endBeltP
                            if (remaining <= 0f && beltDebug) plugin.logger.info("[BeltDBG]   item[$itemId] AT_WALL beltPos=${"%.3f".format(item.beltPos)}")
                            remaining.coerceAtLeast(0f)
                        } else speed
                    }
                } else speed
            }

            if (beltDebug) plugin.logger.info("[BeltDBG]   item[$itemId] MOVE beltPos=${"%.3f".format(item.beltPos)} advance=${"%.4f".format(advance)} frontItem=${frontItem != null}")

            val blockedByObstacle = advance > 0f && run {
                val targetSlot = if (forward) (item.beltPos + advance).toInt() else (item.beltPos - advance).toInt()
                targetSlot != item.beltPos.toInt() &&
                    belt.interactors[targetSlot]?.any { it is BeltInteractor.Obstacle } == true
            }

            if (advance > 0f && !blockedByObstacle) {
                val newPos = if (forward) item.beltPos + advance else item.beltPos - advance
                item.beltPos = if (forward) newPos.coerceAtMost(endBeltP) else newPos.coerceAtLeast(endBeltP)
                updateBeltItemDisplay(item, posA, stepX, stepZ)
            }
        }
        belt.items.removeAll(toRemove.toSet())
    }

    private fun pickupItemEntities(world: World, belt: BeltEntry, posA: AxlePos, stepX: Int, stepZ: Int) {
        val beltTag = "belt[${posA.bx},${posA.by},${posA.bz}]"
        for ((index, pos) in belt.allPositions.withIndex()) {
            val slotPos  = index.toFloat()
            val occupied = belt.items.any { kotlin.math.abs(it.beltPos - slotPos) < 0.5f }
            val allNearby = world.getNearbyEntities(
                Location(world, pos.bx + 0.5, pos.by + 1.25, pos.bz + 0.5), 0.45, 0.45, 0.45
            ).filterIsInstance<org.bukkit.entity.Item>()
            val nearby = allNearby.filter { !it.itemStack.type.isAir }
            if (beltDebug && allNearby.isNotEmpty())
                plugin.logger.info("[BeltDBG] $beltTag pickup scan slot=$index slotPos=$slotPos occupied=$occupied allFound=${allNearby.size} nonAir=${nearby.size}")
            if (occupied || nearby.isEmpty()) continue
            val itemEntity = nearby.first()
            if (beltDebug) plugin.logger.info("[BeltDBG] $beltTag PICKUP entity=${itemEntity.uniqueId.toString().takeLast(6)} stack=${itemEntity.itemStack.type} pos=(${itemEntity.location.x.toInt()},${itemEntity.location.y.toInt()},${itemEntity.location.z.toInt()})")
            val itemStack = itemEntity.itemStack.clone()
            itemEntity.itemStack = ItemStack(Material.AIR)
            itemEntity.remove()
            val spawned = spawnBeltItem(world, posA, stepX, stepZ, itemStack, slotPos)
            if (beltDebug) plugin.logger.info("[BeltDBG] $beltTag spawned BeltItem=${spawned.displayUuid.toString().takeLast(6)} beltPos=$slotPos")
            belt.items.add(spawned)
        }
    }

    /**
     * Pushes the current direction to every ALIGNED funel on [belt]. Only called by [tickBelt] when
     * `forward` actually changed since the last check (see [BeltEntry.lastForward]) — not on a fixed
     * cadence — so this only runs on real events: a motor/water-wheel reversal, the belt stopping or
     * starting, a belt rebuild changing its step direction, or a funel just being (re)placed.
     */
    private fun syncFunelAutoDisplays(belt: BeltEntry, stepX: Int, stepZ: Int, forward: Boolean) {
        for ((_, interactors) in belt.interactors) {
            for (interactor in interactors) {
                if (interactor !is BeltInteractor.FunelAuto) continue
                val travelingToward = (forward && interactor.alignedTowardX == stepX && interactor.alignedTowardZ == stepZ) ||
                    (!forward && interactor.alignedTowardX == -stepX && interactor.alignedTowardZ == -stepZ)
                onFunelAutoDirection(interactor.containerPos, travelingToward)
            }
        }
    }

    private fun tickBeltInteractorsInsert(
        world: World, belt: BeltEntry, posA: AxlePos, stepX: Int, stepZ: Int, forward: Boolean
    ) {
        for ((slotIndex, interactors) in belt.interactors) {
            val slotPos = slotIndex.toFloat()
            if (belt.items.any { kotlin.math.abs(it.beltPos - slotPos) < 0.5f }) continue
            for (interactor in interactors) {
                val inv = when (interactor) {
                    is BeltInteractor.HopperInsert -> {
                        val block = world.getBlockAt(interactor.hopperPos.bx, interactor.hopperPos.by, interactor.hopperPos.bz)
                        if (block.type != Material.HOPPER) continue
                        (block.state as? org.bukkit.block.Hopper)?.inventory
                    }
                    is BeltInteractor.FunelOut -> {
                        val cPos = interactor.containerPos
                        (world.getBlockAt(cPos.bx, cPos.by, cPos.bz).state as? org.bukkit.block.Container)?.inventory
                    }
                    is BeltInteractor.FunelAuto -> {
                        val travelingToward = (forward && interactor.alignedTowardX == stepX && interactor.alignedTowardZ == stepZ) ||
                            (!forward && interactor.alignedTowardX == -stepX && interactor.alignedTowardZ == -stepZ)
                        if (travelingToward) continue
                        val cPos = interactor.containerPos
                        (world.getBlockAt(cPos.bx, cPos.by, cPos.bz).state as? org.bukkit.block.Container)?.inventory
                    }
                    else -> continue
                } ?: continue
                for (i in 0 until inv.size) {
                    val stack = inv.getItem(i) ?: continue
                    if (stack.type.isAir) continue
                    val transfer = stack.clone().also { it.amount = 1 }
                    stack.amount -= 1
                    inv.setItem(i, if (stack.amount <= 0) null else stack)
                    belt.items.add(spawnBeltItem(world, posA, stepX, stepZ, transfer, slotPos))
                    break
                }
                break
            }
        }
    }

    private fun tryExtractBeltItem(
        world: World, belt: BeltEntry, item: BeltItem,
        slotIndex: Int, toRemove: MutableList<BeltItem>,
        forward: Boolean, stepX: Int, stepZ: Int
    ): Boolean {
        val interactors = belt.interactors[slotIndex] ?: return false
        for (interactor in interactors) {
            val inv = when (interactor) {
                is BeltInteractor.HopperExtract -> {
                    val block = world.getBlockAt(interactor.hopperPos.bx, interactor.hopperPos.by, interactor.hopperPos.bz)
                    if (block.type != Material.HOPPER) continue
                    (block.state as? org.bukkit.block.Hopper)?.inventory
                }
                is BeltInteractor.FunelIn -> {
                    val cPos = interactor.containerPos
                    (world.getBlockAt(cPos.bx, cPos.by, cPos.bz).state as? org.bukkit.block.Container)?.inventory
                }
                is BeltInteractor.FunelAuto -> {
                    val travelingToward = (forward && interactor.alignedTowardX == stepX && interactor.alignedTowardZ == stepZ) ||
                        (!forward && interactor.alignedTowardX == -stepX && interactor.alignedTowardZ == -stepZ)
                    if (!travelingToward) continue
                    val cPos = interactor.containerPos
                    (world.getBlockAt(cPos.bx, cPos.by, cPos.bz).state as? org.bukkit.block.Container)?.inventory
                }
                else -> continue
            } ?: continue
            val overflow = inv.addItem(item.item.clone())
            if (overflow.isEmpty()) {
                (item.cachedDisplay?.takeIf { it.isValid }
                    ?: plugin.server.getEntity(item.displayUuid) as? ItemDisplay)?.remove()
                toRemove.add(item)
                return true
            }
        }
        return false
    }

    private fun spawnBeltItem(
        world: World, posA: AxlePos, stepX: Int, stepZ: Int,
        item: ItemStack, beltPos: Float
    ): BeltItem {
        val loc     = Location(world, posA.bx + 0.5, posA.by + 0.5, posA.bz + 0.5)
        val display = world.spawn(loc, ItemDisplay::class.java) { e ->
            e.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
            e.interpolationDuration = 0
            e.interpolationDelay    = 0
            e.transformation = Transformation(
                Vector3f(beltPos * stepX, BELT_ITEM_Y_OFFSET, beltPos * stepZ),
                beltItemRotation(stepX, stepZ),
                Vector3f(BELT_ITEM_SCALE, BELT_ITEM_SCALE, BELT_ITEM_SCALE), GearManager.IDENTITY_Q
            )
        }
        display.setItemStack(item)
        val posATag = "${posA.worldName},${posA.bx},${posA.by},${posA.bz}"
        display.persistentDataContainer.set(pdcBeltItemPosA,  PersistentDataType.STRING,    posATag)
        display.persistentDataContainer.set(pdcBeltItemStack, PersistentDataType.BYTE_ARRAY, item.serializeAsBytes())
        return BeltItem(display.uniqueId, item.clone(), beltPos, posA.bx, posA.bz).also { it.cachedDisplay = display }
    }

    private fun updateBeltItemDisplay(item: BeltItem, posA: AxlePos, stepX: Int, stepZ: Int) {
        val display = item.cachedDisplay?.takeIf { it.isValid }
            ?: (plugin.server.getEntity(item.displayUuid) as? ItemDisplay)?.also { item.cachedDisplay = it }
            ?: return
        display.interpolationDuration = 2
        display.interpolationDelay    = 0
        display.transformation = Transformation(
            Vector3f(
                (posA.bx - item.bxAnchor) + item.beltPos * stepX,
                BELT_ITEM_Y_OFFSET,
                (posA.bz - item.bzAnchor) + item.beltPos * stepZ
            ),
            beltItemRotation(stepX, stepZ),
            Vector3f(BELT_ITEM_SCALE, BELT_ITEM_SCALE, BELT_ITEM_SCALE), GearManager.IDENTITY_Q
        )
    }

    // Fixed rotation: item always lies flat regardless of belt direction.
    private fun beltItemRotation(stepX: Int, stepZ: Int): Quaternionf =
        RotationUtil.axisAngle(1f, 0f, 0f, -90f)

    private fun clearBeltInteractors(belt: BeltEntry) {
        onDropFunelsForBelt(belt)
        belt.allPositions.forEach { beltBlockPos.remove(it) }
        belt.interactors.clear()
    }

    // ─── Persistence ─────────────────────────────────────────────────────────

    fun restoreBeltsFromWorld() {
        data class ItemInfo(val display: ItemDisplay, val stack: ItemStack)

        fun restoreBeltItems(belt: BeltEntry, posA: AxlePos, world: World, infos: List<ItemInfo>, alreadyTracked: MutableSet<UUID>) {
            val beltPosB = belt.allPositions.last()
            val stepX = when { beltPosB.bx > posA.bx -> 1; beltPosB.bx < posA.bx -> -1; else -> 0 }
            val stepZ = when { beltPosB.bz > posA.bz -> 1; beltPosB.bz < posA.bz -> -1; else -> 0 }
            val dist  = (belt.allPositions.size - 1).toFloat()
            for ((disp, item) in infos) {
                if (disp.uniqueId in alreadyTracked) {
                    plugin.logger.info("[Belt] Skipping restore of item ${disp.uniqueId.toString().takeLast(6)} — already tracked in belt")
                    continue
                }
                val t       = disp.transformation.translation
                val bxAnchor = (disp.x - 0.5).toInt()
                val bzAnchor = (disp.z - 0.5).toInt()
                val offsetX  = posA.bx - bxAnchor
                val offsetZ  = posA.bz - bzAnchor
                val beltPos  = ((t.x - offsetX) * stepX + (t.z - offsetZ) * stepZ).coerceIn(0f, dist)
                disp.teleportDuration  = 0
                disp.teleport(Location(world, posA.bx + 0.5, posA.by + 0.5, posA.bz + 0.5))
                disp.interpolationDuration = 0
                disp.interpolationDelay    = 0
                disp.transformation = Transformation(
                    Vector3f(beltPos * stepX, BELT_ITEM_Y_OFFSET, beltPos * stepZ),
                    beltItemRotation(stepX, stepZ),
                    Vector3f(BELT_ITEM_SCALE, BELT_ITEM_SCALE, BELT_ITEM_SCALE), GearManager.IDENTITY_Q
                )
                val beltItem = BeltItem(disp.uniqueId, item, beltPos, posA.bx, posA.bz)
                beltItem.cachedDisplay = disp
                belt.items.add(beltItem)
                alreadyTracked.add(disp.uniqueId)
            }
        }

        val posADisplays = mutableMapOf<AxlePos, ItemDisplay>()
        val fixedByPosA  = mutableMapOf<AxlePos, MutableList<UUID>>()
        val itemsByPosA  = mutableMapOf<AxlePos, MutableList<ItemInfo>>()

        for (world in plugin.server.worlds) {
            for (entity in world.entities) {
                if (entity !is ItemDisplay) continue
                val pdc = entity.persistentDataContainer

                if (pdc.has(pdcBeltEndB, PersistentDataType.STRING)) {
                    val bx = pdc.get(pdcBX,        PersistentDataType.INTEGER) ?: continue
                    val by = pdc.get(pdcBY,        PersistentDataType.INTEGER) ?: continue
                    val bz = pdc.get(pdcBZ,        PersistentDataType.INTEGER) ?: continue
                    val wn = pdc.get(pdcWorldName, PersistentDataType.STRING)  ?: continue
                    posADisplays[AxlePos(wn, bx, by, bz)] = entity
                    continue
                }

                val fixedTag = pdc.get(pdcBeltFixedPosA, PersistentDataType.STRING)
                if (fixedTag != null) {
                    AxlePos.parse(fixedTag)?.let { posA ->
                        fixedByPosA.getOrPut(posA) { mutableListOf() }.add(entity.uniqueId)
                    }
                    continue
                }

                val itemPosATag = pdc.get(pdcBeltItemPosA,  PersistentDataType.STRING)     ?: continue
                val stackBytes  = pdc.get(pdcBeltItemStack, PersistentDataType.BYTE_ARRAY) ?: continue
                val posA        = AxlePos.parse(itemPosATag) ?: continue
                val item = runCatching { ItemStack.deserializeBytes(stackBytes) }.getOrNull() ?: continue
                itemsByPosA.getOrPut(posA) { mutableListOf() }.add(ItemInfo(entity, item))
            }
        }

        plugin.logger.info("[Belt] Restore scan: ${posADisplays.size} belt posA(s), " +
            "${fixedByPosA.size} fixed group(s), ${itemsByPosA.size} item group(s)")

        var beltCount = 0
        for ((posA, dispA) in posADisplays) {
            if (beltsByAxle.containsKey(posA)) continue
            val endBStr = dispA.persistentDataContainer.get(pdcBeltEndB, PersistentDataType.STRING) ?: continue
            val parts   = endBStr.split(",")
            if (parts.size < 3) continue
            runCatching {
                val posB       = AxlePos(posA.worldName, parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
                val fixedUuids = fixedByPosA[posA] ?: mutableListOf()
                val ok         = restoreBelt(posA, posB, fixedUuids)
                if (ok) beltCount++
                else plugin.logger.warning("[Belt] restoreBelt failed: posA=$posA posB=$posB " +
                    "entryA=${gearsByPos[posA]?.gearType} entryB=${gearsByPos[posB]?.gearType}")
            }.onFailure { e ->
                plugin.logger.warning("[Belt] Exception restoring belt at $posA: ${e.message}")
            }
        }

        val alreadyTracked = beltsByAxle.values.toSet().flatMapTo(mutableSetOf()) { b -> b.items.map { it.displayUuid } }

        for ((posA, infos) in itemsByPosA) {
            val belt  = beltsByAxle[posA] ?: continue
            val world = plugin.server.getWorld(posA.worldName) ?: continue
            // Reposiciona os displays de item da esteira: despachado pra região dona de posA.
            val loc = Location(world, posA.bx.toDouble(), posA.by.toDouble(), posA.bz.toDouble())
            Bukkit.getRegionScheduler().run(plugin, loc) {
                restoreBeltItems(belt, posA, world, infos, alreadyTracked)
            }
        }

        if (beltCount > 0)
            plugin.logger.info("Restored $beltCount belt(s) from loaded chunks.")
    }

    private fun restoreBelt(posA: AxlePos, posB: AxlePos, fixedUuids: List<UUID>): Boolean {
        val entryA = gearsByPos[posA] ?: return false
        val entryB = gearsByPos[posB] ?: return false
        if (entryA.gearType != GearType.AXLE || entryB.gearType != GearType.AXLE) return false

        val deltaX = posB.bx - posA.bx; val deltaZ = posB.bz - posA.bz
        if (deltaX != 0 && deltaZ != 0) return false
        val dist  = kotlin.math.abs(deltaX) + kotlin.math.abs(deltaZ)
        val stepX = if (deltaX != 0) deltaX / kotlin.math.abs(deltaX) else 0
        val stepZ = if (deltaZ != 0) deltaZ / kotlin.math.abs(deltaZ) else 0

        val allPositions  = mutableListOf<AxlePos>()
        val axlePositions = mutableSetOf<AxlePos>()
        for (i in 0..dist) {
            val pos = AxlePos(posA.worldName, posA.bx + stepX * i, posA.by, posA.bz + stepZ * i)
            allPositions.add(pos)
            if (gearsByPos.containsKey(pos)) axlePositions.add(pos)
        }

        val spinItem = beltSpinItem()
        for (pos in axlePositions) {
            val e = gearsByPos[pos] ?: continue
            (e.cachedDisplay ?: plugin.server.getEntity(e.displayUuid) as? ItemDisplay)
                ?.setItemStack(spinItem)
        }

        var primaryNet: GearNetwork? = null
        for (pos in axlePositions) {
            val e   = gearsByPos[pos] ?: continue
            val net = networkMgr.networks[e.networkId] ?: continue
            if (primaryNet == null) { primaryNet = net }
            else if (net !== primaryNet) {
                val refMult    = gearsByPos[posA]?.speedMultiplier ?: 1f
                val correction = if (e.speedMultiplier != 0f) refMult / e.speedMultiplier else 1f
                networkMgr.mergeInto(primaryNet!!, net, correction)
            }
        }

        val belt = BeltEntry(allPositions, axlePositions, fixedUuids.toMutableList(), primaryNet?.id ?: -1)
        for (pos in allPositions) beltsByAxle[pos] = belt
        plugin.server.getWorld(posA.worldName)?.let { scanBeltInteractors(it, belt) }
        return true
    }

    // ─── Display item helpers ─────────────────────────────────────────────────

    fun beltSpinItem(): ItemStack = NexoCompat.item(NexoIds.ESTEIRA_SPIN)

    fun beltFixedItem(): ItemStack = NexoCompat.item(NexoIds.ESTEIRA_FIXED)
}
