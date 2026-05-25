package dev.createonmc.gear

import dev.createonmc.axle.AxlePos

class GearNetwork(val id: Int) {
    var angle: Float = 0f
    var ticksLeft: Int = 1
    val members = mutableMapOf<AxlePos, Float>()  // pos → speedMultiplier
    val motorPositions = mutableSetOf<AxlePos>()
}
