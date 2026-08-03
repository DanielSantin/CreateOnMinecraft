package dev.createonmc.nexo

import com.nexomc.nexo.api.NexoItems
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.util.logging.Logger

/**
 * Thin wrapper around the Nexo API so the rest of the plugin never touches [NexoItems]
 * directly. Centralizes two things:
 *
 *  - Building item stacks by id, with a safe fallback (bare STICK + one-time warning)
 *    if the Nexo item config is missing an entry — a misconfigured server should never
 *    NPE the placement code, it should just render a plain stick until fixed.
 *  - Reading an item's Nexo id back off an [ItemStack], used everywhere we used to
 *    compare `itemMeta.itemModel` against a raw NamespacedKey.
 */
object NexoCompat {
    private lateinit var logger: Logger
    private val warnedMissing = mutableSetOf<String>()

    fun init(plugin: JavaPlugin) {
        logger = plugin.logger
    }

    /** Builds the Nexo item registered under [id]. Falls back to a bare stack on miss. */
    fun item(id: String, fallbackMaterial: Material = Material.STICK): ItemStack {
        val builder = NexoItems.optionalItemFromId(id)
        if (builder.isPresent) return builder.get().build()
        if (warnedMissing.add(id)) {
            logger.warning(
                "[Nexo] Item '$id' não está registrado em plugins/Nexo/items — usando um " +
                "item de fallback sem modelo. Veja nexo/items/createonmc.yml no repositório."
            )
        }
        return ItemStack(fallbackMaterial)
    }

    /** The Nexo id of [stack], or null if it isn't a Nexo item at all. */
    fun idOf(stack: ItemStack?): String? = stack?.let { NexoItems.idFromItem(it) }

    /** Logs any [NexoIds.ALL] entries missing from the server's Nexo config. Call once Nexo has finished loading items. */
    fun validateConfigured() {
        val missing = NexoIds.ALL.filterNot { NexoItems.exists(it) }
        if (missing.isEmpty()) {
            logger.info("[Nexo] Todos os ${NexoIds.ALL.size} itens do CreateOnMinecraft estão registrados.")
        } else {
            logger.warning("[Nexo] Itens não registrados em plugins/Nexo/items: ${missing.joinToString(", ")}")
        }
    }
}
