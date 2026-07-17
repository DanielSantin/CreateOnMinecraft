package dev.createonmc.gear

import com.google.gson.JsonParser
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Tag
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.jar.JarFile
import kotlin.random.Random

/** One entry of a recipe's "results" array: item + count, produced with [chance]. */
data class MillstoneResult(
    val item: Material,
    val count: Int = 1,
    val chance: Float = 1f
)

data class MillstoneRecipe(
    val inputs: Set<Material>,
    val results: List<MillstoneResult>,
    val processingTime: Int   // ticks (from Create's "processing_time")
) {
    /** First result is guaranteed in Create's format — used for capacity checks. */
    val primary: MillstoneResult get() = results.first()

    /** Rolls all results for one processed input item. */
    fun rollResults(): List<MillstoneResult> =
        results.filter { it.chance >= 1f || Random.nextFloat() < it.chance }
}

/**
 * Carrega receitas de moagem dos arquivos .json em <dataFolder>/recipes/milling, no formato
 * exato do Create (create:milling): ingredients ("item" ou "tag"), processing_time
 * e results ("id", "count" e "chance" opcionais).
 *
 * No primeiro boot os JSONs empacotados no jar são extraídos para a pasta, e a
 * partir daí o servidor lê só a pasta — dá pra editar/adicionar sem recompilar.
 * Receitas com item desconhecido (ex.: "create:wheat_flour") são puladas com aviso.
 */
object MillstoneRecipes {
    private const val DIR = "recipes/milling"
    private val byInput = mutableMapOf<Material, MillstoneRecipe>()

    fun find(input: Material): MillstoneRecipe? = byInput[input]

    fun load(plugin: JavaPlugin) {
        val dir = File(plugin.dataFolder, DIR)
        if (!dir.exists()) extractDefaults(plugin, dir)

        byInput.clear()
        var loaded = 0
        val files = dir.listFiles { f -> f.isFile && f.extension == "json" } ?: emptyArray()
        for (file in files.sortedBy { it.name }) {
            val recipe = runCatching { parse(file.readText(), plugin, file.name) }
                .onFailure { plugin.logger.warning("Receita ${file.name} inválida: ${it.message}") }
                .getOrNull() ?: continue
            for (mat in recipe.inputs) {
                val prev = byInput.put(mat, recipe)
                if (prev != null) plugin.logger.warning(
                    "Receita ${file.name}: input $mat já tinha receita — a última carregada vence.")
            }
            loaded++
        }
        plugin.logger.info("MillstoneRecipes: $loaded receitas carregadas de ${dir.path}")
    }

    private fun parse(json: String, plugin: JavaPlugin, name: String): MillstoneRecipe? {
        val root = JsonParser.parseString(json).asJsonObject

        val inputs = mutableSetOf<Material>()
        for (ing in root.getAsJsonArray("ingredients").map { it.asJsonObject }) {
            when {
                ing.has("item") -> {
                    val id = ing.get("item").asString
                    val mat = Material.matchMaterial(id)
                        ?: return skip(plugin, name, "ingrediente desconhecido $id")
                    inputs.add(mat)
                }
                ing.has("tag") -> {
                    val id = ing.get("tag").asString
                    val key = NamespacedKey.fromString(id)
                        ?: return skip(plugin, name, "tag inválida $id")
                    val tag = Bukkit.getTag(Tag.REGISTRY_ITEMS, key, Material::class.java)
                        ?: return skip(plugin, name, "tag desconhecida $id")
                    inputs.addAll(tag.values)
                }
                else -> return skip(plugin, name, "ingrediente sem \"item\" nem \"tag\"")
            }
        }
        if (inputs.isEmpty()) return skip(plugin, name, "sem ingredientes")

        val results = root.getAsJsonArray("results").map { it.asJsonObject }.map { r ->
            val id = r.get("id").asString
            val mat = Material.matchMaterial(id)
                ?: return skip(plugin, name, "resultado desconhecido $id")
            MillstoneResult(
                item = mat,
                count = r.get("count")?.asInt ?: 1,
                chance = r.get("chance")?.asFloat ?: 1f
            )
        }
        if (results.isEmpty()) return skip(plugin, name, "sem resultados")

        return MillstoneRecipe(inputs, results, root.get("processing_time")?.asInt ?: 100)
    }

    private fun skip(plugin: JavaPlugin, name: String, reason: String): MillstoneRecipe? {
        plugin.logger.warning("Receita $name pulada: $reason")
        return null
    }

    /** Extrai os JSONs padrão empacotados no jar para a pasta de dados (primeiro boot). */
    private fun extractDefaults(plugin: JavaPlugin, dir: File) {
        dir.mkdirs()
        val jar = File(plugin.javaClass.protectionDomain.codeSource.location.toURI())
        JarFile(jar).use { jf ->
            jf.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith("$DIR/") && it.name.endsWith(".json") }
                .forEach { plugin.saveResource(it.name, false) }
        }
    }
}
