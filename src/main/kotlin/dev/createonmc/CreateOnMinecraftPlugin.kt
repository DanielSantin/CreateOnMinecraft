package dev.createonmc

import dev.createonmc.commands.GearStressCommand
import dev.createonmc.commands.SSGClearCommand
import dev.createonmc.commands.SSGGiveCommand
import dev.createonmc.commands.SSGItemCommand
import dev.createonmc.gear.GearManager
import dev.createonmc.gear.MillstoneRecipes
import dev.createonmc.listeners.AxleInteractListener
import dev.createonmc.listeners.BeltBlockListener
import dev.createonmc.listeners.FunelInteractListener
import dev.createonmc.listeners.GearChunkListener
import dev.createonmc.listeners.GearRemoveListener
import dev.createonmc.listeners.WaterDebugListener
import dev.createonmc.nexo.NexoCompat
import dev.createonmc.nexo.NexoLoadListener
import org.bukkit.plugin.java.JavaPlugin

class CreateOnMinecraftPlugin : JavaPlugin() {

    lateinit var gearManager: GearManager
        private set
    val stressCommand = GearStressCommand()
    val ssgItemCommand = SSGItemCommand()

    override fun onEnable() {
        preloadClasses()
        NexoCompat.init(this)
        server.pluginManager.registerEvents(NexoLoadListener(), this)
        MillstoneRecipes.load(this)
        gearManager = GearManager(this)
        server.pluginManager.registerEvents(AxleInteractListener(gearManager, stressCommand, ssgItemCommand), this)
        server.pluginManager.registerEvents(GearChunkListener(gearManager), this)
        server.pluginManager.registerEvents(GearRemoveListener(gearManager), this)
        server.pluginManager.registerEvents(BeltBlockListener(gearManager, this), this)
        server.pluginManager.registerEvents(FunelInteractListener(gearManager, this), this)
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

    /**
     * Under Folia, this plugin's own classes have been observed to occasionally fail to
     * resolve on their FIRST real reference from live gameplay (NoClassDefFoundError /
     * ClassNotFoundException for a class that's demonstrably present in the jar — seen for
     * both BeltItem and MillstoneData in production, always the class's very first use,
     * likely a classloading race under concurrent region threads). Forcing every class to
     * load here, synchronously, on the main thread, before any player interaction or
     * scheduler task runs, means that race — if it happens at all — happens once at boot
     * and is visible in the startup log, instead of silently breaking a random feature
     * hours into a session.
     */
    private fun preloadClasses() {
        val classes = listOf(
            "dev.createonmc.axle.AxleAxis",
            "dev.createonmc.axle.AxlePos",
            "dev.createonmc.commands.GearStressCommand",
            "dev.createonmc.commands.SSGClearCommand",
            "dev.createonmc.commands.SSGGiveCommand",
            "dev.createonmc.commands.SSGItemCommand",
            "dev.createonmc.gear.BeltEntry",
            "dev.createonmc.gear.BeltInteractor",
            "dev.createonmc.gear.BeltItem",
            "dev.createonmc.gear.BeltManager",
            "dev.createonmc.gear.FunelEntry",
            "dev.createonmc.gear.FunelManager",
            "dev.createonmc.gear.FunelState",
            "dev.createonmc.gear.GearEntry",
            "dev.createonmc.gear.GearManager",
            "dev.createonmc.gear.GearNetwork",
            "dev.createonmc.gear.GearNetworkManager",
            "dev.createonmc.gear.GearType",
            "dev.createonmc.gear.MillstoneData",
            "dev.createonmc.gear.MillstoneRecipe",
            "dev.createonmc.gear.MillstoneRecipes",
            "dev.createonmc.gear.MillstoneResult",
            "dev.createonmc.listeners.AxleInteractListener",
            "dev.createonmc.listeners.BeltBlockListener",
            "dev.createonmc.listeners.FunelInteractListener",
            "dev.createonmc.listeners.GearChunkListener",
            "dev.createonmc.listeners.GearRemoveListener",
            "dev.createonmc.listeners.WaterDebugListener",
            "dev.createonmc.nexo.NexoCompat",
            "dev.createonmc.nexo.NexoIds",
            "dev.createonmc.nexo.NexoLoadListener",
            "dev.createonmc.util.AxleUtil",
            "dev.createonmc.util.ItemUtil",
            "dev.createonmc.util.RotationUtil"
        )
        var failed = 0
        for (name in classes) {
            try {
                Class.forName(name, true, javaClass.classLoader)
            } catch (e: Throwable) {
                failed++
                logger.warning("[Preload] Falha ao carregar $name na inicialização: $e")
            }
        }
        if (failed == 0) logger.info("[Preload] Todas as ${classes.size} classes carregadas na inicialização.")
    }
}
