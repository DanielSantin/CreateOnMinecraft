package dev.createonmc.commands

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID

class GearStressCommand : CommandExecutor {

    // Shared map: player UUID → how many gears to place per click
    val multipliers = mutableMapOf<UUID, Int>()

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Player only."); return true }

        val n = args.getOrNull(0)?.toIntOrNull()
        if (n == null || n < 1) {
            sender.sendMessage("§cUso: /gearstress <n>  (n ≥ 1; use 1 para desativar)")
            return true
        }

        if (n == 1) {
            multipliers.remove(sender.uniqueId)
            sender.sendMessage("§7[GearStress] Desativado.")
        } else {
            multipliers[sender.uniqueId] = n
            sender.sendMessage("§a[GearStress] Cada gear colocada vai spawnar §f$n §agears em linha.")
        }
        return true
    }
}
