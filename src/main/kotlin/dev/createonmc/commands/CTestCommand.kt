package dev.createonmc.commands

import dev.createonmc.CreateOnMinecraftPlugin
import dev.createonmc.gear.GearType
import dev.createonmc.util.AxleUtil
import org.bukkit.NamespacedKey
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class CTestCommand(private val plugin: CreateOnMinecraftPlugin) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("Only players can use this command.")
            return true
        }
        if (args.isEmpty() || args[0].lowercase() != "spawn") {
            sender.sendMessage("Usage: /ctest spawn [rpm]")
            return true
        }

        val rpm = args.getOrNull(1)?.toFloatOrNull() ?: 10f
        if (rpm == 0f) {
            sender.sendMessage("RPM cannot be 0.")
            return true
        }

        val loc = sender.location
        val (orientQ, axis) = AxleUtil.orientFromPlayer(sender)
        val gearType = if (sender.inventory.itemInMainHand.itemMeta?.itemModel ==
                           NamespacedKey("ssggearmachine", "biggear"))
            GearType.LARGE_COGWHEEL else GearType.COGWHEEL
        val spawned = plugin.gearManager.spawnGear(sender.world, loc.blockX, loc.blockY, loc.blockZ,
                                                    orientQ, axis, gearType = gearType, isMotor = true, rpm = rpm)

        if (spawned) sender.sendMessage("Spawned creative motor — ${rpm} RPM, axis ${axis.name}")
        else sender.sendMessage("Position already occupied.")
        return true
    }
}
