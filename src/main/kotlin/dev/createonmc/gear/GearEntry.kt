package dev.createonmc.gear

import dev.createonmc.axle.AxleAxis
import dev.createonmc.axle.AxlePos
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID

class GearEntry(
    val displayUuid: UUID,
    val interactionUuid: UUID,
    val pos: AxlePos,
    val axis: AxleAxis,
    val gearType: GearType,
    val orientQ: Quaternionf,
    val translation: Vector3f,
    val isMotor: Boolean = false,
    var motorSpeed: Float = 0f,
    var networkId: Int = -1,
    var speedMultiplier: Float = 1.0f,
    val extraDisplayUuids: List<UUID> = emptyList()
)
