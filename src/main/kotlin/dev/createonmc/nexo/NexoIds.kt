package dev.createonmc.nexo

import dev.createonmc.gear.GearType

/**
 * IDs used to register CreateOnMinecraft's items in Nexo (a YAML file under plugins/Nexo/items
 * on the server). Every ID here must exist in that config with `material: STICK` — placement in
 * [dev.createonmc.listeners.AxleInteractListener] gates on the held item's material, not
 * just its Nexo id, so a different base material silently breaks placement.
 *
 * None of these items carry a Nexo Block/Furniture mechanic: they are plain custom items.
 * All placement/removal (barrier blocks + ItemDisplay entities) stays in our own code —
 * Nexo is only used as the item registry/model layer, never NexoBlocks.
 */
object NexoIds {
    // ── Held / given items (placeable or hand tools) ────────────────────────
    const val COGWHEEL = "gear"
    const val LARGE_COGWHEEL = "biggear"
    const val AXLE = "eixo"
    const val MOTOR = "motor"
    const val WATER_WHEEL = "water_wheel"
    const val MILLSTONE = "millstone"
    const val ESTEIRA = "esteira"
    const val FUNEL = "funel"

    // ── Display-only items (set on ItemDisplay entities, never held) ────────
    const val WATER_WHEEL_SPIN = "water_wheel_spin"
    const val WATER_WHEEL_FIXED = "water_wheel_fixed"
    const val MILLSTONE_SPIN = "millstone_spin"
    const val MILLSTONE_FIXED = "millstone_fixed"
    const val ESTEIRA_SPIN = "esteira_spin"
    const val ESTEIRA_FIXED = "esteira_fixed"
    const val FUNEL_IN = "funel_in"
    const val FUNEL_OUT = "funel_out"

    /** All IDs that should be present in the Nexo item config; used to validate on startup. */
    val ALL = listOf(
        COGWHEEL, LARGE_COGWHEEL, AXLE, MOTOR, WATER_WHEEL, MILLSTONE, ESTEIRA, FUNEL,
        WATER_WHEEL_SPIN, WATER_WHEEL_FIXED, MILLSTONE_SPIN, MILLSTONE_FIXED,
        ESTEIRA_SPIN, ESTEIRA_FIXED, FUNEL_IN, FUNEL_OUT
    )

    fun idFor(type: GearType): String = when (type) {
        GearType.COGWHEEL -> COGWHEEL
        GearType.LARGE_COGWHEEL -> LARGE_COGWHEEL
        GearType.AXLE -> AXLE
        GearType.MOTOR -> MOTOR
        GearType.WATER_WHEEL -> WATER_WHEEL
        GearType.MILLSTONE -> MILLSTONE
    }

    /** Chat-facing label, since we no longer hardcode display names on the ItemStack. */
    fun labelFor(type: GearType): String = when (type) {
        GearType.COGWHEEL -> "Engrenagem"
        GearType.LARGE_COGWHEEL -> "Engrenagem Grande"
        GearType.AXLE -> "Eixo"
        GearType.MOTOR -> "Motor"
        GearType.WATER_WHEEL -> "Roda D'Água"
        GearType.MILLSTONE -> "Moinho"
    }

    fun gearTypeFromId(id: String?): GearType? = when (id) {
        COGWHEEL -> GearType.COGWHEEL
        LARGE_COGWHEEL -> GearType.LARGE_COGWHEEL
        AXLE -> GearType.AXLE
        MOTOR -> GearType.MOTOR
        WATER_WHEEL -> GearType.WATER_WHEEL
        MILLSTONE -> GearType.MILLSTONE
        else -> null
    }
}
