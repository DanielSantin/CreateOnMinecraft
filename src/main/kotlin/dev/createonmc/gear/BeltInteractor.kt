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

    /** A solid non-barrier block directly above this slot that prevents items from passing through. */
    object Obstacle : BeltInteractor()

    /** Funel in OUT mode: pulls items from the container and puts them on the belt. */
    data class FunelOut(val containerPos: AxlePos) : BeltInteractor()

    /** Funel in IN mode: pulls items from the belt and puts them into the container. */
    data class FunelIn(val containerPos: AxlePos) : BeltInteractor()

    /**
     * Funel in ALIGNED mode: the container is in the same direction as the belt flow.
     * When belt moves toward the container → inserts belt items into the container.
     * When belt moves away from the container → extracts from the container onto the belt.
     * [alignedTowardX]/[alignedTowardZ]: unit vector from the belt slot toward the container.
     */
    data class FunelAuto(
        val containerPos: AxlePos,
        val alignedTowardX: Int,
        val alignedTowardZ: Int
    ) : BeltInteractor()
}
