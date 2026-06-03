package dev.createonmc.gear

import dev.createonmc.CreateOnMinecraftPlugin
import dev.createonmc.axle.AxleAxis
import dev.createonmc.axle.AxlePos
import org.bukkit.World

/**
 * Owns gear network state and all topology/stress logic.
 *
 * Receives [gearsByPos] and [beltsByAxle] by reference — mutations made by
 * [GearManager] are immediately visible here.
 * [onRemoveGear] is a callback into GearManager used when a gear-lock or
 * motor-conflict forces removal of the offending block.
 */
class GearNetworkManager(
    private val plugin: CreateOnMinecraftPlugin,
    private val gearsByPos: MutableMap<AxlePos, GearEntry>,
    private val beltsByAxle: MutableMap<AxlePos, BeltEntry>,
    private val onRemoveGear: (World, Int, Int, Int) -> Unit
) {
    val networks = mutableMapOf<Int, GearNetwork>()
    private var nextNetworkId = 0

    companion object {
        private const val MAX_STEP_TICKS   = 4
        private const val MAX_STEP_ANGLE   = 90f
        private const val TICKS_PER_MINUTE = 20f * 60f
        const val DPT_TO_RPM               = TICKS_PER_MINUTE / 360f
    }

    // ─── Public API (called via private delegates in GearManager) ────────────

    fun connect(entry: GearEntry): Boolean {
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
                    onRemoveGear(w, entry.pos.bx, entry.pos.by, entry.pos.bz)
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

    fun rebuild(oldId: Int) {
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
                beltsByAxle[cur]?.axlePositions?.forEach { bPos ->
                    if (bPos !in visited && bPos in remaining) queue.add(bPos to mult)
                }
            }
        }
    }

    fun isBlockedByLargeGear(pos: AxlePos): Boolean {
        for (axis in AxleAxis.values()) {
            for ((dx, dy, dz) in perpendicularOffsets(axis)) {
                val neighborPos = AxlePos(pos.worldName, pos.bx - dx, pos.by - dy, pos.bz - dz)
                val neighbor = gearsByPos[neighborPos] ?: continue
                if (neighbor.gearType == GearType.LARGE_COGWHEEL && neighbor.axis == axis) return true
            }
        }
        return false
    }

    fun effectiveDpt(network: GearNetwork): Float {
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

    fun stepTicks(baseDpt: Float, network: GearNetwork): Int {
        val maxMult = network.members.values.maxOfOrNull { kotlin.math.abs(it) } ?: 1.0f
        val maxVisualDpt = kotlin.math.abs(baseDpt) * maxMult
        return if (maxVisualDpt == 0f) MAX_STEP_TICKS
               else (MAX_STEP_ANGLE / maxVisualDpt).toInt().coerceIn(1, MAX_STEP_TICKS)
    }

    fun createNetwork(): GearNetwork {
        val net = GearNetwork(nextNetworkId++)
        networks[net.id] = net
        return net
    }

    fun assignToNetwork(entry: GearEntry, network: GearNetwork, mult: Float) {
        entry.networkId = network.id; entry.speedMultiplier = mult
        network.members[entry.pos] = mult
        if (entry.isMotor) network.motorPositions.add(entry.pos)
    }

    fun mergeInto(primary: GearNetwork, secondary: GearNetwork, multCorrection: Float) {
        for ((pos, mult) in secondary.members) {
            val corrected = mult * multCorrection
            primary.members[pos] = corrected
            gearsByPos[pos]?.let { it.networkId = primary.id; it.speedMultiplier = corrected }
        }
        primary.motorPositions.addAll(secondary.motorPositions)
        networks.remove(secondary.id)
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    private fun checkMotorConflict(network: GearNetwork, bridgePos: AxlePos): Boolean {
        if (network.motorPositions.size < 2) return false
        val baseDpts = network.motorPositions.mapNotNull { pos ->
            val e = gearsByPos[pos] ?: return@mapNotNull null
            if (e.speedMultiplier == 0f) null else e.motorSpeed / e.speedMultiplier
        }
        val hasPositive = baseDpts.any { it > 0 }
        val hasNegative = baseDpts.any { it < 0 }
        if (!hasPositive || !hasNegative) return false

        val w = plugin.server.getWorld(bridgePos.worldName) ?: return false
        plugin.server.broadcastMessage(
            "§c[Create] Motor conflict! Block at (${bridgePos.bx}, ${bridgePos.by}, ${bridgePos.bz}) broke.")
        onRemoveGear(w, bridgePos.bx, bridgePos.by, bridgePos.bz)
        return true
    }

    private fun computeNetworkStress(network: GearNetwork, baseDpt: Float) {
        var capacity = 0f
        var impact   = 0f
        for ((pos, mult) in network.members) {
            val entry = gearsByPos[pos] ?: continue
            val rpm = kotlin.math.abs(baseDpt * mult) * DPT_TO_RPM
            when (entry.gearType) {
                GearType.WATER_WHEEL -> capacity += GearManager.STRESS_CAPACITY_WATER_WHEEL * rpm
                GearType.MOTOR       -> capacity += GearManager.STRESS_CAPACITY_MOTOR       * rpm
                GearType.MILLSTONE   -> impact   += GearManager.STRESS_IMPACT_MILLSTONE     * rpm
                else -> { /* cogwheels, axles: zero stress */ }
            }
        }
        network.stressCapacity = capacity
        network.stressImpact   = impact
    }

    private fun computeMyMult(myEntry: GearEntry, neighbor: GearEntry, isAxial: Boolean): Float {
        val neighborMult = neighbor.speedMultiplier
        return when {
            isAxial -> neighborMult
            myEntry.axis != neighbor.axis -> neighborMult * bevelRatioSign(myEntry.pos, myEntry.axis, neighbor.pos, neighbor.axis)
            else -> neighborMult * lateralRatio(myEntry.gearType, neighbor.gearType)
        }
    }

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

    fun findNeighborConnections(pos: AxlePos, axis: AxleAxis, gearType: GearType): List<Pair<AxlePos, Boolean>> {
        val result = mutableListOf<Pair<AxlePos, Boolean>>()
        val (ax, ay, az) = axis.positiveOffset()
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

    private fun canMeshLaterally(type: GearType) =
        type == GearType.COGWHEEL || type == GearType.LARGE_COGWHEEL || type == GearType.MILLSTONE

    fun perpendicularOffsets(axis: AxleAxis) = when (axis) {
        AxleAxis.Y -> listOf(Triple(1,0,0), Triple(-1,0,0), Triple(0,0,1), Triple(0,0,-1))
        AxleAxis.X -> listOf(Triple(0,1,0), Triple(0,-1,0), Triple(0,0,1), Triple(0,0,-1))
        AxleAxis.Z -> listOf(Triple(1,0,0), Triple(-1,0,0), Triple(0,1,0), Triple(0,-1,0))
    }

    private fun diagonalOffsets(axis: AxleAxis) = when (axis) {
        AxleAxis.Y -> listOf(Triple(1,0,1), Triple(1,0,-1), Triple(-1,0,1), Triple(-1,0,-1))
        AxleAxis.X -> listOf(Triple(0,1,1), Triple(0,1,-1), Triple(0,-1,1), Triple(0,-1,-1))
        AxleAxis.Z -> listOf(Triple(1,1,0), Triple(1,-1,0), Triple(-1,1,0), Triple(-1,-1,0))
    }

    private fun meshingOffsets(axis: AxleAxis, myType: GearType, neighborType: GearType): List<Triple<Int,Int,Int>> {
        val my = if (myType == GearType.MILLSTONE) GearType.COGWHEEL else myType
        val nb = if (neighborType == GearType.MILLSTONE) GearType.COGWHEEL else neighborType
        return when {
            my == GearType.COGWHEEL       && nb == GearType.COGWHEEL       -> perpendicularOffsets(axis)
            my == GearType.COGWHEEL       && nb == GearType.LARGE_COGWHEEL -> diagonalOffsets(axis)
            my == GearType.LARGE_COGWHEEL && nb == GearType.COGWHEEL       -> diagonalOffsets(axis)
            else -> emptyList()
        }
    }

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
}
