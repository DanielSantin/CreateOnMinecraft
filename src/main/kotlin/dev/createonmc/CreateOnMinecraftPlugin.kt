package dev.createonmc

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
        val ssgGive = SSGGiveCommand()
        getCommand("ssggive")?.setExecutor(ssgGive)
        getCommand("ssggive")?.tabCompleter = ssgGive
        logger.info("CreateOnMinecraft enabled!")
    }

    override fun onDisable() {
        logger.info("CreateOnMinecraft disabled!")
    }
}
