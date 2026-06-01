package dev.createonmc.gear

import dev.createonmc.CreateOnMinecraftPlugin
import dev.createonmc.axle.AxleAxis
import dev.createonmc.axle.AxlePos
import dev.createonmc.util.RotationUtil
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID

class GearManager(private val plugin: CreateOnMinecraftPlugin) {

    companion object {
        val PIVOT = Vector3f(0.5f, 0.5f, 0.5f)
        val SCALE = Vector3f(1f, 1f, 1f)
        val IDENTITY_Q = Quaternionf(0f, 0f, 0f, 1f)
        private const val MAX_STEP_TICKS = 4
        private const val MAX_STEP_ANGLE = 90f
        const val WATER_WHEEL_RPM = 16f

        // ── Stress values (SU per RPM), matching Create mod ──────────────────
        const val STRESS_CAPACITY_WATER_WHEEL = 16f   // SU generated per RPM
        const val STRESS_CAPACITY_MOTOR       = 256f  // SU generated per RPM (creative-style)
        const val STRESS_IMPACT_MILLSTONE     = 4f    // SU consumed per RPM
        private const val TICKS_PER_MINUTE    = 20f * 60f
        private const val DPT_TO_RPM          = TICKS_PER_MINUTE / 360f  // multiply dpt → rpm

        // ── Belt item transport ───────────────────────────────────────────────
        private const val BELT_ITEM_Y_OFFSET  = 0.375f  // entity at by+0.5 → item center at by+0.875 (14/16)
        private const val BELT_ITEM_SCALE     = 0.5f
        private const val BELT_SPEED_FACTOR   = 0.02f   // baseDpt × factor = blocks/tick (~1 blk/s at 10 RPM)
        private const val BELT_ITEM_SPACING   = 1.0f    // minimum blocks between item centres
        private const val BELT_INTERP_TICKS   = 2       // interpolation ticks for normal belt movement
    }

    // Barrier block offsets relative to gear origin — all types get 1 barrier at (0,0,0).
    // Future multi-block shapes override specific types with additional offsets.
    private val colliderOffsets: Map<GearType, List<Triple<Int,Int,Int>>> =
        GearType.values().associateWith { listOf(Triple(0, 0, 0)) }

    private val gearsByPos = mutableMapOf<AxlePos, GearEntry>()
    val networks = mutableMapOf<Int, GearNetwork>()
    val millstoneData = mutableMapOf<AxlePos, MillstoneData>()
    private val beltsByAxle = mutableMapOf<AxlePos, BeltEntry>()
    private var nextNetworkId = 0
    private var tickCount = 0
    // Belt restoration is debounced: fires 20 ticks after the last chunk load so all
    // adjacent chunks (and their gear entities) are ready before we try to re-attach.
    private var pendingBeltRestoreTaskId = -1

    // ─── PDC keys (stored on the ItemDisplay entity for persistence) ────────
    private val pdcGearType    = NamespacedKey(plugin, "gear_type")
    private val pdcAxis        = NamespacedKey(plugin, "axis")
    private val pdcOrientQ     = NamespacedKey(plugin, "orient_q")     // "x,y,z,w"
    private val pdcIsMotor     = NamespacedKey(plugin, "is_motor")
    private val pdcMotorSpeed  = NamespacedKey(plugin, "motor_speed")
    private val pdcExtraUuids  = NamespacedKey(plugin, "extra_uuids")  // comma-separated
    private val pdcBX          = NamespacedKey(plugin, "bx")
    private val pdcBY          = NamespacedKey(plugin, "by")
    private val pdcBZ          = NamespacedKey(plugin, "bz")
    private val pdcWorldName   = NamespacedKey(plugin, "world_name")
    // Belt persistence PDC keys
    private val pdcBeltEndB      = NamespacedKey(plugin, "belt_end_b")       // on posA display: "bx,by,bz" of posB
    private val pdcBeltFixedPosA = NamespacedKey(plugin, "belt_fixed_posa")  // on esteira_fixed: "wN,bx,by,bz" of posA
    private val pdcBeltItemPosA  = NamespacedKey(plugin, "belt_item_posa")   // on belt item display: "wN,bx,by,bz" of posA
    private val pdcBeltItemStack = NamespacedKey(plugin, "belt_item_stack2") // on belt item display: full ItemStack bytes
    // Millstone inventory PDC keys
    private val pdcMsInputType  = NamespacedKey(plugin, "ms_input_type")
    private val pdcMsInputCount = NamespacedKey(plugin, "ms_input_count")
    private val pdcMsOutput     = NamespacedKey(plugin, "ms_output")      // "MAT:n,MAT:n,..."
    private val pdcMsProgress   = NamespacedKey(plugin, "ms_progress")

    init {
        plugin.server.scheduler.runTaskTimer(plugin, Runnable { tick() }, 0L, 1L)
        // Delay restore by 1 tick so all worlds/chunks are ready
        plugin.server.scheduler.runTask(plugin, Runnable { restoreFromWorld() })
    }

    fun setSpeed(rpm: Float) {
        val dpt = kotlin.math.abs(rpm) * 360f / (20f * 60f)
        for ((_, entry) in gearsByPos) {
            if (entry.isMotor) entry.motorSpeed = dpt
        }
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    fun spawnGear(
        world: World, bx: Int, by: Int, bz: Int,
        orientQ: Quaternionf, axis: AxleAxis,
        gearType: GearType = GearType.COGWHEEL,
        isMotor: Boolean = false, rpm: Float = 0f
    ): Boolean {
        val pos = AxlePos(world.name, bx, by, bz)
        if (gearsByPos.containsKey(pos)) return false
        if (isBlockedByLargeGear(pos)) return false

        val translation = Vector3f(0f, 0f, 0f)
        val loc = Location(world, bx + 0.5, by + 0.5, bz + 0.5)

        val display = world.spawn(loc, ItemDisplay::class.java) { entity ->
            entity.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
            entity.interpolationDuration = 0
            entity.interpolationDelay = 0
            entity.transformation = Transformation(translation, computeTotalQ(orientQ, 0f),
                Vector3f(SCALE), Quaternionf(0f, 0f, 0f, 1f))
        }
        display.setItemStack(gearItem(gearType))

        // Spawn extra static display for types that need it
        val extraUuids = mutableListOf<UUID>()
        if (gearType == GearType.WATER_WHEEL || gearType == GearType.MILLSTONE) {
            val staticItem = when (gearType) {
                GearType.WATER_WHEEL -> waterWheelFixoItem()
                GearType.MILLSTONE   -> millstoneFixedItem()
                else -> null
            }
            if (staticItem != null) {
                val fixo = world.spawn(loc, ItemDisplay::class.java) { entity ->
                    entity.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
                    entity.interpolationDuration = 0
                    entity.interpolationDelay = 0
                    entity.transformation = Transformation(translation, computeTotalQ(orientQ, 0f),
                        Vector3f(SCALE), Quaternionf(0f, 0f, 0f, 1f))
                }
                fixo.setItemStack(staticItem)
                extraUuids.add(fixo.uniqueId)
            }
        }

        // Place barrier blocks for physical collision
        placeColliders(world, bx, by, bz, gearType)

        val entry = GearEntry(
            displayUuid = display.uniqueId,
            pos = pos, axis = axis,
            gearType = gearType,
            orientQ = Quaternionf(orientQ),
            translation = translation,
            isMotor = isMotor,
            motorSpeed = when {
                !isMotor -> 0f
                gearType == GearType.WATER_WHEEL -> computeWaterWheelDpt(world, pos, axis)
                else -> kotlin.math.abs(rpm) * 360f / (20f * 60f)
            },
            extraDisplayUuids = extraUuids
        )
        entry.cachedDisplay = display
        gearsByPos[pos] = entry
        tagDisplay(display, entry)
        if (gearType == GearType.MILLSTONE) millstoneData[pos] = MillstoneData()

        // ── Pre-connect: find neighbours before networks are merged ─────────────────
        // Must be done BEFORE connectGear() so we can distinguish spinning from stopped.
        val preConnectConns = findNeighborConnections(pos, axis, gearType)

        val allAxialNeighbors = preConnectConns
            .filter { (_, isAxial) -> isAxial }
            .mapNotNull { (nPos, _) -> gearsByPos[nPos] }
        // Prefer the axial neighbour that is already motor-driven (spinning side).
        val activeAxialNeighbor: GearEntry? =
            allAxialNeighbors.firstOrNull { n -> networks[n.networkId]?.motorPositions?.isNotEmpty() == true }
            ?: allAxialNeighbors.firstOrNull()

        // First lateral (meshing) neighbour — needed to align the tooth phase of gears
        // that connect only laterally (e.g. millstone, lone cogwheel on a perpendicular shaft).
        val activeLateralNeighbor: GearEntry? = preConnectConns
            .firstOrNull { (_, isAxial) -> !isAxial }
            ?.let { (nPos, _) -> gearsByPos[nPos] }

        // Snapshot active-side positions so resyncAxialChain won't snap them.
        val activeNetPositions: Set<AxlePos> =
            activeAxialNeighbor?.let { networks[it.networkId]?.members?.keys?.toSet() } ?: emptySet()

        if (!connectGear(entry)) return false

        val net = networks[entry.networkId]
        val meshOffset = computeMeshOffset(entry)

        // ── Choose initial quaternion — three cases ──────────────────────────────────
        //
        //  1. Axial neighbour  → copy live Q exactly (same orientQ + multiplier, zero drift)
        //
        //  2. Lateral neighbour → decompose neighbour's live Q to extract its current
        //     Y-axis angle, scale by gear ratio, add half-tooth mesh offset.
        //     Avoids the mismatch between delta-accumulated Q and net.angle reconstruction
        //     that previously misaligned millstone / cogwheel teeth on placement.
        //
        //  3. No neighbours    → reconstruct from net.angle (isolated / first in chain).
        val initialQ: Quaternionf = when {
            activeAxialNeighbor != null -> {
                Quaternionf(activeAxialNeighbor.currentDisplayQ)
            }
            activeLateralNeighbor != null -> {
                // orientQ⁻¹ × neighbourQ  =  axisAngle(Y, θ_neighbour)  for same-axis gears.
                val localRot = Quaternionf(entry.orientQ).conjugate()
                    .mul(activeLateralNeighbor.currentDisplayQ)
                val neighbourAngle = 2f *
                    kotlin.math.atan2(localRot.y.toDouble(), localRot.w.toDouble()).toFloat() *
                    (180f / Math.PI.toFloat())
                // Gear ratio between the two members (e.g. −1 for same-size, −2 or −0.5 for mixed).
                val ratio = if (activeLateralNeighbor.speedMultiplier != 0f)
                    entry.speedMultiplier / activeLateralNeighbor.speedMultiplier else 0f
                computeTotalQ(entry.orientQ, neighbourAngle * ratio + meshOffset)
            }
            else -> {
                val baseAngle = if (net != null && net.motorPositions.isNotEmpty())
                    net.angle * entry.speedMultiplier else 0f
                computeTotalQ(entry.orientQ, baseAngle + meshOffset)
            }
        }
        entry.currentDisplayQ = Quaternionf(initialQ)
        (plugin.server.getEntity(entry.displayUuid) as? ItemDisplay)?.transformation =
            Transformation(entry.translation, initialQ, Vector3f(SCALE), Quaternionf(0f, 0f, 0f, 1f))

        // ── Propagate Q through the stopped side of the axle chain ───────────────────
        // Gears that were disconnected kept their old stopped Q.  BFS outward from the
        // newly placed gear and snap every stopped axial member to the correct angle.
        // activeNetPositions are already in-sync and are excluded to avoid visual hitches.
        resyncAxialChain(entry, activeNetPositions)

        return true
    }

    fun removeGear(world: World, bx: Int, by: Int, bz: Int, dropItem: Boolean = false) {
        val pos = AxlePos(world.name, bx, by, bz)
        detachBelt(pos)
        val entry = gearsByPos.remove(pos) ?: return
        (entry.cachedDisplay ?: plugin.server.getEntity(entry.displayUuid))?.remove()
        entry.extraDisplayUuids.forEach { plugin.server.getEntity(it)?.remove() }
        removeColliders(world, bx, by, bz, entry.gearType)
        // Drop remaining millstone inventory items on removal
        millstoneData.remove(pos)?.let { ms ->
            val dropLoc = Location(world, bx + 0.5, by + 0.8, bz + 0.5)
            ms.inputItem?.let { mat -> if (ms.inputCount > 0) world.dropItemNaturally(dropLoc, org.bukkit.inventory.ItemStack(mat, ms.inputCount)) }
            ms.outputItems.forEach { world.dropItemNaturally(dropLoc, it) }
        }
        if (dropItem) world.dropItemNaturally(
            Location(world, bx + 0.5, by + 0.8, bz + 0.5), gearDropItem(entry.gearType))

        val network = networks[entry.networkId] ?: return
        network.members.remove(pos)
        network.motorPositions.remove(pos)

        if (network.members.isEmpty()) { networks.remove(entry.networkId); return }
        rebuildNetworks(entry.networkId)
    }

    fun hasGear(world: World, bx: Int, by: Int, bz: Int): Boolean =
        gearsByPos.containsKey(AxlePos(world.name, bx, by, bz))

    fun getEntry(pos: AxlePos): GearEntry? = gearsByPos[pos]

    fun saveMillstoneState(pos: AxlePos) {
        val entry = gearsByPos[pos] ?: return
        val ms = millstoneData[pos] ?: return
        val display = entry.cachedDisplay ?: return
        tagMillstoneState(display, ms)
    }

    // ─── Belt ────────────────────────────────────────────────────────────────

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

        // Scan all positions A→B, validate each one
        val allPositions = mutableListOf<AxlePos>()
        val axlePositions = mutableSetOf<AxlePos>()

        for (i in 0..dist) {
            val px = posA.bx + stepX * i
            val pz = posA.bz + stepZ * i
            val pos = AxlePos(posA.worldName, px, posA.by, pz)
            allPositions.add(pos)

            val existing = gearsByPos[pos]
            when {
                existing != null -> {
                    // Must be a same-axis axle not already in a belt
                    if (existing.gearType != GearType.AXLE || existing.axis != entryA.axis) return false
                    if (beltsByAxle.containsKey(pos)) return false
                    axlePositions.add(pos)
                }
                else -> {
                    // Must be air — no solid block may block the belt path
                    if (!world.getBlockAt(px, posA.by, pz).type.isAir) return false
                }
            }
        }

        val beltAngle = when {
            stepX > 0 ->  90f
            stepX < 0 -> -90f
            stepZ < 0 -> 180f
            else      ->   0f
        }
        val beltOrientQ = RotationUtil.axisAngle(0f, 1f, 0f, beltAngle)
        val spinItem = beltSpinItem()
        val fixedItem = beltFixedItem()
        val fixedUuids = mutableListOf<UUID>()

        for (pos in allPositions) {
            if (pos in axlePositions) {
                // Convert axle display to esteira_spin
                val e = gearsByPos[pos] ?: continue
                val display = e.cachedDisplay ?: plugin.server.getEntity(e.displayUuid) as? ItemDisplay ?: continue
                display.setItemStack(spinItem)
            } else {
                // Place barrier for gap position
                world.getBlockAt(pos.bx, pos.by, pos.bz).type = Material.BARRIER
            }
            // Spawn esteira_fixed at every position (axle or gap)
            val loc = Location(world, pos.bx + 0.5, pos.by + 0.5, pos.bz + 0.5)
            val fixed = world.spawn(loc, ItemDisplay::class.java) { e ->
                e.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
                e.interpolationDuration = 0
                e.transformation = Transformation(Vector3f(), Quaternionf(beltOrientQ), Vector3f(SCALE), IDENTITY_Q)
            }
            fixed.setItemStack(fixedItem)
            fixedUuids.add(fixed.uniqueId)
        }

        // Merge all axle networks (belt connects them at 1:1 ratio)
        var primaryNet: GearNetwork? = null
        for (pos in axlePositions) {
            val e = gearsByPos[pos] ?: continue
            val net = networks[e.networkId] ?: continue
            if (primaryNet == null) {
                primaryNet = net
            } else if (net !== primaryNet) {
                val refMult = gearsByPos[posA]?.speedMultiplier ?: 1f
                val correction = if (e.speedMultiplier != 0f) refMult / e.speedMultiplier else 1f
                mergeInto(primaryNet!!, net, correction)
            }
        }

        val mergedNetworkId = primaryNet?.id ?: -1
        val belt = BeltEntry(allPositions, axlePositions, fixedUuids, mergedNetworkId)
        for (pos in allPositions) beltsByAxle[pos] = belt

        // ── Persist belt metadata on display entities ─────────────────────────
        val eA = gearsByPos[posA]!!
        val dispA: ItemDisplay? = eA.cachedDisplay?.takeIf { it.isValid }
            ?: plugin.server.getEntity(eA.displayUuid) as? ItemDisplay
        if (dispA != null) {
            dispA.persistentDataContainer.set(
                pdcBeltEndB, PersistentDataType.STRING, "${posB.bx},${posB.by},${posB.bz}")
        } else {
            plugin.logger.warning("[Belt] Could not tag posA entity for persistence at $posA")
        }

        // Each esteira_fixed: stores posA so we can collect their UUIDs on reload
        val posATag = "${posA.worldName},${posA.bx},${posA.by},${posA.bz}"
        fixedUuids.forEach { uuid ->
            (plugin.server.getEntity(uuid) as? ItemDisplay)
                ?.persistentDataContainer?.set(pdcBeltFixedPosA, PersistentDataType.STRING, posATag)
        }

        return true
    }

    fun detachBelt(pos: AxlePos, clearPersistence: Boolean = true) {
        val belt = beltsByAxle[pos] ?: return
        val worldName = belt.allPositions.firstOrNull()?.worldName ?: return
        val world = plugin.server.getWorld(worldName) ?: return

        // Drop all in-transit items back into the world
        val posA = belt.allPositions.first()
        val posB = belt.allPositions.last()
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

        // Clear belt persistence tag from posA's gear display (only when player actively breaks the belt)
        if (clearPersistence) {
            gearsByPos[posA]?.let { e ->
                (e.cachedDisplay ?: plugin.server.getEntity(e.displayUuid) as? ItemDisplay)
                    ?.persistentDataContainer?.remove(pdcBeltEndB)
            }
        }

        // Remove esteira_fixed displays
        belt.fixedDisplayUuids.forEach { plugin.server.getEntity(it)?.remove() }

        // Remove belt barriers at gap positions and revert axle displays
        val axleItem = gearItem(GearType.AXLE)
        for (p in belt.allPositions) {
            beltsByAxle.remove(p)
            if (p in belt.axlePositions) {
                val e = gearsByPos[p] ?: continue
                val display = e.cachedDisplay ?: plugin.server.getEntity(e.displayUuid) as? ItemDisplay ?: continue
                display.setItemStack(axleItem)
            } else {
                val block = world.getBlockAt(p.bx, p.by, p.bz)
                if (block.type == Material.BARRIER) block.type = Material.AIR
            }
        }

        if (belt.mergedNetworkId != -1) rebuildNetworks(belt.mergedNetworkId)
    }

    fun addAxleToBelt(world: World, pos: AxlePos): Boolean {
        val belt = beltsByAxle[pos] ?: return false
        if (pos in belt.axlePositions) return false

        val refAxlePos = belt.axlePositions.firstOrNull() ?: return false
        val refEntry = gearsByPos[refAxlePos] ?: return false

        val loc = Location(world, pos.bx + 0.5, pos.by + 0.5, pos.bz + 0.5)
        val display = world.spawn(loc, ItemDisplay::class.java) { e ->
            e.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
            e.interpolationDuration = 0
            e.interpolationDelay = 0
            e.transformation = Transformation(
                Vector3f(), Quaternionf(refEntry.currentDisplayQ), Vector3f(SCALE), IDENTITY_Q)
        }
        display.setItemStack(beltSpinItem())

        val entry = GearEntry(
            displayUuid = display.uniqueId,
            pos = pos,
            axis = refEntry.axis,
            gearType = GearType.AXLE,
            orientQ = Quaternionf(refEntry.orientQ),
            translation = Vector3f(),
            currentDisplayQ = Quaternionf(refEntry.currentDisplayQ)
        )
        entry.cachedDisplay = display
        gearsByPos[pos] = entry
        tagDisplay(display, entry)

        val refNet = networks[refEntry.networkId]
        if (refNet != null) assignToNetwork(entry, refNet, refEntry.speedMultiplier)
        belt.axlePositions.add(pos)
        return true
    }

    // ─── Belt item transport ─────────────────────────────────────────────────

    private fun tickBelts() {
        val seen = mutableSetOf<BeltEntry>()
        for (belt in beltsByAxle.values) if (seen.add(belt)) tickBelt(belt)
    }

    private fun tickBelt(belt: BeltEntry) {
        val posA = belt.allPositions.firstOrNull() ?: return
        val posB = belt.allPositions.lastOrNull() ?: return
        val dist = belt.allPositions.size - 1
        if (dist == 0) return
        val world = plugin.server.getWorld(posA.worldName) ?: return

        val stepX = when { posB.bx > posA.bx -> 1; posB.bx < posA.bx -> -1; else -> 0 }
        val stepZ = when { posB.bz > posA.bz -> 1; posB.bz < posA.bz -> -1; else -> 0 }

        if (tickCount % 4 == 0) pickupItemEntities(world, belt, posA, stepX, stepZ)
        if (tickCount % 8 == 0) tickBeltHoppers(world, belt, posA, stepX, stepZ)

        val refEntry = gearsByPos[belt.axlePositions.firstOrNull() ?: return] ?: return
        val baseDpt  = networks[refEntry.networkId]?.lastBaseDpt ?: 0f
        val beltTag  = "belt[${posA.bx},${posA.by},${posA.bz}→${posB.bx},${posB.bz}]"

        // Signed speed: positive → items move A→B, negative → B→A.
        // Physics: surface velocity at top of shaft = ω × (0,r,0).
        //   X-axis shaft: v = (0,0,ωr)  → +Z at positive ω  → dirSign = stepZ
        //   Z-axis shaft: v = (-ωr,0,0) → -X at positive ω  → dirSign = -stepX
        //   Y-axis shaft: v = (+ωr,0,0) or (0,0,-ωr)        → dirSign = stepX-stepZ
        val dirSign = when (refEntry.axis) {
            AxleAxis.X -> stepZ.toFloat()
            AxleAxis.Z -> -stepX.toFloat()
            AxleAxis.Y -> (stepX - stepZ).toFloat()
        }
        val signedSpeed = baseDpt * refEntry.speedMultiplier * dirSign * BELT_SPEED_FACTOR
        if (signedSpeed == 0f || belt.items.isEmpty()) return

        val speed   = kotlin.math.abs(signedSpeed)
        val forward = signedSpeed > 0f   // true = A→B, false = B→A
        if (belt.items.isNotEmpty())
            plugin.logger.info("[BeltDBG] $beltTag tick=$tickCount items=${belt.items.size} speed=${"%.4f".format(speed)} forward=$forward")

        // "Exit" end and direction for end-of-belt and belt-chain checks
        val endPos   = if (forward) posB  else posA
        val endBeltP = if (forward) dist.toFloat() else 0f
        val exitX    = if (forward) stepX else -stepX
        val exitZ    = if (forward) stepZ else -stepZ

        // Sort so "frontmost" items (closest to exit) come first in the list
        if (forward) belt.items.sortByDescending { it.beltPos } else belt.items.sortBy { it.beltPos }
        val toRemove = mutableListOf<BeltItem>()

        // Adjacent positions that could be the start of a connected belt.
        val exitCandidates = listOf(
            AxlePos(posA.worldName, endPos.bx + exitX,  endPos.by, endPos.bz + exitZ),
            AxlePos(posA.worldName, endPos.bx - exitZ,  endPos.by, endPos.bz + exitX),
            AxlePos(posA.worldName, endPos.bx + exitZ,  endPos.by, endPos.bz - exitX),
        )

        for (i in belt.items.indices) {
            val item = belt.items[i]
            if (item in toRemove) continue

            val itemId = item.displayUuid.toString().takeLast(6)

            // ── Phase 1: item has reached or passed the belt end ─────────────
            val pastEnd = if (forward) item.beltPos >= endBeltP else item.beltPos <= endBeltP
            if (pastEnd) {
                plugin.logger.info("[BeltDBG]   item[$itemId] PAST_END beltPos=${"%.3f".format(item.beltPos)} endBeltP=$endBeltP")

                // Find the first connected belt (straight, left, or right turn).
                var nextBelt: BeltEntry? = null
                var nextEntryIdx = 0
                for (candidate in exitCandidates) {
                    val nb = beltsByAxle[candidate] ?: continue
                    if (nb === belt) continue
                    val idx = nb.allPositions.indexOf(candidate)
                    if (idx >= 0) { nextBelt = nb; nextEntryIdx = idx; break }
                }
                plugin.logger.info("[BeltDBG]   item[$itemId] nextBelt=${nextBelt != null} candidates=$exitCandidates")

                // ── Phase 2: item has crossed the 1-block gap → instant swap ─
                val gapLimit = if (forward) endBeltP + 1f else endBeltP - 1f
                val crossedGap = if (forward) item.beltPos >= gapLimit else item.beltPos <= gapLimit

                if (nextBelt != null && crossedGap) {
                    val entryPos = nextEntryIdx.toFloat()
                    val overshoot = if (forward) item.beltPos - gapLimit else gapLimit - item.beltPos
                    val newBeltPos = entryPos + overshoot
                    val blocked = nextBelt.items.any { kotlin.math.abs(it.beltPos - entryPos) < BELT_ITEM_SPACING }
                    plugin.logger.info("[BeltDBG]   item[$itemId] TRANSFER→nextBelt newBeltPos=${"%.3f".format(newBeltPos)} blocked=$blocked")
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
                            item.beltPos = newBeltPos
                            item.cachedDisplay = disp
                            toRemove.add(item)
                            nextBelt.items.add(item)
                            updateBeltItemDisplay(item, nA, nSX, nSZ)
                        } else {
                            plugin.logger.warning("[BeltDBG]   item[$itemId] display entity MISSING at transfer, respawning")
                            toRemove.add(item)
                            nextBelt.items.add(spawnBeltItem(world, nA, nSX, nSZ, item.item.clone(), newBeltPos))
                        }
                    } else {
                        plugin.logger.info("[BeltDBG]   item[$itemId] HOLD at gapLimit=${"%.3f".format(gapLimit)} (dest blocked)")
                        item.beltPos = gapLimit
                    }

                } else if (nextBelt != null) {
                    val entryPos = nextEntryIdx.toFloat()
                    val destBlocked = nextBelt.items.any { kotlin.math.abs(it.beltPos - entryPos) < BELT_ITEM_SPACING }
                    val advanceGap = if (forward) minOf(speed, gapLimit - item.beltPos).coerceAtLeast(0f)
                                     else        minOf(speed, item.beltPos - gapLimit).coerceAtLeast(0f)
                    plugin.logger.info("[BeltDBG]   item[$itemId] GAP_CROSS beltPos=${"%.3f".format(item.beltPos)} gapLimit=${"%.3f".format(gapLimit)} destBlocked=$destBlocked advanceGap=${"%.4f".format(advanceGap)}")
                    if (!destBlocked && advanceGap > 0f) {
                        item.beltPos = if (forward) item.beltPos + advanceGap else item.beltPos - advanceGap
                        updateBeltItemDisplay(item, posA, stepX, stepZ)
                    }

                } else {
                    val endBlock = world.getBlockAt(endPos.bx + exitX, endPos.by, endPos.bz + exitZ)
                    plugin.logger.info("[BeltDBG]   item[$itemId] END solid=${endBlock.type.isSolid} block=${endBlock.type}")
                    if (!endBlock.type.isSolid) {
                        (item.cachedDisplay?.takeIf { it.isValid }
                            ?: plugin.server.getEntity(item.displayUuid) as? ItemDisplay)?.remove()
                        world.dropItemNaturally(
                            Location(world, endPos.bx + exitX + 0.5, endPos.by + 0.875, endPos.bz + exitZ + 0.5),
                            item.item.clone()
                        )
                        plugin.logger.info("[BeltDBG]   item[$itemId] DROPPED at (${endPos.bx+exitX},${endPos.by},${endPos.bz+exitZ})")
                        toRemove.add(item)
                    }
                }
                continue
            }

            // ── Normal movement along the belt ───────────────────────────────
            val frontItem = if (i > 0 && belt.items[i - 1] !in toRemove) belt.items[i - 1] else null

            val advance = if (frontItem != null) {
                val gap = if (forward) frontItem.beltPos - item.beltPos
                          else        item.beltPos - frontItem.beltPos
                val adv = if (gap <= BELT_ITEM_SPACING) 0f else minOf(speed, gap - BELT_ITEM_SPACING)
                if (adv == 0f) plugin.logger.info("[BeltDBG]   item[$itemId] BLOCKED_BY_FRONT gap=${"%.3f".format(gap)} spacing=$BELT_ITEM_SPACING front=${frontItem.displayUuid.toString().takeLast(6)}")
                adv
            } else {
                val wouldPassEnd = if (forward) item.beltPos + speed >= endBeltP
                                   else         item.beltPos - speed <= endBeltP
                if (wouldPassEnd) {
                    val hasNextBelt = exitCandidates.any { c -> beltsByAxle[c]?.let { it !== belt } == true }
                    if (hasNextBelt) speed
                    else {
                        val endBlock = world.getBlockAt(endPos.bx + exitX, endPos.by, endPos.bz + exitZ)
                        if (endBlock.type.isSolid) {
                            val remaining = if (forward) endBeltP - item.beltPos else item.beltPos - endBeltP
                            if (remaining <= 0f) plugin.logger.info("[BeltDBG]   item[$itemId] AT_WALL beltPos=${"%.3f".format(item.beltPos)}")
                            remaining.coerceAtLeast(0f)
                        } else speed
                    }
                } else speed
            }

            plugin.logger.info("[BeltDBG]   item[$itemId] MOVE beltPos=${"%.3f".format(item.beltPos)} advance=${"%.4f".format(advance)} frontItem=${frontItem != null}")

            if (advance > 0f) {
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
            val slotPos = index.toFloat()
            val occupied = belt.items.any { kotlin.math.abs(it.beltPos - slotPos) < 0.5f }
            val allNearby = world.getNearbyEntities(
                Location(world, pos.bx + 0.5, pos.by + 1.25, pos.bz + 0.5), 0.45, 0.45, 0.45
            ).filterIsInstance<org.bukkit.entity.Item>()
            val nearby = allNearby.filter { !it.itemStack.type.isAir }
            if (allNearby.isNotEmpty())
                plugin.logger.info("[BeltDBG] $beltTag pickup scan slot=$index slotPos=$slotPos occupied=$occupied allFound=${allNearby.size} nonAir=${nearby.size}")
            if (occupied || nearby.isEmpty()) continue
            val itemEntity = nearby.first()
            plugin.logger.info("[BeltDBG] $beltTag PICKUP entity=${itemEntity.uniqueId.toString().takeLast(6)} stack=${itemEntity.itemStack.type} pos=(${itemEntity.location.x.toInt()},${itemEntity.location.y.toInt()},${itemEntity.location.z.toInt()})")
            val itemStack  = itemEntity.itemStack.clone()
            itemEntity.itemStack = org.bukkit.inventory.ItemStack(Material.AIR)
            itemEntity.remove()
            val spawned = spawnBeltItem(world, posA, stepX, stepZ, itemStack, slotPos)
            plugin.logger.info("[BeltDBG] $beltTag spawned BeltItem=${spawned.displayUuid.toString().takeLast(6)} beltPos=$slotPos")
            belt.items.add(spawned)
        }
    }

    private fun tickBeltHoppers(world: World, belt: BeltEntry, posA: AxlePos, stepX: Int, stepZ: Int) {
        for ((index, pos) in belt.allPositions.withIndex()) {
            val slotPos = index.toFloat()
            if (belt.items.any { kotlin.math.abs(it.beltPos - slotPos) < 0.5f }) continue

            // Hopper above facing DOWN
            if (tryHopperInsert(world.getBlockAt(pos.bx, pos.by + 1, pos.bz),
                    org.bukkit.block.BlockFace.DOWN, world, belt, posA, stepX, stepZ, slotPos)) continue

            // Hoppers from four sides
            for ((dx, dz, face) in listOf(
                Triple( 1,  0, org.bukkit.block.BlockFace.WEST),
                Triple(-1,  0, org.bukkit.block.BlockFace.EAST),
                Triple( 0,  1, org.bukkit.block.BlockFace.NORTH),
                Triple( 0, -1, org.bukkit.block.BlockFace.SOUTH)
            )) {
                if (tryHopperInsert(world.getBlockAt(pos.bx + dx, pos.by, pos.bz + dz),
                        face, world, belt, posA, stepX, stepZ, slotPos)) break
            }
        }
    }

    private fun tryHopperInsert(
        block: org.bukkit.block.Block, requiredFacing: org.bukkit.block.BlockFace,
        world: World, belt: BeltEntry, posA: AxlePos, stepX: Int, stepZ: Int, slotPos: Float
    ): Boolean {
        if (block.type != Material.HOPPER) return false
        val data = block.blockData as? org.bukkit.block.data.type.Hopper ?: return false
        if (data.facing != requiredFacing) return false
        val inv = (block.state as? org.bukkit.block.Hopper)?.inventory ?: return false
        for (i in 0 until inv.size) {
            val stack = inv.getItem(i) ?: continue
            if (stack.type.isAir) continue
            val transfer = stack.clone().also { it.amount = 1 }
            stack.amount -= 1
            inv.setItem(i, if (stack.amount <= 0) null else stack)
            belt.items.add(spawnBeltItem(world, posA, stepX, stepZ, transfer, slotPos))
            return true
        }
        return false
    }

    private fun spawnBeltItem(
        world: World, posA: AxlePos, stepX: Int, stepZ: Int,
        item: ItemStack, beltPos: Float
    ): BeltItem {
        val loc = Location(world, posA.bx + 0.5, posA.by + 0.5, posA.bz + 0.5)
        val display = world.spawn(loc, ItemDisplay::class.java) { e ->
            e.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
            e.interpolationDuration = 0
            e.interpolationDelay   = 0
            e.transformation = Transformation(
                Vector3f(beltPos * stepX, BELT_ITEM_Y_OFFSET, beltPos * stepZ),
                beltItemRotation(stepX, stepZ),
                Vector3f(BELT_ITEM_SCALE, BELT_ITEM_SCALE, BELT_ITEM_SCALE), IDENTITY_Q
            )
        }
        display.setItemStack(item)
        // Tag for persistence: posA lets us locate the belt on reload; stack encodes what the item is.
        // beltPos is reconstructed from the transformation translation (persisted in entity NBT).
        val posATag = "${posA.worldName},${posA.bx},${posA.by},${posA.bz}"
        display.persistentDataContainer.set(pdcBeltItemPosA,  PersistentDataType.STRING, posATag)
        display.persistentDataContainer.set(pdcBeltItemStack, PersistentDataType.BYTE_ARRAY, item.serializeAsBytes())
        return BeltItem(display.uniqueId, item.clone(), beltPos, posA.bx, posA.bz).also { it.cachedDisplay = display }
    }

    private fun updateBeltItemDisplay(item: BeltItem, posA: AxlePos, stepX: Int, stepZ: Int) {
        val display = item.cachedDisplay?.takeIf { it.isValid }
            ?: (plugin.server.getEntity(item.displayUuid) as? ItemDisplay)?.also { item.cachedDisplay = it }
            ?: return
        display.interpolationDuration = 2
        display.interpolationDelay   = 0
        // Translation = (offset from original anchor to current belt's posA) + (beltPos in belt direction).
        // The entity is never teleported after spawn, so this accumulates cleanly across belt transfers.
        display.transformation = Transformation(
            Vector3f(
                (posA.bx - item.bxAnchor) + item.beltPos * stepX,
                BELT_ITEM_Y_OFFSET,
                (posA.bz - item.bzAnchor) + item.beltPos * stepZ
            ),
            beltItemRotation(stepX, stepZ),
            Vector3f(BELT_ITEM_SCALE, BELT_ITEM_SCALE, BELT_ITEM_SCALE), IDENTITY_Q
        )
    }

    /**
     * Quaternion that makes an item lie flat on the belt surface, oriented along the belt direction.
     * Derivation: rotate -90° around X (face visible from above), then rotate around Y to align
     * with the belt travel direction.
     *
     * Belt dir  │ Y-angle
     * ──────────┼────────
     *  +X       │  +90°
     *  -X       │  -90°
     *  +Z       │   0°   (default)
     *  -Z       │ +180°
     */
    // Fixed rotation: item always lies flat regardless of belt direction.
    // Removing Y-axis rotation eliminates the 90° spin artefact at L-connections.
    private fun beltItemRotation(stepX: Int, stepZ: Int): Quaternionf =
        RotationUtil.axisAngle(1f, 0f, 0f, -90f)

    // ─── Network logic ───────────────────────────────────────────────────────

    private fun connectGear(entry: GearEntry): Boolean {
        val connections = findNeighborConnections(entry.pos, entry.axis, entry.gearType)

        if (connections.isEmpty()) {
            assignToNetwork(entry, createNetwork(), mult = 1.0f)
            return true
        }

        val byNetwork = connections
            .mapNotNull { (nPos, isAxial) -> gearsByPos[nPos]?.let { Triple(it.networkId, nPos, isAxial) } }
            .filter { it.first != -1 }
            .groupBy { it.first }

        if (byNetwork.isEmpty()) {
            assignToNetwork(entry, createNetwork(), mult = 1.0f)
            return true
        }

        val (primaryId, primaryConns) = byNetwork.entries.first()
        val (_, firstNPos, firstIsAxial) = primaryConns.first()
        val firstNeighbor = gearsByPos[firstNPos]!!
        val myMult = computeMyMult(entry, firstNeighbor, firstIsAxial)

        // Detect gear-locking: every additional connection within the same network must imply the same myMult.
        // Multiple connections to different networks are fine (handled via correction below).
        for ((netId, conns) in byNetwork) {
            val refMult = if (netId == primaryId) myMult else {
                val (_, rPos, rAxial) = conns.first()
                computeMyMult(entry, gearsByPos[rPos]!!, rAxial)
            }
            val extraConns = if (netId == primaryId) conns.drop(1) else conns.drop(1)
            for ((_, nPos, isAxial) in extraConns) {
                val neighbor = gearsByPos[nPos] ?: continue
                val expected = computeMyMult(entry, neighbor, isAxial)
                if (kotlin.math.abs(refMult - expected) > 0.001f) {
                    val w = plugin.server.getWorld(entry.pos.worldName) ?: break
                    plugin.server.broadcastMessage(
                        "§c[Create] Gear locked! Block at (${entry.pos.bx}, ${entry.pos.by}, ${entry.pos.bz}) broke.")
                    removeGear(w, entry.pos.bx, entry.pos.by, entry.pos.bz)
                    return false
                }
            }
        }

        val primaryNetwork = networks[primaryId]!!
        assignToNetwork(entry, primaryNetwork, mult = myMult)

        for ((otherId, otherConns) in byNetwork) {
            if (otherId == primaryId) continue
            val (_, otherNPos, otherIsAxial) = otherConns.first()
            val otherNeighbor = gearsByPos[otherNPos]!!
            val expectedMyMultFromOther = computeMyMult(entry, otherNeighbor, otherIsAxial)
            val correction = myMult / expectedMyMultFromOther
            mergeInto(primaryNetwork, networks[otherId]!!, correction)
        }

        if (checkMotorConflict(primaryNetwork, bridgePos = entry.pos)) return false
        return true
    }

    private fun computeMyMult(myEntry: GearEntry, neighbor: GearEntry, isAxial: Boolean): Float {
        val neighborMult = neighbor.speedMultiplier
        return when {
            isAxial -> neighborMult
            myEntry.axis != neighbor.axis -> neighborMult * bevelRatioSign(myEntry.pos, myEntry.axis, neighbor.pos, neighbor.axis)
            else -> neighborMult * lateralRatio(myEntry.gearType, neighbor.gearType)
        }
    }

    // ratio = sign of (offset·axisA) × (offset·axisB); derived from rim-velocity matching at bevel contact
    private fun bevelRatioSign(posA: AxlePos, axisA: AxleAxis, posB: AxlePos, axisB: AxleAxis): Float {
        val dx = posB.bx - posA.bx
        val dy = posB.by - posA.by
        val dz = posB.bz - posA.bz
        val (ax1, ay1, az1) = axisA.positiveOffset()
        val (ax2, ay2, az2) = axisB.positiveOffset()
        val vA = dx * ax1 + dy * ay1 + dz * az1
        val vB = dx * ax2 + dy * ay2 + dz * az2
        return if (vA * vB > 0) 1f else -1f
    }

    // Returns the speed ratio of `myType` relative to `neighborType` for a lateral (meshing) connection.
    // Sign is always negative (opposite rotation). Magnitude is the gear ratio.
    // MILLSTONE is treated as COGWHEEL for ratio purposes (same tooth-ring size)
    private fun lateralRatio(myType: GearType, neighborType: GearType): Float {
        val my = if (myType == GearType.MILLSTONE) GearType.COGWHEEL else myType
        val nb = if (neighborType == GearType.MILLSTONE) GearType.COGWHEEL else neighborType
        return when {
            my == GearType.COGWHEEL       && nb == GearType.COGWHEEL       -> -1.0f
            my == GearType.LARGE_COGWHEEL && nb == GearType.LARGE_COGWHEEL -> -1.0f
            my == GearType.COGWHEEL       && nb == GearType.LARGE_COGWHEEL -> -2.0f
            else                                                            -> -0.5f
        }
    }

    private fun rebuildNetworks(oldId: Int) {
        val old = networks.remove(oldId) ?: return
        val remaining = old.members.keys.filter { it in gearsByPos }.toSet()
        remaining.forEach { gearsByPos[it]?.networkId = -1 }

        val visited = mutableSetOf<AxlePos>()
        for (start in remaining) {
            if (start in visited) continue
            val net = createNetwork()
            net.angle = old.angle

            val startMult = old.members[start] ?: 1.0f
            val queue = ArrayDeque<Pair<AxlePos, Float>>()
            queue.add(start to startMult)
            while (queue.isNotEmpty()) {
                val (cur, mult) = queue.removeFirst()
                if (cur in visited || cur !in remaining) continue
                visited.add(cur)
                val e = gearsByPos[cur] ?: continue
                e.networkId = net.id; e.speedMultiplier = mult
                net.members[cur] = mult
                if (e.isMotor) net.motorPositions.add(cur)
                findNeighborConnections(cur, e.axis, e.gearType).forEach { (nPos, isAxial) ->
                    if (nPos !in visited && nPos in remaining) {
                        val neighborE = gearsByPos[nPos] ?: return@forEach
                        val nextMult = when {
                            isAxial -> mult
                            e.axis != neighborE.axis -> mult * bevelRatioSign(e.pos, e.axis, neighborE.pos, neighborE.axis)
                            else -> mult * lateralRatio(neighborE.gearType, e.gearType)
                        }
                        queue.add(nPos to nextMult)
                    }
                }
                // Belt connections: axles in the same belt stay linked at 1:1 ratio.
                // Entries for a detached belt are already removed from beltsByAxle before
                // rebuildNetworks is called, so this only follows belts that still exist.
                beltsByAxle[cur]?.axlePositions?.forEach { bPos ->
                    if (bPos !in visited && bPos in remaining) queue.add(bPos to mult)
                }
            }
        }
    }

    private fun checkMotorConflict(network: GearNetwork, bridgePos: AxlePos): Boolean {
        if (network.motorPositions.size < 2) return false
        // Compute networkBaseDpt for each motor: motorSpeed / speedMultiplier
        val baseDpts = network.motorPositions.mapNotNull { pos ->
            val e = gearsByPos[pos] ?: return@mapNotNull null
            if (e.speedMultiplier == 0f) null else e.motorSpeed / e.speedMultiplier
        }
        val hasPositive = baseDpts.any { it > 0 }
        val hasNegative = baseDpts.any { it < 0 }
        if (!hasPositive || !hasNegative) return false  // all same direction, no conflict

        val w = plugin.server.getWorld(bridgePos.worldName) ?: return false
        plugin.server.broadcastMessage(
            "§c[Create] Motor conflict! Block at (${bridgePos.bx}, ${bridgePos.by}, ${bridgePos.bz}) broke.")
        removeGear(w, bridgePos.bx, bridgePos.by, bridgePos.bz)
        return true
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Fastest motor's networkBaseDpt (signed).
     * Returns 0 if no motor is present OR if the network is overstressed.
     * Also updates network.stressCapacity / stressImpact as a side-effect.
     */
    private fun networkEffectiveDpt(network: GearNetwork): Float {
        var best = 0f
        for (pos in network.motorPositions) {
            val e = gearsByPos[pos] ?: continue
            if (e.speedMultiplier == 0f) continue
            val baseDpt = e.motorSpeed / e.speedMultiplier
            if (kotlin.math.abs(baseDpt) > kotlin.math.abs(best)) best = baseDpt
        }
        if (best == 0f) return 0f

        computeNetworkStress(network, best)
        return if (network.isOverstressed) 0f else best
    }

    /**
     * Computes total stress capacity and impact for the network at the given baseDpt,
     * storing results in network.stressCapacity and network.stressImpact.
     *
     * Stress formula (matching Create mod):
     *   capacity/impact = baseValue × |rpm|
     *   where rpm = baseDpt × speedMultiplier × (20×60/360)
     *
     * Values:
     *   WATER_WHEEL  → +16 SU/RPM capacity
     *   MOTOR        → +256 SU/RPM capacity  (creative-style, effectively unlimited)
     *   MILLSTONE    → +4 SU/RPM impact
     *   others       → no stress (pure transmission)
     */
    private fun computeNetworkStress(network: GearNetwork, baseDpt: Float) {
        var capacity = 0f
        var impact   = 0f
        for ((pos, mult) in network.members) {
            val entry = gearsByPos[pos] ?: continue
            val rpm = kotlin.math.abs(baseDpt * mult) * DPT_TO_RPM
            when (entry.gearType) {
                GearType.WATER_WHEEL -> capacity += STRESS_CAPACITY_WATER_WHEEL * rpm
                GearType.MOTOR       -> capacity += STRESS_CAPACITY_MOTOR       * rpm
                GearType.MILLSTONE   -> impact   += STRESS_IMPACT_MILLSTONE     * rpm
                else -> { /* cogwheels, axles: zero stress */ }
            }
        }
        network.stressCapacity = capacity
        network.stressImpact   = impact
    }

    // Step ticks based on the fastest visual speed across all members
    private fun computeStepTicks(baseDpt: Float, network: GearNetwork): Int {
        val maxMult = network.members.values.maxOfOrNull { kotlin.math.abs(it) } ?: 1.0f
        val maxVisualDpt = kotlin.math.abs(baseDpt) * maxMult
        return if (maxVisualDpt == 0f) MAX_STEP_TICKS
               else (MAX_STEP_ANGLE / maxVisualDpt).toInt().coerceIn(1, MAX_STEP_TICKS)
    }

    private fun canMeshLaterally(type: GearType) =
        type == GearType.COGWHEEL || type == GearType.LARGE_COGWHEEL || type == GearType.MILLSTONE

    private fun findNeighborConnections(pos: AxlePos, axis: AxleAxis, gearType: GearType): List<Pair<AxlePos, Boolean>> {
        val result = mutableListOf<Pair<AxlePos, Boolean>>()
        val (ax, ay, az) = axis.positiveOffset()
        // MILLSTONE only connects laterally, never via the axle direction
        if (gearType != GearType.MILLSTONE) {
            for (f in listOf(1, -1)) {
                val n = AxlePos(pos.worldName, pos.bx + ax * f, pos.by + ay * f, pos.bz + az * f)
                val neighbor = gearsByPos[n] ?: continue
                if (neighbor.axis == axis && neighbor.gearType != GearType.MILLSTONE)
                    result.add(n to true)
            }
        }
        if (canMeshLaterally(gearType)) {
            for (neighborType in listOf(GearType.COGWHEEL, GearType.LARGE_COGWHEEL, GearType.MILLSTONE)) {
                for ((dx, dy, dz) in meshingOffsets(axis, gearType, neighborType)) {
                    val n = AxlePos(pos.worldName, pos.bx + dx, pos.by + dy, pos.bz + dz)
                    val neighbor = gearsByPos[n] ?: continue
                    if (neighbor.axis == axis && neighbor.gearType == neighborType) result.add(n to false)
                }
            }
        }
        // Bevel connections: LARGE_COGWHEEL with a perpendicular-axis LARGE_COGWHEEL
        if (gearType == GearType.LARGE_COGWHEEL) {
            for ((offset, neighborAxis) in bevelCandidates(axis)) {
                val (dx, dy, dz) = offset
                val n = AxlePos(pos.worldName, pos.bx + dx, pos.by + dy, pos.bz + dz)
                val neighbor = gearsByPos[n] ?: continue
                if (neighbor.gearType == GearType.LARGE_COGWHEEL && neighbor.axis == neighborAxis) result.add(n to false)
            }
        }
        return result
    }

    // Orthogonal distance-1 offsets in the plane perpendicular to axis (used for COGWHEEL↔COGWHEEL and blocking)
    private fun perpendicularOffsets(axis: AxleAxis) = when (axis) {
        AxleAxis.Y -> listOf(Triple(1,0,0), Triple(-1,0,0), Triple(0,0,1), Triple(0,0,-1))
        AxleAxis.X -> listOf(Triple(0,1,0), Triple(0,-1,0), Triple(0,0,1), Triple(0,0,-1))
        AxleAxis.Z -> listOf(Triple(1,0,0), Triple(-1,0,0), Triple(0,1,0), Triple(0,-1,0))
    }

    // Diagonal offsets (distance 1 in each of 2 perpendicular directions) for COGWHEEL↔LARGE_COGWHEEL
    private fun diagonalOffsets(axis: AxleAxis) = when (axis) {
        AxleAxis.Y -> listOf(Triple(1,0,1), Triple(1,0,-1), Triple(-1,0,1), Triple(-1,0,-1))
        AxleAxis.X -> listOf(Triple(0,1,1), Triple(0,1,-1), Triple(0,-1,1), Triple(0,-1,-1))
        AxleAxis.Z -> listOf(Triple(1,1,0), Triple(1,-1,0), Triple(-1,1,0), Triple(-1,-1,0))
    }

    private fun meshingOffsets(axis: AxleAxis, myType: GearType, neighborType: GearType): List<Triple<Int,Int,Int>> {
        // Treat MILLSTONE as COGWHEEL for meshing offset calculation
        val my = if (myType == GearType.MILLSTONE) GearType.COGWHEEL else myType
        val nb = if (neighborType == GearType.MILLSTONE) GearType.COGWHEEL else neighborType
        return when {
            my == GearType.COGWHEEL       && nb == GearType.COGWHEEL       -> perpendicularOffsets(axis)
            my == GearType.COGWHEEL       && nb == GearType.LARGE_COGWHEEL -> diagonalOffsets(axis)
            my == GearType.LARGE_COGWHEEL && nb == GearType.COGWHEEL       -> diagonalOffsets(axis)
            else -> emptyList()
        }
    }

    // Bevel candidates: each entry is (offset → expected neighbor axis) for cross-axis LARGE_COGWHEEL meshing
    private fun bevelCandidates(axis: AxleAxis): List<Pair<Triple<Int,Int,Int>, AxleAxis>> = when (axis) {
        AxleAxis.Y -> listOf(
            Triple( 1, 1, 0) to AxleAxis.X, Triple(-1, 1, 0) to AxleAxis.X,
            Triple( 1,-1, 0) to AxleAxis.X, Triple(-1,-1, 0) to AxleAxis.X,
            Triple( 0, 1, 1) to AxleAxis.Z, Triple( 0, 1,-1) to AxleAxis.Z,
            Triple( 0,-1, 1) to AxleAxis.Z, Triple( 0,-1,-1) to AxleAxis.Z
        )
        AxleAxis.X -> listOf(
            Triple( 1, 1, 0) to AxleAxis.Y, Triple(-1, 1, 0) to AxleAxis.Y,
            Triple( 1,-1, 0) to AxleAxis.Y, Triple(-1,-1, 0) to AxleAxis.Y,
            Triple( 1, 0, 1) to AxleAxis.Z, Triple(-1, 0, 1) to AxleAxis.Z,
            Triple( 1, 0,-1) to AxleAxis.Z, Triple(-1, 0,-1) to AxleAxis.Z
        )
        AxleAxis.Z -> listOf(
            Triple( 0, 1, 1) to AxleAxis.Y, Triple( 0,-1, 1) to AxleAxis.Y,
            Triple( 0, 1,-1) to AxleAxis.Y, Triple( 0,-1,-1) to AxleAxis.Y,
            Triple( 1, 0, 1) to AxleAxis.X, Triple(-1, 0, 1) to AxleAxis.X,
            Triple( 1, 0,-1) to AxleAxis.X, Triple(-1, 0,-1) to AxleAxis.X
        )
    }

    // A position is blocked if any LARGE_COGWHEEL with a given axis claims it as part of its footprint
    private fun isBlockedByLargeGear(pos: AxlePos): Boolean {
        for (axis in AxleAxis.values()) {
            for ((dx, dy, dz) in perpendicularOffsets(axis)) {
                val neighborPos = AxlePos(pos.worldName, pos.bx - dx, pos.by - dy, pos.bz - dz)
                val neighbor = gearsByPos[neighborPos] ?: continue
                if (neighbor.gearType == GearType.LARGE_COGWHEEL && neighbor.axis == axis) return true
            }
        }
        return false
    }

    private fun createNetwork(): GearNetwork {
        val net = GearNetwork(nextNetworkId++)
        networks[net.id] = net
        return net
    }

    private fun assignToNetwork(entry: GearEntry, network: GearNetwork, mult: Float) {
        entry.networkId = network.id; entry.speedMultiplier = mult
        network.members[entry.pos] = mult
        if (entry.isMotor) network.motorPositions.add(entry.pos)
    }

    private fun mergeInto(primary: GearNetwork, secondary: GearNetwork, multCorrection: Float) {
        for ((pos, mult) in secondary.members) {
            val corrected = mult * multCorrection
            primary.members[pos] = corrected
            gearsByPos[pos]?.let { it.networkId = primary.id; it.speedMultiplier = corrected }
        }
        primary.motorPositions.addAll(secondary.motorPositions)
        networks.remove(secondary.id)
    }

    // ─── Persistence ─────────────────────────────────────────────────────────

    private fun tagMillstoneState(display: ItemDisplay, ms: MillstoneData) {
        val pdc = display.persistentDataContainer
        pdc.set(pdcMsInputType,  PersistentDataType.STRING, ms.inputItem?.name ?: "")
        pdc.set(pdcMsInputCount, PersistentDataType.INTEGER, ms.inputCount)
        pdc.set(pdcMsProgress,   PersistentDataType.INTEGER, ms.progressTicks)
        val outputStr = ms.outputItems.joinToString(",") { "${it.type.name}:${it.amount}" }
        pdc.set(pdcMsOutput, PersistentDataType.STRING, outputStr)
    }

    private fun tagDisplay(display: ItemDisplay, entry: GearEntry) {
        val pdc = display.persistentDataContainer
        pdc.set(pdcGearType,    PersistentDataType.STRING, entry.gearType.name)
        pdc.set(pdcAxis,        PersistentDataType.STRING, entry.axis.name)
        pdc.set(pdcOrientQ,     PersistentDataType.STRING,
            "${entry.orientQ.x},${entry.orientQ.y},${entry.orientQ.z},${entry.orientQ.w}")
        pdc.set(pdcIsMotor,     PersistentDataType.BOOLEAN, entry.isMotor)
        pdc.set(pdcMotorSpeed,  PersistentDataType.FLOAT, entry.motorSpeed)
        pdc.set(pdcExtraUuids,  PersistentDataType.STRING,
            entry.extraDisplayUuids.joinToString(","))
        pdc.set(pdcBX,          PersistentDataType.INTEGER, entry.pos.bx)
        pdc.set(pdcBY,          PersistentDataType.INTEGER, entry.pos.by)
        pdc.set(pdcBZ,          PersistentDataType.INTEGER, entry.pos.bz)
        pdc.set(pdcWorldName,   PersistentDataType.STRING, entry.pos.worldName)
        if (entry.gearType == GearType.MILLSTONE) {
            tagMillstoneState(display, millstoneData[entry.pos] ?: MillstoneData())
        }
    }

    /** Restores a single gear from the PDC stored on its ItemDisplay entity. */
    private fun restoreDisplay(display: ItemDisplay): Boolean {
        val pdc = display.persistentDataContainer
        val gearTypeName = pdc.get(pdcGearType, PersistentDataType.STRING) ?: return false

        runCatching {
            val gearType   = GearType.valueOf(gearTypeName)
            val axis       = AxleAxis.valueOf(pdc.get(pdcAxis, PersistentDataType.STRING)!!)
            val parts      = pdc.get(pdcOrientQ, PersistentDataType.STRING)!!.split(",")
            val orientQ    = Quaternionf(parts[0].toFloat(), parts[1].toFloat(),
                                         parts[2].toFloat(), parts[3].toFloat())
            val isMotor    = pdc.get(pdcIsMotor, PersistentDataType.BOOLEAN) ?: false
            val motorSpeed = pdc.get(pdcMotorSpeed, PersistentDataType.FLOAT) ?: 0f
            val extraStr   = pdc.get(pdcExtraUuids, PersistentDataType.STRING) ?: ""
            val extraUuids = if (extraStr.isEmpty()) mutableListOf()
                             else extraStr.split(",").map { UUID.fromString(it.trim()) }.toMutableList()
            val bx         = pdc.get(pdcBX, PersistentDataType.INTEGER)!!
            val by         = pdc.get(pdcBY, PersistentDataType.INTEGER)!!
            val bz         = pdc.get(pdcBZ, PersistentDataType.INTEGER)!!
            val worldName  = pdc.get(pdcWorldName, PersistentDataType.STRING)!!

            val pos = AxlePos(worldName, bx, by, bz)
            if (gearsByPos.containsKey(pos)) return false  // already registered — not a new restore

            val entry = GearEntry(
                displayUuid       = display.uniqueId,
                pos               = pos,
                axis              = axis,
                gearType          = gearType,
                orientQ           = orientQ,
                translation       = display.transformation.translation,
                isMotor           = isMotor,
                motorSpeed        = motorSpeed,
                extraDisplayUuids = extraUuids,
                currentDisplayQ   = Quaternionf(display.transformation.leftRotation)
            )

            entry.cachedDisplay = display
            gearsByPos[pos] = entry
            connectGear(entry)

            if (gearType == GearType.MILLSTONE) {
                val ms = MillstoneData()
                val inputTypeName = pdc.get(pdcMsInputType, PersistentDataType.STRING) ?: ""
                if (inputTypeName.isNotEmpty()) {
                    runCatching { ms.inputItem = Material.valueOf(inputTypeName) }
                }
                ms.inputCount = pdc.get(pdcMsInputCount, PersistentDataType.INTEGER) ?: 0
                ms.progressTicks = pdc.get(pdcMsProgress, PersistentDataType.INTEGER) ?: 0
                if (ms.inputItem != null) ms.currentRecipe = MillstoneRecipes.find(ms.inputItem!!)
                val outputStr = pdc.get(pdcMsOutput, PersistentDataType.STRING) ?: ""
                if (outputStr.isNotEmpty()) {
                    outputStr.split(",").forEach { token ->
                        val parts2 = token.split(":")
                        if (parts2.size == 2) runCatching {
                            ms.outputItems.add(org.bukkit.inventory.ItemStack(
                                Material.valueOf(parts2[0]), parts2[1].toInt()))
                        }
                    }
                }
                millstoneData[pos] = ms
            }
        }.onFailure { e ->
            plugin.logger.warning("Failed to restore gear at ${display.location}: ${e.message}")
            return false
        }
        return true
    }

    /** Removes every Create entity and barrier in [worldName]. Returns count of gears removed. */
    fun clearWorld(worldName: String): Int {
        val world = plugin.server.getWorld(worldName)
        var count = 0

        // 1. Detach all belts (removes esteira_fixed displays + gap barriers + belt items)
        val beltsSeen = mutableSetOf<BeltEntry>()
        for ((pos, belt) in beltsByAxle.toMap()) {
            if (pos.worldName != worldName) continue
            if (!beltsSeen.add(belt)) continue
            belt.fixedDisplayUuids.forEach { plugin.server.getEntity(it)?.remove() }
            belt.items.forEach {
                (it.cachedDisplay ?: plugin.server.getEntity(it.displayUuid))?.remove()
            }
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

        // 2. Remove all gear entities and their barrier colliders
        for ((pos, entry) in gearsByPos.toMap()) {
            if (pos.worldName != worldName) continue
            (entry.cachedDisplay ?: plugin.server.getEntity(entry.displayUuid))?.remove()
            entry.extraDisplayUuids.forEach { plugin.server.getEntity(it)?.remove() }
            world?.let { removeColliders(it, pos.bx, pos.by, pos.bz, entry.gearType) }
            count++
        }
        gearsByPos.entries.removeIf      { it.key.worldName == worldName }
        millstoneData.entries.removeIf   { it.key.worldName == worldName }
        networks.entries.removeIf        { (_, net) ->
            net.members.keys.all { it.worldName == worldName }.also { allGone ->
                if (allGone) net.members.clear()
            }
        }

        // 3. Sweep for any stray Create ItemDisplay entities (PDC tag present)
        world?.entities?.forEach { entity ->
            if (entity is ItemDisplay &&
                entity.persistentDataContainer.has(pdcGearType, PersistentDataType.STRING)) {
                entity.remove()
                count++
            }
        }

        return count
    }

    /** Scans loaded chunks on startup (called 1 tick after plugin enable). */
    fun restoreFromWorld() {
        var count = 0
        for (world in plugin.server.worlds)
            for (entity in world.entities)
                if (entity is ItemDisplay &&
                    entity.persistentDataContainer.has(pdcGearType, PersistentDataType.STRING) &&
                    restoreDisplay(entity)) count++
        if (count > 0) plugin.logger.info("Restored $count gear(s) from loaded chunks.")
        // Belt restore is deferred — actual chunk entities arrive via ChunkLoadEvent,
        // so restoreBeltsFromWorld() is called (debounced) from restoreFromChunk() instead.
    }

    private fun restoreBeltsFromWorld() {
        // Single pass: bucket entities by their belt role
        // posA displays  → list of (posA → entity) to reconstruct belts
        // esteira_fixed  → grouped by posA for UUID collection
        // belt items     → grouped by posA to re-attach to restored belts
        data class ItemInfo(val display: ItemDisplay, val stack: org.bukkit.inventory.ItemStack)

        val posADisplays   = mutableMapOf<AxlePos, ItemDisplay>()
        val fixedByPosA    = mutableMapOf<AxlePos, MutableList<UUID>>()
        val itemsByPosA    = mutableMapOf<AxlePos, MutableList<ItemInfo>>()

        for (world in plugin.server.worlds) {
            for (entity in world.entities) {
                if (entity !is ItemDisplay) continue
                val pdc = entity.persistentDataContainer

                // Belt posA gear display — has belt_end_b AND the standard gear PDC keys
                if (pdc.has(pdcBeltEndB, PersistentDataType.STRING)) {
                    val bx = pdc.get(pdcBX, PersistentDataType.INTEGER) ?: continue
                    val by = pdc.get(pdcBY, PersistentDataType.INTEGER) ?: continue
                    val bz = pdc.get(pdcBZ, PersistentDataType.INTEGER) ?: continue
                    val wn = pdc.get(pdcWorldName, PersistentDataType.STRING) ?: continue
                    posADisplays[AxlePos(wn, bx, by, bz)] = entity
                    continue
                }

                // esteira_fixed display — has belt_fixed_posa
                val fixedTag = pdc.get(pdcBeltFixedPosA, PersistentDataType.STRING)
                if (fixedTag != null) {
                    parseAxlePos(fixedTag)?.let { posA ->
                        fixedByPosA.getOrPut(posA) { mutableListOf() }.add(entity.uniqueId)
                    }
                    continue
                }

                // Belt item display — has belt_item_posa + belt_item_stack2
                val itemPosATag = pdc.get(pdcBeltItemPosA,  PersistentDataType.STRING)     ?: continue
                val stackBytes  = pdc.get(pdcBeltItemStack, PersistentDataType.BYTE_ARRAY) ?: continue
                val posA        = parseAxlePos(itemPosATag) ?: continue
                val item = runCatching { org.bukkit.inventory.ItemStack.deserializeBytes(stackBytes) }.getOrNull() ?: continue
                itemsByPosA.getOrPut(posA) { mutableListOf() }
                    .add(ItemInfo(entity, item))
            }
        }

        plugin.logger.info("[Belt] Restore scan: ${posADisplays.size} belt posA(s), " +
            "${fixedByPosA.size} fixed group(s), ${itemsByPosA.size} item group(s)")

        // Re-attach belts
        var beltCount = 0
        for ((posA, dispA) in posADisplays) {
            if (beltsByAxle.containsKey(posA)) continue
            val endBStr = dispA.persistentDataContainer.get(pdcBeltEndB, PersistentDataType.STRING) ?: continue
            val parts   = endBStr.split(",")
            if (parts.size < 3) continue
            runCatching {
                val posB = AxlePos(posA.worldName, parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
                val fixedUuids = fixedByPosA[posA] ?: mutableListOf()
                val ok = restoreBelt(posA, posB, fixedUuids)
                if (ok) beltCount++
                else plugin.logger.warning("[Belt] restoreBelt failed: posA=$posA posB=$posB " +
                    "entryA=${gearsByPos[posA]?.gearType} entryB=${gearsByPos[posB]?.gearType}")
            }.onFailure { e ->
                plugin.logger.warning("[Belt] Exception restoring belt at $posA: ${e.message}")
            }
        }

        // Re-attach belt items to restored belts.
        // Build a set of display UUIDs already tracked in memory so chunk-reload events
        // never inject a duplicate BeltItem for an entity that is already in transit.
        val alreadyTracked = beltsByAxle.values.toSet().flatMapTo(mutableSetOf()) { b -> b.items.map { it.displayUuid } }

        for ((posA, infos) in itemsByPosA) {
            val belt = beltsByAxle[posA] ?: continue
            val world = plugin.server.getWorld(posA.worldName) ?: continue
            val beltPosB = belt.allPositions.last()
            val stepX = when { beltPosB.bx > posA.bx -> 1; beltPosB.bx < posA.bx -> -1; else -> 0 }
            val stepZ = when { beltPosB.bz > posA.bz -> 1; beltPosB.bz < posA.bz -> -1; else -> 0 }
            val dist  = (belt.allPositions.size - 1).toFloat()
            for ((disp, item) in infos) {
                if (disp.uniqueId in alreadyTracked) {
                    plugin.logger.info("[Belt] Skipping restore of item ${disp.uniqueId.toString().takeLast(6)} — already tracked in belt")
                    continue
                }
                val t = disp.transformation.translation
                val bxAnchor = (disp.x - 0.5).toInt()
                val bzAnchor = (disp.z - 0.5).toInt()
                val offsetX = posA.bx - bxAnchor
                val offsetZ = posA.bz - bzAnchor
                val beltPos = ((t.x - offsetX) * stepX + (t.z - offsetZ) * stepZ).coerceIn(0f, dist)
                disp.teleportDuration = 0
                disp.teleport(Location(world, posA.bx + 0.5, posA.by + 0.5, posA.bz + 0.5))
                disp.interpolationDuration = 0
                disp.interpolationDelay    = 0
                disp.transformation = Transformation(
                    Vector3f(beltPos * stepX, BELT_ITEM_Y_OFFSET, beltPos * stepZ),
                    beltItemRotation(stepX, stepZ),
                    Vector3f(BELT_ITEM_SCALE, BELT_ITEM_SCALE, BELT_ITEM_SCALE), IDENTITY_Q
                )
                val beltItem = BeltItem(disp.uniqueId, item, beltPos, posA.bx, posA.bz)
                beltItem.cachedDisplay = disp
                belt.items.add(beltItem)
                alreadyTracked.add(disp.uniqueId)
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

        // Restore esteira_spin on axle displays
        val spinItem = beltSpinItem()
        for (pos in axlePositions) {
            val e = gearsByPos[pos] ?: continue
            (e.cachedDisplay ?: plugin.server.getEntity(e.displayUuid) as? ItemDisplay)
                ?.setItemStack(spinItem)
        }

        // Merge all axle networks
        var primaryNet: GearNetwork? = null
        for (pos in axlePositions) {
            val e = gearsByPos[pos] ?: continue
            val net = networks[e.networkId] ?: continue
            if (primaryNet == null) { primaryNet = net }
            else if (net !== primaryNet) {
                val refMult = gearsByPos[posA]?.speedMultiplier ?: 1f
                val correction = if (e.speedMultiplier != 0f) refMult / e.speedMultiplier else 1f
                mergeInto(primaryNet!!, net, correction)
            }
        }

        val belt = BeltEntry(allPositions, axlePositions, fixedUuids.toMutableList(), primaryNet?.id ?: -1)
        for (pos in allPositions) beltsByAxle[pos] = belt
        return true
    }

    private fun parseAxlePos(s: String): AxlePos? {
        val p = s.split(",")
        if (p.size < 4) return null
        return runCatching { AxlePos(p[0], p[1].toInt(), p[2].toInt(), p[3].toInt()) }.getOrNull()
    }

    /** Scans a newly loaded chunk (called by ChunkLoadEvent). */
    fun restoreFromChunk(chunk: org.bukkit.Chunk) {
        var count = 0
        var needsBeltRestore = false
        for (entity in chunk.entities) {
            if (entity !is ItemDisplay) continue
            val pdc = entity.persistentDataContainer
            if (pdc.has(pdcGearType, PersistentDataType.STRING) && restoreDisplay(entity)) count++

            if (!needsBeltRestore) {
                val posA: AxlePos? = when {
                    pdc.has(pdcBeltEndB, PersistentDataType.STRING) -> {
                        val bx = pdc.get(pdcBX, PersistentDataType.INTEGER)
                        val by = pdc.get(pdcBY, PersistentDataType.INTEGER)
                        val bz = pdc.get(pdcBZ, PersistentDataType.INTEGER)
                        val wn = pdc.get(pdcWorldName, PersistentDataType.STRING)
                        if (bx != null && by != null && bz != null && wn != null) AxlePos(wn, bx, by, bz) else null
                    }
                    else -> {
                        val tag = pdc.get(pdcBeltFixedPosA, PersistentDataType.STRING)
                            ?: pdc.get(pdcBeltItemPosA, PersistentDataType.STRING)
                        tag?.let { parseAxlePos(it) }
                    }
                }
                if (posA != null && !beltsByAxle.containsKey(posA)) needsBeltRestore = true
            }
        }
        if (count > 0) plugin.logger.info("Restored $count gear(s) from chunk (${chunk.x}, ${chunk.z}).")

        if (!needsBeltRestore) return

        // Debounce belt restoration: reset the 20-tick countdown each time a chunk with
        // untracked belt data loads. This ensures adjacent chunks are in memory before
        // we try to re-attach belts. Skipped entirely when all belts are already tracked.
        if (pendingBeltRestoreTaskId != -1)
            plugin.server.scheduler.cancelTask(pendingBeltRestoreTaskId)
        pendingBeltRestoreTaskId = plugin.server.scheduler.runTaskLater(plugin, Runnable {
            pendingBeltRestoreTaskId = -1
            restoreBeltsFromWorld()
        }, 20L).taskId
    }

    // ─── Water wheel ─────────────────────────────────────────────────────────

    private fun updateWaterWheelSpeeds() {
        for ((pos, entry) in gearsByPos) {
            if (entry.gearType != GearType.WATER_WHEEL) continue
            val world = plugin.server.getWorld(pos.worldName) ?: continue
            entry.motorSpeed = computeWaterWheelDpt(world, pos, entry.axis)
        }
    }

    /**
     * Returns the signed DPT (degrees/tick) for a water wheel.
     * Sign encodes rotation direction; 0 = no net torque.
     *
     * Uses the physical torque formula τ = r × F for each adjacent water block,
     * projected onto the wheel axis:
     *   X-axis: τ_x =  r_y · v_z   (dy encodes sign of r_y; top=+1, bottom=−1)
     *   Z-axis: τ_z = −r_y · v_x   (same dy encoding, negated)
     *   Y-axis: τ_y =  r_z·v_x − r_x·v_z  (both flow components matter)
     *
     * flow level convention: level increases away from source (0=source, 7=far).
     * vx ≈ level(E) − level(W); vz ≈ level(S) − level(N); positive = flows that way.
     *
     * Max per-block contribution: |dy|·7 = 7. Normalise by 7 → full RPM from one
     * fully-flowing paddle. Opposite-side paddles in symmetric flow cancel (correct physics).
     */
    private fun computeWaterWheelDpt(world: World, pos: AxlePos, axis: AxleAxis): Float {
        var score = 0f
        for ((dx, dy, dz) in perpendicularOffsets(axis)) {
            val wx = pos.bx + dx; val wy = pos.by + dy; val wz = pos.bz + dz
            val block = world.getBlockAt(wx, wy, wz)
            if (block.type != Material.WATER) continue
            val lvl = (block.blockData as? org.bukkit.block.data.Levelled)?.level ?: continue
            if (lvl == 0 || lvl >= 8) continue  // source or falling → no horizontal flow
            score += when (axis) {
                // τ_x = r_y · v_z ; dy is ±1 for top/bottom, 0 for N/S sides
                AxleAxis.X -> dy * (wLvl(world, wx, wy, wz + 1) - wLvl(world, wx, wy, wz - 1)).toFloat()
                // τ_z = −r_y · v_x ; same dy encoding, negated
                AxleAxis.Z -> -dy * (wLvl(world, wx + 1, wy, wz) - wLvl(world, wx - 1, wy, wz)).toFloat()
                // τ_y = r_z · v_x − r_x · v_z
                AxleAxis.Y -> {
                    val vx = (wLvl(world, wx + 1, wy, wz) - wLvl(world, wx - 1, wy, wz)).toFloat()
                    val vz = (wLvl(world, wx, wy, wz + 1) - wLvl(world, wx, wy, wz - 1)).toFloat()
                    dz * vx - dx * vz
                }
            }
        }
        // Normalise: max practical single-paddle contribution is 7 (one fully-flowing block).
        // Clamp to [-1,1] in case multiple paddles add up, then scale to target RPM.
        val normalized = (score / 7f).coerceIn(-1f, 1f)
        return normalized * WATER_WHEEL_RPM * 360f / (20f * 60f)
    }

    /**
     * Water level at (x,y,z) for flow-gradient calculation.
     *   non-water  → 8  (sentinel: "fully downstream / absent")
     *   falling (8)→ 0  (falling water acts as a local source for horizontal spread)
     *   source  (0)→ 0
     *   flowing 1–7→ as-is
     *
     * Without this, a falling-water neighbor is indistinguishable from dry air,
     * which inverts the gradient and reverses the detected flow direction.
     */
    private fun wLvl(world: World, x: Int, y: Int, z: Int): Int {
        val b = world.getBlockAt(x, y, z)
        if (b.type != Material.WATER) return 8
        val lvl = (b.blockData as? org.bukkit.block.data.Levelled)?.level ?: 8
        return if (lvl >= 8) 0 else lvl   // falling water (8) = local source = 0
    }

    // ─── Tick ────────────────────────────────────────────────────────────────

    private fun tick() {
        tickCount++
        if (tickCount % 20 == 0) updateWaterWheelSpeeds()
        tickMillstones()
        tickBelts()

        val dead = mutableListOf<AxlePos>()
        for ((_, entry) in gearsByPos) {
            val display = entry.cachedDisplay
            // isDead() = entity actually removed; !isValid() would also fire on chunk unload,
            // causing a restore loop when the chunk reloads.
            if (display == null || display.isDead()) dead.add(entry.pos)
        }
        dead.forEach { pos ->
            detachBelt(pos, clearPersistence = false)  // clean up in-memory state; keep PDC so belt can be restored on reload
            val e = gearsByPos.remove(pos) ?: return@forEach
            e.extraDisplayUuids.forEach { plugin.server.getEntity(it)?.remove() }
            millstoneData.remove(pos)
            plugin.server.getWorld(pos.worldName)?.let { w ->
                removeColliders(w, pos.bx, pos.by, pos.bz, e.gearType)
            }
            networks[e.networkId]?.let { net ->
                net.members.remove(pos); net.motorPositions.remove(pos)
                if (net.members.isEmpty()) networks.remove(e.networkId)
            }
        }

        for ((_, net) in networks) {
            if (net.motorPositions.isEmpty()) continue
            if (--net.ticksLeft > 0) continue

            val baseDpt = networkEffectiveDpt(net)
            net.lastBaseDpt = baseDpt
            if (baseDpt == 0f) continue
            val stepTicks = computeStepTicks(baseDpt, net)
            net.ticksLeft = stepTicks
            net.angle = ((net.angle + baseDpt * stepTicks) % 360f + 360f) % 360f

            // Delta quaternion for this step: a small Y-axis rotation.
            // Each gear multiplies its own currentDisplayQ by this delta scaled
            // by its speedMultiplier, keeping consecutive quaternions always in
            // the same hemisphere (dot = cos(Δ/2) > 0 for |Δ| < 180°).
            val baseStepAngle = baseDpt * stepTicks   // degrees the reference gear advances

            // Performance: many gears in a straight chain share the same speedMultiplier.
            // Compute axisAngle once per unique multiplier and reuse — avoids N trig
            // calls for a long axle run (sin/cos per call is the expensive part).
            val deltaQByMult = HashMap<Float, Quaternionf>(4)

            for (pos in net.members.keys) {
                val entry = gearsByPos[pos] ?: continue
                // Fix 1: use cached reference; fall back to getEntity() only on cache miss
                val display = entry.cachedDisplay?.takeIf { it.isValid }
                    ?: (plugin.server.getEntity(entry.displayUuid) as? ItemDisplay)
                        ?.also { entry.cachedDisplay = it }
                    ?: continue

                val deltaQ = deltaQByMult.getOrPut(entry.speedMultiplier) {
                    RotationUtil.axisAngle(0f, 1f, 0f, baseStepAngle * entry.speedMultiplier)
                }
                val newQ = Quaternionf(entry.currentDisplayQ).mul(deltaQ).normalize()
                entry.currentDisplayQ = Quaternionf(newQ)

                // Fix 2: scale and rightRotation are always SCALE/IDENTITY_Q — no getTransformation() needed
                display.transformation = Transformation(entry.translation, newQ, SCALE, IDENTITY_Q)
                // Fix 3: only call setInterpolationDuration when the value actually changes
                if (entry.lastInterpolationDuration != stepTicks) {
                    display.interpolationDuration = stepTicks
                    entry.lastInterpolationDuration = stepTicks
                }
                display.interpolationDelay = 0
            }
        }
    }

    private fun tickMillstones() {
        for ((pos, ms) in millstoneData) {
            val entry = gearsByPos[pos] ?: continue

            // Hopper I/O: every 8 ticks (matches vanilla hopper transfer rate)
            if (tickCount % 8 == 0) tickMillstoneHoppers(pos, ms, entry)

            // ── Processing (requires network power) ──────────────────────────
            val recipe = ms.currentRecipe ?: continue
            if (ms.inputCount <= 0) continue
            if (ms.outputItems.sumOf { it.amount } >= MillstoneData.MAX_OUTPUT_STACKS * recipe.output.maxStackSize) continue

            val network = networks[entry.networkId] ?: continue
            val baseDpt = networkEffectiveDpt(network)
            if (baseDpt == 0f) continue
            val rpm = baseDpt * entry.speedMultiplier * (20f * 60f) / 360f

            ms.progressTicks += ms.processingSpeed(rpm)

            // Particle: spray item fragments while processing (every 4 ticks)
            if (tickCount % 4 == 0) {
                val mat = ms.inputItem ?: continue
                val world = plugin.server.getWorld(pos.worldName) ?: continue
                val pLoc = Location(world, pos.bx + 0.5, pos.by + 0.6, pos.bz + 0.5)
                world.spawnParticle(
                    org.bukkit.Particle.ITEM,
                    pLoc,
                    6,
                    0.25, 0.15, 0.25,
                    0.08,
                    org.bukkit.inventory.ItemStack(mat)
                )
            }

            if (ms.progressTicks >= recipe.processingTime) {
                ms.progressTicks -= recipe.processingTime
                ms.inputCount -= 1
                if (ms.inputCount <= 0) {
                    ms.inputItem = null
                    ms.currentRecipe = null
                    ms.progressTicks = 0
                }
                val output = ms.outputItems.find { it.type == recipe.output && it.amount < it.maxStackSize }
                if (output != null) output.amount += recipe.outputCount
                else ms.outputItems.add(org.bukkit.inventory.ItemStack(recipe.output, recipe.outputCount))
                entry.cachedDisplay?.let { tagMillstoneState(it, ms) }
            }
        }
    }

    /**
     * Transfers items between adjacent hoppers and this millstone.
     * Called every 8 ticks — matches the vanilla hopper transfer rate (1 item / 8 ticks).
     *
     * Input: hoppers pointing TOWARD the millstone (any of the 5 non-bottom faces).
     *   e.g. hopper above facing DOWN, hopper to the East facing WEST.
     *
     * Output: hopper directly BELOW the millstone (pulls from output regardless of spin).
     *   One item per call; if hopper inventory is full the item is kept in output.
     *
     * No watchlist is needed: we already iterate only the set of existing millstones,
     * so checking their 6 adjacent blocks is O(millstones × 6) — already optimal.
     */
    private fun tickMillstoneHoppers(pos: AxlePos, ms: MillstoneData, entry: GearEntry) {
        val world = plugin.server.getWorld(pos.worldName) ?: return
        var stateChanged = false

        // ── Input: hoppers whose output nozzle points at this millstone ──────
        // Each pair: (offset from millstone → hopper position, required facing of that hopper)
        val inputCandidates = listOf(
            Triple( 0, 1, 0) to org.bukkit.block.BlockFace.DOWN,   // above  → faces DOWN
            Triple( 0, 0, 1) to org.bukkit.block.BlockFace.NORTH,  // south  → faces NORTH
            Triple( 0, 0,-1) to org.bukkit.block.BlockFace.SOUTH,  // north  → faces SOUTH
            Triple( 1, 0, 0) to org.bukkit.block.BlockFace.WEST,   // east   → faces WEST
            Triple(-1, 0, 0) to org.bukkit.block.BlockFace.EAST    // west   → faces EAST
        )
        for ((offset, requiredFacing) in inputCandidates) {
            val (dx, dy, dz) = offset
            val block = world.getBlockAt(pos.bx + dx, pos.by + dy, pos.bz + dz)
            if (block.type != Material.HOPPER) continue
            val hopperData = block.blockData as? org.bukkit.block.data.type.Hopper ?: continue
            if (hopperData.facing != requiredFacing) continue

            val hopperState = block.state as? org.bukkit.block.Hopper ?: continue
            val inv = hopperState.inventory
            for (i in 0 until inv.size) {
                val item = inv.getItem(i) ?: continue
                if (item.type.isAir) continue
                // Try to push exactly 1 item
                val temp = org.bukkit.inventory.ItemStack(item.type, 1)
                if (ms.addInput(temp)) {
                    item.amount -= 1
                    inv.setItem(i, if (item.amount <= 0) null else item)
                    // No update() here: inventory.setItem() writes directly to the live
                    // tile entity. Calling update() would overwrite with the stale snapshot.
                    stateChanged = true
                }
                break  // one item per hopper per transfer tick
            }
        }

        // ── Output: hopper directly below pulls one item from output ─────────
        val below = world.getBlockAt(pos.bx, pos.by - 1, pos.bz)
        if (below.type == Material.HOPPER) {
            val hopperState = below.state as? org.bukkit.block.Hopper
            if (hopperState != null && ms.outputItems.isNotEmpty()) {
                // Peek: try to add 1 item — only consume from our output if it fits
                val peek = ms.outputItems.first()
                val candidate = org.bukkit.inventory.ItemStack(peek.type, 1)
                val leftover = hopperState.inventory.addItem(candidate)
                if (leftover.isEmpty()) {
                    ms.takeOneFromOutput()   // mirrors what we peeked
                    // No update() here: inventory.setItem() writes directly to the live
                    // tile entity. Calling update() would overwrite with the stale snapshot.
                    stateChanged = true
                }
            }
        }

        if (stateChanged) entry.cachedDisplay?.let { tagMillstoneState(it, ms) }
    }

    /**
     * Half-tooth-pitch offset for the newly placed [entry] based on its first lateral
     * (non-axial) connection. This aligns the placed gear's teeth into the gaps of its
     * neighbour instead of colliding.
     *
     * Tooth counts:  small (COGWHEEL / MILLSTONE) = 8 teeth → half-pitch = 360/16 = 22.5°
     *                large (LARGE_COGWHEEL)        = 16 teeth → half-pitch = 360/32 = 11.25°
     *
     * Applied to the PLACED gear only; offset is constant regardless of net.angle because
     * the ±speedMultiplier terms in the meshing invariant cancel exactly.
     *
     * Axle connections (same-axis, same-direction) need no offset.
     */
    private fun computeMeshOffset(entry: GearEntry): Float {
        val connections = findNeighborConnections(entry.pos, entry.axis, entry.gearType)
        for ((_, isAxial) in connections) {
            if (!isAxial) {
                // Any lateral connection determines the offset from the PLACED gear's tooth count
                return when (entry.gearType) {
                    GearType.LARGE_COGWHEEL -> 360f / 32f   // 16 teeth → half-pitch = 11.25°
                    else                    -> 360f / 16f   // 8 teeth  → half-pitch = 22.5°
                }
            }
        }
        return 0f   // isolated gear or axle-only connections
    }

    /**
     * BFS outward from [startEntry] through every axial connection in the same network,
     * copying [currentDisplayQ] and snapping the ItemDisplay for gears that just
     * rejoined the network after being stopped while disconnected.
     *
     * [skipPositions] — the set of positions that were already in the active (spinning)
     * network before the merge.  They are skipped entirely so we never interrupt an
     * ongoing interpolation on a gear that was already correct.
     *
     * Only axial connections are followed: gears connected laterally (opposite sign /
     * different ratio) have a different orientQ and need different treatment.
     */
    private fun resyncAxialChain(startEntry: GearEntry, skipPositions: Set<AxlePos> = emptySet()) {
        // Pre-seed visited with skip set so the BFS never enters the active side
        val visited = mutableSetOf<AxlePos>(startEntry.pos)
        visited.addAll(skipPositions)
        val queue = ArrayDeque<GearEntry>()
        queue.add(startEntry)

        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for ((nPos, isAxial) in findNeighborConnections(cur.pos, cur.axis, cur.gearType)) {
                if (!isAxial || nPos in visited) continue
                val neighbor = gearsByPos[nPos] ?: continue
                if (neighbor.networkId != startEntry.networkId) continue
                visited.add(nPos)

                // Copy Q from the current node — all axial members share the same
                // orientQ and speedMultiplier, so this is an exact match.
                neighbor.currentDisplayQ = Quaternionf(cur.currentDisplayQ)
                (plugin.server.getEntity(neighbor.displayUuid) as? ItemDisplay)?.let { display ->
                    val t = display.transformation
                    display.transformation = Transformation(
                        neighbor.translation,
                        Quaternionf(cur.currentDisplayQ),
                        t.scale,
                        t.rightRotation
                    )
                    display.interpolationDuration = 0
                    display.interpolationDelay    = 0
                }

                queue.add(neighbor)
            }
        }
    }

    private fun computeTotalQ(orientQ: Quaternionf, angle: Float): Quaternionf =
        Quaternionf(orientQ).mul(RotationUtil.axisAngle(0f, 1f, 0f, angle))

    private fun gearItem(type: GearType): ItemStack {
        val modelKey = when (type) {
            GearType.COGWHEEL       -> NamespacedKey("ssggearmachine", "gear")
            GearType.LARGE_COGWHEEL -> NamespacedKey("ssggearmachine", "biggear")
            GearType.AXLE           -> NamespacedKey("ssggearmachine", "eixo")
            GearType.MOTOR          -> NamespacedKey("ssggearmachine", "motor")
            GearType.WATER_WHEEL    -> NamespacedKey("ssggearmachine", "parts/water_wheel_spin")
            GearType.MILLSTONE      -> NamespacedKey("ssggearmachine", "parts/millstone_spin")
        }
        val stack = ItemStack(Material.STICK)
        val meta: ItemMeta = stack.itemMeta ?: return stack
        meta.setItemModel(modelKey)
        stack.itemMeta = meta
        return stack
    }

    private fun beltSpinItem(): ItemStack {
        val stack = ItemStack(Material.STICK)
        val meta: ItemMeta = stack.itemMeta ?: return stack
        meta.setItemModel(NamespacedKey("ssggearmachine", "parts/esteira_spin"))
        stack.itemMeta = meta
        return stack
    }

    private fun beltFixedItem(): ItemStack {
        val stack = ItemStack(Material.STICK)
        val meta: ItemMeta = stack.itemMeta ?: return stack
        meta.setItemModel(NamespacedKey("ssggearmachine", "parts/esteira_fixed"))
        stack.itemMeta = meta
        return stack
    }

    private fun millstoneFixedItem(): ItemStack {
        val stack = ItemStack(Material.STICK)
        val meta: ItemMeta = stack.itemMeta ?: return stack
        meta.setItemModel(NamespacedKey("ssggearmachine", "parts/millstone_fixed"))
        stack.itemMeta = meta
        return stack
    }

    private fun waterWheelFixoItem(): ItemStack {
        val stack = ItemStack(Material.STICK)
        val meta: ItemMeta = stack.itemMeta ?: return stack
        meta.setItemModel(NamespacedKey("ssggearmachine", "parts/water_wheel_fixed"))
        stack.itemMeta = meta
        return stack
    }

    private fun gearDropItem(type: GearType): ItemStack {
        val (modelId, displayName) = when (type) {
            GearType.COGWHEEL       -> "gear"        to "Cogwheel"
            GearType.LARGE_COGWHEEL -> "biggear"     to "Large Cogwheel"
            GearType.AXLE           -> "eixo"        to "Axle"
            GearType.MOTOR          -> "motor"       to "Motor"
            GearType.WATER_WHEEL    -> "water_wheel" to "Water Wheel"
            GearType.MILLSTONE      -> "millstone"   to "Millstone"
        }
        val stack = ItemStack(Material.STICK)
        val meta: ItemMeta = stack.itemMeta ?: return stack
        meta.setItemModel(NamespacedKey("ssggearmachine", modelId))
        meta.setDisplayName("§r$displayName")
        stack.itemMeta = meta
        return stack
    }

    private fun placeColliders(world: World, bx: Int, by: Int, bz: Int, gearType: GearType) {
        for ((dx, dy, dz) in colliderOffsets[gearType] ?: return) {
            world.getBlockAt(bx + dx, by + dy, bz + dz).type = Material.BARRIER
        }
    }

    private fun removeColliders(world: World, bx: Int, by: Int, bz: Int, gearType: GearType) {
        for ((dx, dy, dz) in colliderOffsets[gearType] ?: return) {
            val block = world.getBlockAt(bx + dx, by + dy, bz + dz)
            if (block.type == Material.BARRIER) block.type = Material.AIR
        }
    }

}
