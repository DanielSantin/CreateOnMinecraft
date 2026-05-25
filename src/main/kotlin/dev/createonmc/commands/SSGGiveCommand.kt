package dev.createonmc.commands

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class SSGGiveCommand : CommandExecutor {

    private val rpmKey = NamespacedKey("createonmc", "motor_rpm")

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Only players can use this command."); return true }

        val type = args.getOrNull(0)?.lowercase()
        if (type == null) {
            sender.sendMessage("Usage: /ssggive <gear|biggear|eixo|motor [rpm]>")
            return true
        }

        val stack = when (type) {
            "gear"        -> makeItem("gear", "Gear")
            "biggear"     -> makeItem("biggear", "Big Gear")
            "eixo"        -> makeItem("eixo", "Eixo")
            "water_wheel" -> makeItem("water_wheel", "Water Wheel")
            "motor"   -> {
                val rpm = args.getOrNull(1)?.toFloatOrNull() ?: 10f
                if (rpm == 0f) { sender.sendMessage("RPM cannot be 0."); return true }
                makeMotor(rpm)
            }
            else -> {
                sender.sendMessage("Unknown item '${args[0]}'. Usage: /ssggive <gear|biggear|eixo|motor [rpm]|water_wheel>")
                return true
            }
        }

        sender.inventory.addItem(stack)
        sender.sendMessage("Gave you: ${stack.itemMeta?.displayName ?: type}§r.")
        return true
    }

    private fun makeItem(modelId: String, displayName: String): ItemStack {
        val stack = ItemStack(Material.STICK)
        val meta = stack.itemMeta!!
        meta.setItemModel(NamespacedKey("ssggearmachine", modelId))
        meta.setDisplayName("§r$displayName")
        stack.itemMeta = meta
        return stack
    }

    private fun makeMotor(rpm: Float): ItemStack {
        val stack = ItemStack(Material.STICK)
        val meta = stack.itemMeta!!
        meta.setItemModel(NamespacedKey("ssggearmachine", "motor"))
        meta.setDisplayName("§rMotor §7(${rpm} RPM)")
        meta.persistentDataContainer.set(rpmKey, PersistentDataType.FLOAT, rpm)
        stack.itemMeta = meta
        return stack
    }
}
