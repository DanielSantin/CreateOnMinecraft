package dev.createonmc.gear

import dev.createonmc.axle.AxlePos

/**
 * Describes an effect registered on a specific belt slot.
 *
 * Two categories:
 *  - Interactors: actively move items between the belt and an inventory (hoppers, funels).
 *  - Modifiers:   passively alter item movement without transferring items (Obstacle).
 *
 * Adding a new interactor:
 *   1. Add a subclass in the "Interactors" section below.
 *   2. Scan for it in GearManager.updateInteractorAt() (for block-based) or
 *      GearManager.placeFunel() (for entity-based).
 *   3. Handle it in tickBeltInteractorsInsert() or tryExtractBeltItem().
 */
sealed class BeltInteractor {

    // ── Interactors ───────────────────────────────────────────────────────────

    /** Hopper (or future inserter) that pushes items INTO the belt. */
    data class HopperInsert(val hopperPos: AxlePos) : BeltInteractor()

    /** Hopper (or future extractor) below the belt that pulls items OUT. */
    data class HopperExtract(val hopperPos: AxlePos) : BeltInteractor()

    /** Funel in OUT mode: container → belt. */
    data class FunelOut(val containerPos: AxlePos) : BeltInteractor()

    /** Funel in IN mode: belt → container. */
    data class FunelIn(val containerPos: AxlePos) : BeltInteractor()

    /**
     * Funel in ALIGNED mode: container is in the belt travel direction.
     * Belt moving toward container → belt → container (insert).
     * Belt moving away from container → container → belt (extract).
     * [alignedTowardX]/[alignedTowardZ]: unit vector from slot toward container.
     */
    data class FunelAuto(
        val containerPos: AxlePos,
        val alignedTowardX: Int,
        val alignedTowardZ: Int
    ) : BeltInteractor()

    // ── Modifiers ─────────────────────────────────────────────────────────────

    /** Solid non-barrier block above this slot — items cannot enter this slot. */
    object Obstacle : BeltInteractor()
}
