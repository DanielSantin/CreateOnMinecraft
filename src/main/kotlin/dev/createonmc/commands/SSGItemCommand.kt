package dev.createonmc.commands

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class SSGItemCommand : CommandExecutor {

    val previewModelKey = NamespacedKey("createonmc", "preview_model")

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Player only."); return true }

        val modelId = args.getOrNull(0)
        if (modelId.isNullOrBlank()) {
            sender.sendMessage("§cUso: /ssgitem <model_id>  (ex: biggear, parts/water_wheel_spin)")
            return true
        }

        val stack = ItemStack(Material.STICK)
        val meta = stack.itemMeta!!
        meta.setItemModel(NamespacedKey("ssggearmachine", modelId))
        meta.setDisplayName("§r[Preview] $modelId")
        meta.persistentDataContainer.set(previewModelKey, PersistentDataType.STRING, modelId)
        stack.itemMeta = meta

        sender.inventory.addItem(stack)
        sender.sendMessage("§7[SSGItem] §fssggearmachine:$modelId")
        return true
    }
}
