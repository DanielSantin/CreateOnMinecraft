package dev.createonmc.axle

import org.bukkit.util.Vector

enum class AxleAxis {
    X, Y, Z;

    fun unitVector(): Vector = when (this) {
        X -> Vector(1.0, 0.0, 0.0)
        Y -> Vector(0.0, 1.0, 0.0)
        Z -> Vector(0.0, 0.0, 1.0)
    }

    fun positiveOffset(): Triple<Int, Int, Int> = when (this) {
        X -> Triple(1, 0, 0)
        Y -> Triple(0, 1, 0)
        Z -> Triple(0, 0, 1)
    }

    fun negativeOffset(): Triple<Int, Int, Int> = when (this) {
        X -> Triple(-1, 0, 0)
        Y -> Triple(0, -1, 0)
        Z -> Triple(0, 0, -1)
    }
}
