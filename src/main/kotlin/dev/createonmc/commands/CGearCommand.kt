package dev.createonmc.commands

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

class CGearCommand : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Only players can use this command."); return true }

        val type = args.getOrNull(0)?.lowercase() ?: "gear"
        val isAxle = type == "eixo" || type == "axle"
        val isBig  = !isAxle && (type == "big" || type == "biggear" || type == "large" || type == "2")
        val (modelKey, name) = when {
            isAxle -> NamespacedKey("ssggearmachine", "eixo") to "Eixo"
            isBig  -> NamespacedKey("ssggearmachine", "biggear") to "Big Gear"
            else   -> NamespacedKey("ssggearmachine", "gear") to "Gear"
        }

        val stack = ItemStack(Material.STICK)
        val meta = stack.itemMeta!!
        meta.setItemModel(modelKey)
        meta.setDisplayName("§r$name")
        stack.itemMeta = meta
        sender.inventory.addItem(stack)
        sender.sendMessage("Gave you a §f$name§r.")
        return true
    }
}
