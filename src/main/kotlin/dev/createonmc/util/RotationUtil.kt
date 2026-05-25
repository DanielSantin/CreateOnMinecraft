package dev.createonmc.util

import org.joml.Quaternionf
import kotlin.math.cos
import kotlin.math.sin

object RotationUtil {

    fun axisAngle(x: Float, y: Float, z: Float, angleDeg: Float): Quaternionf {
        val rad = Math.toRadians(angleDeg.toDouble()).toFloat()
        val s = sin(rad / 2f)
        return Quaternionf(x * s, y * s, z * s, cos(rad / 2f)).normalize()
    }
}
