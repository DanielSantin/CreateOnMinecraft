package dev.createonmc.util

import dev.createonmc.axle.AxleAxis
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.joml.Quaternionf

object AxleUtil {

    // Orientation from the clicked block face — used for stick placement on blocks.
    // The face determines the axle axis: the gear spins around the axis perpendicular to that face.
    fun orientFromFace(face: BlockFace): Pair<Quaternionf, AxleAxis> = when (face) {
        BlockFace.UP, BlockFace.DOWN ->
            Pair(Quaternionf(), AxleAxis.Y)                              // flat disc, Y spin
        BlockFace.NORTH, BlockFace.SOUTH ->
            Pair(RotationUtil.axisAngle(1f, 0f, 0f, 90f), AxleAxis.Z)  // upright N/S, Z spin
        BlockFace.EAST, BlockFace.WEST ->
            Pair(RotationUtil.axisAngle(0f, 0f, 1f, -90f), AxleAxis.X) // upright E/W, X spin
        else -> Pair(Quaternionf(), AxleAxis.Y)
    }

    // Orientation from player look direction — used for commands (/ctest, /cmotor).
    fun orientFromPlayer(player: Player): Pair<Quaternionf, AxleAxis> {
        val pitch = player.location.pitch
        val snappedYaw = snapYawTo90(player.location.yaw)
        return when {
            pitch < -45f -> Pair(Quaternionf(), AxleAxis.Y)
            snappedYaw.toInt() == 0 || snappedYaw.toInt() == 180 ->
                Pair(RotationUtil.axisAngle(1f, 0f, 0f, 90f), AxleAxis.Z)
            else ->
                Pair(RotationUtil.axisAngle(0f, 0f, 1f, -90f), AxleAxis.X)
        }
    }

    fun snapYawTo90(yaw: Float): Float {
        val normalized = ((yaw % 360f) + 360f) % 360f
        return (Math.round(normalized / 90f) * 90 % 360).toFloat()
    }
}
