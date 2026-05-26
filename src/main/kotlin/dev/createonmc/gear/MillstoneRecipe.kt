package dev.createonmc.gear

import org.bukkit.Material

data class MillstoneRecipe(
    val input: Material,
    val output: Material,
    val outputCount: Int,
    val processingTime: Int   // ticks (from Create's "processing_time")
)

object MillstoneRecipes {
    val ALL: List<MillstoneRecipe> = listOf(
        // create:milling/cobblestone — cobblestone → gravel, 250 t
        MillstoneRecipe(Material.COBBLESTONE, Material.GRAVEL, 1, 250),
        // stone → gravel (initial recipe), 200 t
        MillstoneRecipe(Material.STONE, Material.GRAVEL, 1, 200)
    )

    fun find(input: Material): MillstoneRecipe? = ALL.find { it.input == input }
}
