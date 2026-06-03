package dev.createonmc.gear

import dev.createonmc.axle.AxlePos
import java.util.UUID

enum class FunelState { OUT, IN, ALIGNED }

class FunelEntry(
    val displayUuid: UUID,
    val containerPos: AxlePos,
    val beltSlotPos: AxlePos,
    val slotIndex: Int,
    var state: FunelState
)
