package dev.createonmc.gear

import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import java.util.UUID

class BeltItem(
    val displayUuid: UUID,
    val item: ItemStack,
    var beltPos: Float,        // 0 = belt start, dist = belt end
    val bxAnchor: Int,         // block X of the display entity's fixed world anchor (never changes)
    val bzAnchor: Int          // block Z of the display entity's fixed world anchor (never changes)
) {
    var cachedDisplay: ItemDisplay? = null
}
