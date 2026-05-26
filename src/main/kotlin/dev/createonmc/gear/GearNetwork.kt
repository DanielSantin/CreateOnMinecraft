package dev.createonmc.gear

import dev.createonmc.axle.AxlePos

class GearNetwork(val id: Int) {
    var angle: Float = 0f
    var ticksLeft: Int = 1
    val members = mutableMapOf<AxlePos, Float>()  // pos → speedMultiplier
    val motorPositions = mutableSetOf<AxlePos>()

    /** Last-computed stress values (updated every step). Read-only for external use. */
    var stressCapacity: Float = 0f
    var stressImpact:   Float = 0f
    val isOverstressed get() = stressImpact > stressCapacity && stressCapacity > 0f
}
