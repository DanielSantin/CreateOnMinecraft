package dev.createonmc.gear

import org.bukkit.Material
import org.bukkit.inventory.ItemStack

/**
 * Runtime processing state for a Millstone.
 * Persisted via PDC on the Interaction entity.
 *
 *  inputItem / inputCount  — items waiting to be processed (one material type only)
 *  outputItems             — processed items ready to be taken
 *  progressTicks           — processing progress toward currentRecipe.processingTime
 *  currentRecipe           — recipe being processed (null if no recipe or input empty)
 */
class MillstoneData {
    var inputItem: Material? = null
    var inputCount: Int = 0
    val outputItems: MutableList<ItemStack> = mutableListOf()
    var progressTicks: Int = 0
    var currentRecipe: MillstoneRecipe? = null

    /** Ticks of progress added per server tick: max(1, abs(rpm)/16) */
    fun processingSpeed(rpm: Float): Int = maxOf(1, (kotlin.math.abs(rpm) / 16f).toInt())

    /** Try to add [stack] to input. Returns true if any items were consumed. */
    fun addInput(stack: ItemStack): Boolean {
        val mat = stack.type
        if (mat == Material.AIR || mat.isAir) return false
        if (MillstoneRecipes.find(mat) == null) return false         // no recipe
        if (inputItem != null && inputItem != mat) return false      // different type
        val space = MAX_INPUT - inputCount
        if (space <= 0) return false
        val take = minOf(space, stack.amount)
        stack.amount -= take
        inputItem = mat
        inputCount += take
        if (currentRecipe == null) currentRecipe = MillstoneRecipes.find(mat)
        return true
    }

    /** Take up to one stack from output. Returns the ItemStack or null. */
    fun takeOutput(): ItemStack? {
        if (outputItems.isEmpty()) return null
        val first = outputItems.first()
        val take = minOf(first.amount, first.maxStackSize)
        return if (take >= first.amount) {
            outputItems.removeFirst()
            first.clone()
        } else {
            val result = first.clone().also { it.amount = take }
            first.amount -= take
            result
        }
    }

    /** Take one item from input (for the player to retrieve). */
    fun takeInput(): ItemStack? {
        val mat = inputItem ?: return null
        if (inputCount <= 0) { inputItem = null; return null }
        val take = minOf(inputCount, ItemStack(mat).maxStackSize)
        inputCount -= take
        if (inputCount <= 0) { inputItem = null; currentRecipe = null; progressTicks = 0 }
        return ItemStack(mat, take)
    }

    companion object {
        const val MAX_INPUT = 64
        const val MAX_OUTPUT_STACKS = 9
    }
}
