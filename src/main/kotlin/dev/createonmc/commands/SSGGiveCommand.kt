package dev.createonmc.commands

import dev.createonmc.nexo.NexoCompat
import dev.createonmc.nexo.NexoIds
import org.bukkit.NamespacedKey
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class SSGGiveCommand : CommandExecutor, TabCompleter {

    private val rpmKey = NamespacedKey("createonmc", "motor_rpm")

    private val items = listOf("gear", "biggear", "eixo", "motor", "water_wheel", "millstone", "esteira", "funel")

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Only players can use this command."); return true }

        val type = args.getOrNull(0)?.lowercase()
        if (type == null) {
            sender.sendMessage("Usage: /ssggive <${items.joinToString("|")}>")
            return true
        }

        val stack = when (type) {
            "gear"        -> makeItem(NexoIds.COGWHEEL, "Engrenagem")
            "biggear"     -> makeItem(NexoIds.LARGE_COGWHEEL, "Engrenagem Grande")
            "eixo"        -> makeItem(NexoIds.AXLE, "Eixo")
            "water_wheel" -> makeItem(NexoIds.WATER_WHEEL, "Roda D'Água")
            "millstone"   -> makeItem(NexoIds.MILLSTONE, "Moinho")
            "esteira"     -> makeItem(NexoIds.ESTEIRA, "Esteira")
            "funel"       -> makeItem(NexoIds.FUNEL, "Funil")
            "motor" -> {
                val rpm = args.getOrNull(1)?.toFloatOrNull() ?: 10f
                if (rpm == 0f) { sender.sendMessage("RPM cannot be 0."); return true }
                makeMotor(rpm)
            }
            else -> {
                sender.sendMessage("Unknown item '${args[0]}'. Usage: /ssggive <${items.joinToString("|")}>")
                return true
            }
        }

        sender.inventory.addItem(stack)
        sender.sendMessage("Gave you: ${stack.itemMeta?.displayName ?: type}§r.")
        return true
    }

    override fun onTabComplete(
        sender: CommandSender, command: Command, label: String, args: Array<String>
    ): List<String> {
        return when (args.size) {
            1 -> items.filter { it.startsWith(args[0].lowercase()) }
            2 -> if (args[0].lowercase() == "motor")
                listOf("5", "10", "20", "50", "100").filter { it.startsWith(args[1]) }
            else emptyList()
            else -> emptyList()
        }
    }

    private fun makeItem(nexoId: String, displayName: String): ItemStack {
        val stack = NexoCompat.item(nexoId)
        val meta = stack.itemMeta ?: return stack
        meta.setDisplayName("§r$displayName")
        stack.itemMeta = meta
        return stack
    }

    private fun makeMotor(rpm: Float): ItemStack {
        val stack = NexoCompat.item(NexoIds.MOTOR)
        val meta = stack.itemMeta ?: return stack
        meta.setDisplayName("§rMotor §7(${rpm} RPM)")
        meta.persistentDataContainer.set(rpmKey, PersistentDataType.FLOAT, rpm)
        stack.itemMeta = meta
        return stack
    }
}
