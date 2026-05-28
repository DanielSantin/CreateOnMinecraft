package dev.createonmc.gear

import dev.createonmc.axle.AxleAxis
import dev.createonmc.axle.AxlePos
import org.bukkit.entity.ItemDisplay
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID

class GearEntry(
    val displayUuid: UUID,
    val pos: AxlePos,
    val axis: AxleAxis,
    val gearType: GearType,
    val orientQ: Quaternionf,
    val translation: Vector3f,
    val isMotor: Boolean = false,
    var motorSpeed: Float = 0f,
    var networkId: Int = -1,
    var speedMultiplier: Float = 1.0f,
    val extraDisplayUuids: List<UUID> = emptyList(),
    /**
     * The quaternion we actually sent to the ItemDisplay last tick.
     * Used for delta-based rotation: each step we multiply by a small
     * axisAngle delta instead of recomputing from the absolute network angle.
     * This guarantees dot(prev, next) = cos(Δ/2) > 0 for any Δ < 180°,
     * so the client never picks the wrong interpolation arc (180° flip).
     */
    var currentDisplayQ: Quaternionf = Quaternionf()   // identity; overwritten on first tick
) {
    // Cached entity reference — avoids getEntity() lookup every tick.
    // Refreshed on spawn/restore and lazily on cache miss in tick().
    var cachedDisplay: ItemDisplay? = null

    // Last interpolationDuration sent to the display entity.
    // setInterpolationDuration() is only called when this value changes.
    var lastInterpolationDuration: Int = -1
}
