package dev.createonmc.util

import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

object ItemUtil {
    /** Removes [amount] from the player's held item — skipped in creative, like vanilla block placement. */
    fun consumeHeld(player: Player, amount: Int = 1) {
        if (player.gameMode == GameMode.CREATIVE) return
        val hand = player.inventory.itemInMainHand
        if (hand.amount <= amount) {
            player.inventory.setItemInMainHand(ItemStack(Material.AIR))
        } else {
            hand.amount -= amount
        }
    }
}
