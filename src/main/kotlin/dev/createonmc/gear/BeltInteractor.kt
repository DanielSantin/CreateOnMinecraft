package dev.createonmc.gear

import dev.createonmc.axle.AxlePos

/**
 * Describes something attached to a specific belt slot that can push items
 * into the belt or pull items out of it.
 *
 * Adding a new interactor type in the future:
 *   1. Add a subclass here (data class or object).
 *   2. Scan for it in GearManager.updateInteractorAt().
 *   3. Handle it in tickBeltInteractorsInsert() or tryExtractBeltItem() as appropriate.
 */
sealed class BeltInteractor {

    /** A hopper (or future inserter) that pushes items INTO the belt. */
    data class HopperInsert(val hopperPos: AxlePos) : BeltInteractor()

    /** A hopper (or future extractor) below the belt that pulls items OUT. */
    data class HopperExtract(val hopperPos: AxlePos) : BeltInteractor()
}
