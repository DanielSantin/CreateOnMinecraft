package dev.createonmc.commands

import dev.createonmc.gear.GearManager
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender

class CSpeedCommand(private val gearManager: GearManager) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        val rpm = args.getOrNull(0)?.toFloatOrNull()
        if (rpm == null || rpm == 0f) {
            sender.sendMessage("Usage: /cspeed <rpm>")
            return true
        }
        gearManager.setSpeed(rpm)
        sender.sendMessage("Gear speed set to ${rpm} RPM")
        return true
    }
}
