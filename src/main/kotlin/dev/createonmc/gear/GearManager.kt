package dev.createonmc.gear

import dev.createonmc.CreateOnMinecraftPlugin
import dev.createonmc.axle.AxleAxis
import dev.createonmc.axle.AxlePos
import dev.createonmc.util.RotationUtil
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.entity.Interaction
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
        private const val MAX_STEP_TICKS = 4
        private const val MAX_STEP_ANGLE = 90f
        const val WATER_WHEEL_RPM = 16f
    }

    // Barrier block offsets relative to gear position, per gear type (generic collision shapes)
    private val colliderOffsets: Map<GearType, List<Triple<Int,Int,Int>>> = mapOf(
        GearType.WATER_WHEEL to listOf(Triple(0, 0, 0)),
        GearType.MILLSTONE   to listOf(Triple(0, 0, 0))
    )

    private val gearsByPos = mutableMapOf<AxlePos, GearEntry>()
    private val interactionToPos = mutableMapOf<UUID, AxlePos>()
    val networks = mutableMapOf<Int, GearNetwork>()
    val millstoneData = mutableMapOf<AxlePos, MillstoneData>()
    private var nextNetworkId = 0
    private var tickCount = 0

    // ─── PDC keys (stored on the Interaction entity for persistence) ─────────
    private val pdcGearType    = NamespacedKey(plugin, "gear_type")
    private val pdcAxis        = NamespacedKey(plugin, "axis")
    private val pdcOrientQ     = NamespacedKey(plugin, "orient_q")     // "x,y,z,w"
    private val pdcIsMotor     = NamespacedKey(plugin, "is_motor")
    private val pdcMotorSpeed  = NamespacedKey(plugin, "motor_speed")
    private val pdcDisplayUuid = NamespacedKey(plugin, "display_uuid")
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

        val (iWidth, iHeight) = interactionDimensions(axis, gearType)
        val iLoc = Location(world, bx + 0.5, by + 0.5 - iHeight / 2.0, bz + 0.5)
        val interaction = world.spawn(iLoc, Interaction::class.java) { entity ->
            entity.interactionWidth = iWidth
            entity.interactionHeight = iHeight
        }

        val entry = GearEntry(
            displayUuid = display.uniqueId,
            interactionUuid = interaction.uniqueId,
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
        gearsByPos[pos] = entry
        interactionToPos[interaction.uniqueId] = pos
        tagInteraction(interaction, entry)
        if (gearType == GearType.MILLSTONE) millstoneData[pos] = MillstoneData()

        if (!connectGear(entry)) return false

        val net = networks[entry.networkId]
        if (net != null && net.motorPositions.isNotEmpty()) {
            val correctAngle = net.angle * entry.speedMultiplier
            (plugin.server.getEntity(entry.displayUuid) as? ItemDisplay)?.transformation =
                Transformation(entry.translation, computeTotalQ(entry.orientQ, correctAngle),
                    Vector3f(SCALE), Quaternionf(0f, 0f, 0f, 1f))
        }

        return true
    }

    fun removeGear(world: World, bx: Int, by: Int, bz: Int, dropItem: Boolean = false) {
        val pos = AxlePos(world.name, bx, by, bz)
        val entry = gearsByPos.remove(pos) ?: return
        interactionToPos.remove(entry.interactionUuid)
        plugin.server.getEntity(entry.displayUuid)?.remove()
        plugin.server.getEntity(entry.interactionUuid)?.remove()
        entry.extraDisplayUuids.forEach { plugin.server.getEntity(it)?.remove() }
        removeColliders(world, bx, by, bz, entry.gearType)
        // Drop remaining millstone inventory items on removal
        millstoneData.remove(pos)?.let { ms ->
            val dropLoc = Location(world, bx + 0.5, by + 0.8, bz + 0.5)
            ms.inputItem?.let { mat -> if (ms.inputCount > 0) world.dropItemNaturally(dropLoc, org.bukkit.inventory.ItemStack(mat, ms.inputCount)) }
            ms.outputItems.forEach { world.dropItemNaturally(dropLoc, it) }
        }
        if (dropItem) gearDropItem(entry.gearType)?.let {
            world.dropItemNaturally(Location(world, bx + 0.5, by + 0.8, bz + 0.5), it)
        }

        val network = networks[entry.networkId] ?: return
        network.members.remove(pos)
        network.motorPositions.remove(pos)

        if (network.members.isEmpty()) { networks.remove(entry.networkId); return }
        rebuildNetworks(entry.networkId)
    }

    fun hasGear(world: World, bx: Int, by: Int, bz: Int): Boolean =
        gearsByPos.containsKey(AxlePos(world.name, bx, by, bz))

    fun getPosForInteraction(uuid: UUID): AxlePos? = interactionToPos[uuid]

    fun getEntry(pos: AxlePos): GearEntry? = gearsByPos[pos]

    fun getInteractionEntity(pos: AxlePos): org.bukkit.entity.Interaction? {
        val entry = gearsByPos[pos] ?: return null
        return plugin.server.getEntity(entry.interactionUuid) as? org.bukkit.entity.Interaction
    }

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

    // Fastest motor's networkBaseDpt (signed); each gear's visual speed = baseDpt * speedMultiplier
    private fun networkEffectiveDpt(network: GearNetwork): Float {
        var best = 0f
        for (pos in network.motorPositions) {
            val e = gearsByPos[pos] ?: continue
            if (e.speedMultiplier == 0f) continue
            val baseDpt = e.motorSpeed / e.speedMultiplier
            if (kotlin.math.abs(baseDpt) > kotlin.math.abs(best)) best = baseDpt
        }
        return best
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

    fun tagMillstoneState(interaction: Interaction, ms: MillstoneData) {
        val pdc = interaction.persistentDataContainer
        pdc.set(pdcMsInputType,  PersistentDataType.STRING, ms.inputItem?.name ?: "")
        pdc.set(pdcMsInputCount, PersistentDataType.INTEGER, ms.inputCount)
        pdc.set(pdcMsProgress,   PersistentDataType.INTEGER, ms.progressTicks)
        val outputStr = ms.outputItems.joinToString(",") { "${it.type.name}:${it.amount}" }
        pdc.set(pdcMsOutput, PersistentDataType.STRING, outputStr)
    }

    private fun tagInteraction(interaction: Interaction, entry: GearEntry) {
        val pdc = interaction.persistentDataContainer
        pdc.set(pdcGearType,    PersistentDataType.STRING, entry.gearType.name)
        pdc.set(pdcAxis,        PersistentDataType.STRING, entry.axis.name)
        pdc.set(pdcOrientQ,     PersistentDataType.STRING,
            "${entry.orientQ.x},${entry.orientQ.y},${entry.orientQ.z},${entry.orientQ.w}")
        pdc.set(pdcIsMotor,     PersistentDataType.BOOLEAN, entry.isMotor)
        pdc.set(pdcMotorSpeed,  PersistentDataType.FLOAT, entry.motorSpeed)
        pdc.set(pdcDisplayUuid, PersistentDataType.STRING, entry.displayUuid.toString())
        pdc.set(pdcExtraUuids,  PersistentDataType.STRING,
            entry.extraDisplayUuids.joinToString(","))
        pdc.set(pdcBX,          PersistentDataType.INTEGER, entry.pos.bx)
        pdc.set(pdcBY,          PersistentDataType.INTEGER, entry.pos.by)
        pdc.set(pdcBZ,          PersistentDataType.INTEGER, entry.pos.bz)
        pdc.set(pdcWorldName,   PersistentDataType.STRING, entry.pos.worldName)
        // Write blank millstone state for new spawns so the keys exist for restore
        if (entry.gearType == GearType.MILLSTONE) {
            tagMillstoneState(interaction, millstoneData[entry.pos] ?: MillstoneData())
        }
    }

    /** Tenta restaurar uma única entidade Interaction a partir do seu PDC. */
    fun restoreInteraction(interaction: Interaction): Boolean {
        val pdc = interaction.persistentDataContainer
        val gearTypeName = pdc.get(pdcGearType, PersistentDataType.STRING) ?: return false

        runCatching {
            val gearType   = GearType.valueOf(gearTypeName)
            val axis       = AxleAxis.valueOf(pdc.get(pdcAxis, PersistentDataType.STRING)!!)
            val parts      = pdc.get(pdcOrientQ, PersistentDataType.STRING)!!.split(",")
            val orientQ    = Quaternionf(parts[0].toFloat(), parts[1].toFloat(),
                                         parts[2].toFloat(), parts[3].toFloat())
            val isMotor    = pdc.get(pdcIsMotor, PersistentDataType.BOOLEAN) ?: false
            val motorSpeed = pdc.get(pdcMotorSpeed, PersistentDataType.FLOAT) ?: 0f
            val displayId  = UUID.fromString(pdc.get(pdcDisplayUuid, PersistentDataType.STRING)!!)
            val extraStr   = pdc.get(pdcExtraUuids, PersistentDataType.STRING) ?: ""
            val extraUuids = if (extraStr.isEmpty()) mutableListOf()
                             else extraStr.split(",").map { UUID.fromString(it.trim()) }.toMutableList()
            val bx         = pdc.get(pdcBX, PersistentDataType.INTEGER)!!
            val by         = pdc.get(pdcBY, PersistentDataType.INTEGER)!!
            val bz         = pdc.get(pdcBZ, PersistentDataType.INTEGER)!!
            val worldName  = pdc.get(pdcWorldName, PersistentDataType.STRING)!!

            val pos = AxlePos(worldName, bx, by, bz)
            if (gearsByPos.containsKey(pos)) return@runCatching  // já restaurado

            val display = plugin.server.getEntity(displayId) as? ItemDisplay
                ?: run { interaction.remove(); return@runCatching }

            val entry = GearEntry(
                displayUuid       = displayId,
                interactionUuid   = interaction.uniqueId,
                pos               = pos,
                axis              = axis,
                gearType          = gearType,
                orientQ           = orientQ,
                translation       = display.transformation.translation,
                isMotor           = isMotor,
                motorSpeed        = motorSpeed,
                extraDisplayUuids = extraUuids
            )

            gearsByPos[pos] = entry
            interactionToPos[interaction.uniqueId] = pos
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
            plugin.logger.warning("Failed to restore gear at ${interaction.location}: ${e.message}")
            return false
        }
        return true
    }

    /** Varre chunks já carregados (chamado no startup, 1 tick de delay). */
    fun restoreFromWorld() {
        var count = 0
        for (world in plugin.server.worlds)
            for (entity in world.entities)
                if (entity is Interaction && restoreInteraction(entity)) count++
        if (count > 0) plugin.logger.info("Restored $count gear(s) from loaded chunks.")
    }

    /** Varre um chunk recém-carregado (chamado pelo ChunkLoadEvent). */
    fun restoreFromChunk(chunk: org.bukkit.Chunk) {
        var count = 0
        for (entity in chunk.entities)
            if (entity is Interaction && restoreInteraction(entity)) count++
        if (count > 0) plugin.logger.info("Restored $count gear(s) from chunk (${chunk.x}, ${chunk.z}).")
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

        val dead = mutableListOf<AxlePos>()
        for ((_, entry) in gearsByPos) {
            val e = plugin.server.getEntity(entry.displayUuid)
            if (e == null || !e.isValid) dead.add(entry.pos)
        }
        dead.forEach { pos ->
            val e = gearsByPos.remove(pos) ?: return@forEach
            interactionToPos.remove(e.interactionUuid)
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
            if (baseDpt == 0f) continue
            val stepTicks = computeStepTicks(baseDpt, net)
            net.ticksLeft = stepTicks
            net.angle = ((net.angle + baseDpt * stepTicks) % 360f + 360f) % 360f

            for (pos in net.members.keys) {
                val entry = gearsByPos[pos] ?: continue
                val display = plugin.server.getEntity(entry.displayUuid) as? ItemDisplay ?: continue
                val t = display.transformation
                val newQ = computeTotalQ(entry.orientQ, net.angle * entry.speedMultiplier)
                // Quaternions q and −q represent the same rotation, but the client
                // interpolates numerically along the shortest arc. If the new quaternion
                // is in the opposite hemisphere (dot < 0), negate it so the client
                // always takes the short path and never produces a 180° flip.
                val safeQ = if (t.leftRotation.dot(newQ) < 0f)
                    Quaternionf(-newQ.x, -newQ.y, -newQ.z, -newQ.w) else newQ
                display.transformation = Transformation(
                    entry.translation, safeQ, t.scale, t.rightRotation
                )
                display.interpolationDuration = stepTicks
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
                val interaction = plugin.server.getEntity(entry.interactionUuid) as? org.bukkit.entity.Interaction
                if (interaction != null) tagMillstoneState(interaction, ms)
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

        if (stateChanged) {
            val interaction = plugin.server.getEntity(entry.interactionUuid) as? org.bukkit.entity.Interaction
            if (interaction != null) tagMillstoneState(interaction, ms)
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

    private fun gearDropItem(type: GearType): ItemStack? {
        val (modelId, displayName) = when (type) {
            GearType.WATER_WHEEL -> "water_wheel" to "Water Wheel"
            GearType.MILLSTONE   -> "millstone" to "Millstone"
            else -> return null
        }
        val stack = ItemStack(Material.STICK)
        val meta: ItemMeta = stack.itemMeta ?: return null
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

    private fun interactionDimensions(axis: AxleAxis, gearType: GearType): Pair<Float, Float> {
        // Water wheel: barrier block handles all collision; Interaction entity is kept only
        // for UUID mapping — give it a negligible hitbox so it never intercepts block clicks.
        // Water wheel and millstone use barrier blocks for collision;
        // the Interaction entity is kept only for UUID mapping — negligible hitbox.
        if (gearType == GearType.WATER_WHEEL || gearType == GearType.MILLSTONE) return Pair(0.01f, 0.01f)
        val radialSize = when (gearType) {
            GearType.LARGE_COGWHEEL -> 1.8f
            GearType.AXLE           -> 0.5f
            else                    -> 1.0f
        }
        return when (axis) {
            AxleAxis.Y -> Pair(radialSize, 1.0f)
            AxleAxis.X, AxleAxis.Z -> Pair(1.1f, radialSize)
        }
    }
}
