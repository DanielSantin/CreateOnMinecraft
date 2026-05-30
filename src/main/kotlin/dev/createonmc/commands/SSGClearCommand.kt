package dev.createonmc.commands

import dev.createonmc.gear.GearManager
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class SSGClearCommand(private val gearManager: GearManager) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        val worldName = when {
            args.isNotEmpty() -> args[0]
            sender is Player  -> sender.world.name
            else -> {
                sender.sendMessage("§cUsage (console): /ssgclear <world>")
                return true
            }
        }
        val count = gearManager.clearWorld(worldName)
        sender.sendMessage("§a[Create] Removed $count Create entities from '$worldName'.")
        return true
    }

    override fun onTabComplete(
        sender: CommandSender, command: Command, label: String, args: Array<String>
    ): List<String> {
        if (args.size != 1) return emptyList()
        return sender.server.worlds.map { it.name }.filter { it.startsWith(args[0]) }
    }
}
