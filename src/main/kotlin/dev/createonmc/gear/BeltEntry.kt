package dev.createonmc.gear

import dev.createonmc.axle.AxlePos
import java.util.UUID

class BeltEntry(
    val allPositions: List<AxlePos>,        // ALL positions A→B inclusive
    val axlePositions: MutableSet<AxlePos>, // subset that had axles (converted to esteira_spin)
    val fixedDisplayUuids: List<UUID>,      // esteira_fixed displays for every position
    val mergedNetworkId: Int                // network ID after merging all axle networks
) {
    val items: MutableList<BeltItem> = mutableListOf()

    /** slotIndex → interactors registered at that slot (hoppers, future extractors/inserters). */
    val interactors: MutableMap<Int, MutableList<BeltInteractor>> = mutableMapOf()
}
