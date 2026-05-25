package dev.createonmc.commands

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class CMotorCommand : CommandExecutor {

    private val rpmKey = NamespacedKey("createonmc", "motor_rpm")

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Only players can use this command."); return true }

        val rpm = args.getOrNull(0)?.toFloatOrNull() ?: 10f
        if (rpm == 0f) { sender.sendMessage("RPM cannot be 0."); return true }

        val stack = ItemStack(Material.STICK)
        val meta = stack.itemMeta!!
        meta.setItemModel(NamespacedKey("ssggearmachine", "motor"))
        meta.setDisplayName("§rMotor §7(${rpm} RPM)")
        meta.persistentDataContainer.set(rpmKey, PersistentDataType.FLOAT, rpm)
        stack.itemMeta = meta

        sender.inventory.addItem(stack)
        sender.sendMessage("Gave you a motor — ${rpm} RPM.")
        return true
    }
}
