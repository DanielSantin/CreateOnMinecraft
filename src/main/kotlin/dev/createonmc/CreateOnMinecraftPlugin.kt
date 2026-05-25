package dev.createonmc

import dev.createonmc.commands.CGearCommand
import dev.createonmc.commands.CMotorCommand
import dev.createonmc.commands.CSpeedCommand
import dev.createonmc.commands.CTestCommand
import dev.createonmc.commands.SSGGiveCommand
import dev.createonmc.gear.GearManager
import dev.createonmc.listeners.AxleInteractListener
import org.bukkit.plugin.java.JavaPlugin

class CreateOnMinecraftPlugin : JavaPlugin() {

    lateinit var gearManager: GearManager
        private set

    override fun onEnable() {
        gearManager = GearManager(this)
        server.pluginManager.registerEvents(AxleInteractListener(this, gearManager), this)
        getCommand("ctest")?.setExecutor(CTestCommand(this))
        getCommand("cspeed")?.setExecutor(CSpeedCommand(gearManager))
        getCommand("cmotor")?.setExecutor(CMotorCommand())
        getCommand("cgear")?.setExecutor(CGearCommand())
        getCommand("ssggive")?.setExecutor(SSGGiveCommand())
        logger.info("CreateOnMinecraft enabled!")
    }

    override fun onDisable() {
        logger.info("CreateOnMinecraft disabled!")
    }
}
