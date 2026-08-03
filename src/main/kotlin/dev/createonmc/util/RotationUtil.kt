package dev.createonmc.util

import org.bukkit.block.BlockFace
import org.joml.Quaternionf
import kotlin.math.cos
import kotlin.math.sin

object RotationUtil {

    fun axisAngle(x: Float, y: Float, z: Float, angleDeg: Float): Quaternionf {
        val rad = Math.toRadians(angleDeg.toDouble()).toFloat()
        val s = sin(rad / 2f)
        return Quaternionf(x * s, y * s, z * s, cos(rad / 2f)).normalize()
    }

    /** Rotation for a face-mounted display entity, oriented to face outward from the clicked face. */
    fun fromBlockFace(face: BlockFace): Quaternionf = when (face) {
        BlockFace.SOUTH -> axisAngle(0f, 1f, 0f,   0f)
        BlockFace.EAST  -> axisAngle(0f, 1f, 0f,  90f)
        BlockFace.NORTH -> axisAngle(0f, 1f, 0f, 180f)
        BlockFace.WEST  -> axisAngle(0f, 1f, 0f, 270f)
        BlockFace.UP    -> axisAngle(1f, 0f, 0f, -90f)
        BlockFace.DOWN  -> axisAngle(1f, 0f, 0f,  90f)
        else            -> Quaternionf()
    }
}
