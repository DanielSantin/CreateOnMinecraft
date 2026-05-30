package dev.createonmc.gear

import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import java.util.UUID

class BeltItem(
    val displayUuid: UUID,
    val item: ItemStack,       // item being transported
    var beltPos: Float         // 0 = posA center, dist = posB center
) {
    var cachedDisplay: ItemDisplay? = null
}
