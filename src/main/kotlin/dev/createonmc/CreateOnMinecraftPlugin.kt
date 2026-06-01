package dev.createonmc

import dev.createonmc.commands.GearStressCommand
import dev.createonmc.commands.SSGClearCommand
import dev.createonmc.commands.SSGGiveCommand
import dev.createonmc.commands.SSGItemCommand
import dev.createonmc.gear.GearManager
import dev.createonmc.listeners.AxleInteractListener
import dev.createonmc.listeners.BeltBlockListener
import dev.createonmc.listeners.GearChunkListener
import dev.createonmc.listeners.WaterDebugListener
import org.bukkit.plugin.java.JavaPlugin

class CreateOnMinecraftPlugin : JavaPlugin() {

    lateinit var gearManager: GearManager
        private set
    val stressCommand = GearStressCommand()
    val ssgItemCommand = SSGItemCommand()

    override fun onEnable() {
        gearManager = GearManager(this)
        server.pluginManager.registerEvents(AxleInteractListener(gearManager, stressCommand, ssgItemCommand), this)
        server.pluginManager.registerEvents(GearChunkListener(gearManager), this)
        server.pluginManager.registerEvents(BeltBlockListener(gearManager, this), this)
        server.pluginManager.registerEvents(WaterDebugListener(logger), this)
        val ssgGive = SSGGiveCommand()
        getCommand("ssggive")?.setExecutor(ssgGive)
        getCommand("ssggive")?.tabCompleter = ssgGive
        getCommand("gearstress")?.setExecutor(stressCommand)
        getCommand("ssgitem")?.setExecutor(ssgItemCommand)
        val ssgClear = SSGClearCommand(gearManager)
        getCommand("ssgclear")?.setExecutor(ssgClear)
        getCommand("ssgclear")?.tabCompleter = ssgClear
        logger.info("CreateOnMinecraft enabled!")
    }

    override fun onDisable() {
        logger.info("CreateOnMinecraft disabled!")
    }
}
