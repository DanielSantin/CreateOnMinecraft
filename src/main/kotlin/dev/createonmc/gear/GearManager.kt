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
        const val WATER_WHEEL_RPM = 16f

        // ── Stress values (SU per RPM), matching Create mod ──────────────────
        const val STRESS_CAPACITY_WATER_WHEEL = 16f   // SU generated per RPM
        const val STRESS_CAPACITY_MOTOR       = 256f  // SU generated per RPM (creative-style)
        const val STRESS_IMPACT_MILLSTONE     = 4f    // SU consumed per RPM

    }

    // Barrier block offsets relative to gear origin — all types get 1 barrier at (0,0,0).
    // Future multi-block shapes override specific types with additional offsets.
    private val colliderOffsets: Map<GearType, List<Triple<Int,Int,Int>>> =
        GearType.values().associateWith { listOf(Triple(0, 0, 0)) }

    private val gearsByPos = mutableMapOf<AxlePos, GearEntry>()
    val millstoneData = mutableMapOf<AxlePos, MillstoneData>()
    /** Shared maps — passed by reference to sub-managers so mutations are visible everywhere. */
    private val beltsByAxle  = mutableMapOf<AxlePos, BeltEntry>()
    internal val beltBlockPos = mutableMapOf<AxlePos, Pair<BeltEntry, Int>>()
    val funel = FunelManager(plugin, beltBlockPos)
    private val networkMgr = GearNetworkManager(plugin, gearsByPos, beltsByAxle) { w, bx, by, bz -> removeGear(w, bx, by, bz) }
    val networks get() = networkMgr.networks
    val belt = BeltManager(plugin, gearsByPos, networkMgr, beltsByAxle, beltBlockPos,
        onDropFunelsForBelt = { b -> funel.dropFunelsForBelt(b) },
        onGearItem          = { t -> gearItem(t) },
        onTagDisplay        = { d, e -> tagDisplay(d, e) })
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

    // ─── Belt (delegated to BeltManager) ──────────────────────────────────────

    fun hasBeltAt(pos: AxlePos): Boolean = belt.hasBeltAt(pos)
    fun attachBelt(posA: AxlePos, posB: AxlePos): Boolean = belt.attachBelt(posA, posB)
    fun detachBelt(pos: AxlePos, clearPersistence: Boolean = true) = belt.detachBelt(pos, clearPersistence)
    fun addAxleToBelt(world: World, pos: AxlePos): Boolean = belt.addAxleToBelt(world, pos)
    internal fun scanBeltInteractors(world: World, b: BeltEntry) = belt.scanBeltInteractors(world, b)
    internal fun updateInteractorAt(world: World, b: BeltEntry, slotIndex: Int) = belt.updateInteractorAt(world, b, slotIndex)
    private fun tickBelts() = belt.tickBelts(tickCount)

    // ─── Funel management (delegated to FunelManager) ────────────────────────

    fun placeFunel(world: World, containerPos: AxlePos, face: org.bukkit.block.BlockFace): Boolean =
        funel.placeFunel(world, containerPos, face)

    fun toggleFunel(displayUuid: java.util.UUID) = funel.toggleFunel(displayUuid)

    fun removeFunel(displayUuid: java.util.UUID) = funel.removeFunel(displayUuid)

    fun isFunelDisplayEntity(entity: org.bukkit.entity.ItemDisplay): Boolean =
        funel.isFunelDisplayEntity(entity)

    fun findFunelOnContainer(containerPos: AxlePos, face: org.bukkit.block.BlockFace): java.util.UUID? =
        funel.findFunelOnContainer(containerPos, face)

    fun findFunelAtBarrier(barrierPos: AxlePos): java.util.UUID? =
        funel.findFunelAtBarrier(barrierPos)


    // ─── Network logic (delegated to GearNetworkManager) ─────────────────────

    private fun connectGear(entry: GearEntry): Boolean = networkMgr.connect(entry)
    private fun rebuildNetworks(oldId: Int) = networkMgr.rebuild(oldId)
    private fun isBlockedByLargeGear(pos: AxlePos): Boolean = networkMgr.isBlockedByLargeGear(pos)
    private fun networkEffectiveDpt(network: GearNetwork): Float = networkMgr.effectiveDpt(network)
    private fun computeStepTicks(baseDpt: Float, network: GearNetwork): Int = networkMgr.stepTicks(baseDpt, network)
    private fun mergeInto(primary: GearNetwork, secondary: GearNetwork, multCorrection: Float) = networkMgr.mergeInto(primary, secondary, multCorrection)
    private fun assignToNetwork(entry: GearEntry, network: GearNetwork, mult: Float) = networkMgr.assignToNetwork(entry, network, mult)
    private fun createNetwork(): GearNetwork = networkMgr.createNetwork()
    private fun findNeighborConnections(pos: AxlePos, axis: dev.createonmc.axle.AxleAxis, gearType: GearType) = networkMgr.findNeighborConnections(pos, axis, gearType)
    private fun perpendicularOffsets(axis: dev.createonmc.axle.AxleAxis) = networkMgr.perpendicularOffsets(axis)

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

        // 1. Detach all belts
        belt.clearForWorld(worldName)
        beltBlockPos.entries.removeIf { it.key.worldName == worldName }

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
                    pdc.has(belt.pdcBeltEndB, PersistentDataType.STRING) -> {
                        val bx = pdc.get(pdcBX, PersistentDataType.INTEGER)
                        val by = pdc.get(pdcBY, PersistentDataType.INTEGER)
                        val bz = pdc.get(pdcBZ, PersistentDataType.INTEGER)
                        val wn = pdc.get(pdcWorldName, PersistentDataType.STRING)
                        if (bx != null && by != null && bz != null && wn != null) AxlePos(wn, bx, by, bz) else null
                    }
                    else -> {
                        val tag = pdc.get(belt.pdcBeltFixedPosA, PersistentDataType.STRING)
                            ?: pdc.get(belt.pdcBeltItemPosA, PersistentDataType.STRING)
                        tag?.let { AxlePos.parse(it) }
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
            belt.restoreBeltsFromWorld()
            funel.restoreFunels()
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
     * Uses the physical torque formula τ = (r × v)·axis for each perpendicular
     * neighbor, where r is the unit offset to the neighbor and v is the actual
     * fluid flow vector from Paper's FluidData.computeFlowDirection() — the same
     * vector the vanilla engine uses to push entities. This handles falling
     * water, corners and source/flow mixtures natively (the old level-gradient
     * heuristic needed a special case for falling water) and, unlike levels,
     * also measures the VERTICAL flow component, so water falling beside a
     * wall-mounted wheel produces torque too.
     *
     * Still pools / source blocks have flow ≈ 0 → no rotation: only a current
     * drives the wheel. Opposite-side paddles in symmetric flow cancel
     * (correct physics). |v| ≤ 1, so one fully-flowing paddle gives full RPM;
     * clamp keeps multiple paddles from exceeding it.
     */
    private fun computeWaterWheelDpt(world: World, pos: AxlePos, axis: AxleAxis): Float {
        var score = 0f
        for ((dx, dy, dz) in perpendicularOffsets(axis)) {
            val wx = pos.bx + dx; val wy = pos.by + dy; val wz = pos.bz + dz
            // Never sample into an unloaded chunk — getFluidData would sync-load it
            if (!world.isChunkLoaded(wx shr 4, wz shr 4)) continue
            val loc = Location(world, wx.toDouble(), wy.toDouble(), wz.toDouble())
            val fluid = world.getFluidData(loc)
            val type = fluid.fluidType
            if (type != org.bukkit.Fluid.WATER && type != org.bukkit.Fluid.FLOWING_WATER) continue
            val v = fluid.computeFlowDirection(loc)
            score += when (axis) {
                AxleAxis.X -> (dy * v.z - dz * v.y).toFloat()   // (r × v)·x̂
                AxleAxis.Y -> (dz * v.x - dx * v.z).toFloat()   // (r × v)·ŷ
                AxleAxis.Z -> (dx * v.y - dy * v.x).toFloat()   // (r × v)·ẑ
            }
        }
        val normalized = score.coerceIn(-1f, 1f)
        return normalized * WATER_WHEEL_RPM * 360f / (20f * 60f)
    }

    // ─── Tick ────────────────────────────────────────────────────────────────

    /**
     * Called from GearRemoveListener when a gear's ItemDisplay is removed from the
     * world for any cause except chunk unload (/kill, explosion, another plugin…).
     * Cleans up the in-memory state but keeps PDC so the gear/belt can be restored
     * if the entity ever comes back via chunk reload.
     */
    internal fun onDisplayRemoved(display: ItemDisplay) {
        val pdc = display.persistentDataContainer
        if (!pdc.has(pdcGearType, PersistentDataType.STRING)) return
        val worldName = pdc.get(pdcWorldName, PersistentDataType.STRING) ?: return
        val bx = pdc.get(pdcBX, PersistentDataType.INTEGER) ?: return
        val by = pdc.get(pdcBY, PersistentDataType.INTEGER) ?: return
        val bz = pdc.get(pdcBZ, PersistentDataType.INTEGER) ?: return
        val pos = AxlePos(worldName, bx, by, bz)
        val entry = gearsByPos[pos] ?: return
        // Belt/extra displays carry the same PDC; only the tracked main display counts.
        if (entry.displayUuid != display.uniqueId) return

        detachBelt(pos, clearPersistence = false)
        gearsByPos.remove(pos)
        entry.extraDisplayUuids.forEach { plugin.server.getEntity(it)?.remove() }
        millstoneData.remove(pos)
        plugin.server.getWorld(pos.worldName)?.let { w ->
            removeColliders(w, pos.bx, pos.by, pos.bz, entry.gearType)
        }
        networks[entry.networkId]?.let { net ->
            net.members.remove(pos); net.motorPositions.remove(pos)
            if (net.members.isEmpty()) networks.remove(entry.networkId)
        }
    }

    private fun tick() {
        tickCount++
        if (tickCount % 20 == 0) updateWaterWheelSpeeds()
        tickMillstones()
        tickBelts()

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
            if (ms.outputItems.sumOf { it.amount } >= MillstoneData.MAX_OUTPUT_STACKS * recipe.primary.item.maxStackSize) continue

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
                for (result in recipe.rollResults()) {
                    val output = ms.outputItems.find { it.type == result.item && it.amount < it.maxStackSize }
                    if (output != null) output.amount += result.count
                    else ms.outputItems.add(org.bukkit.inventory.ItemStack(result.item, result.count))
                }
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
