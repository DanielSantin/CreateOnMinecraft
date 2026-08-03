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

    /**
     * Direction reported to ALIGNED funels the last time it was checked. Null forces a
     * resync on the next tick (used when a funel is (re)placed). Lets [BeltManager] only
     * touch funel displays on an actual direction change instead of on a fixed cadence.
     */
    var lastForward: Boolean? = null
}
