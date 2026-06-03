package dev.createonmc.gear

import dev.createonmc.CreateOnMinecraftPlugin
import dev.createonmc.axle.AxlePos
import dev.createonmc.util.RotationUtil
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.joml.Quaternionf
import java.util.UUID

class FunelManager(
    private val plugin: CreateOnMinecraftPlugin,
    private val beltBlockPos: MutableMap<AxlePos, Pair<BeltEntry, Int>>
) {
    private val funelsByUuid         = mutableMapOf<UUID, FunelEntry>()
    private val funelsByContainerPos = mutableMapOf<AxlePos, UUID>()

    private val pdcContainerPos = NamespacedKey(plugin, "funel_container_pos")
    private val pdcState        = NamespacedKey(plugin, "funel_state")
    private val pdcFace         = NamespacedKey(plugin, "funel_face")

    /** Called by [FunelInteractListener] when a player shift-right-clicks a container with a funel item. */
    fun placeFunel(world: World, containerPos: AxlePos, face: BlockFace): Boolean {
        val searchPositions = listOf(
            AxlePos(world.name, containerPos.bx + face.modX, containerPos.by + face.modY,     containerPos.bz + face.modZ),
            AxlePos(world.name, containerPos.bx + face.modX, containerPos.by + face.modY - 1, containerPos.bz + face.modZ),
            AxlePos(world.name, containerPos.bx + face.modX, containerPos.by + face.modY - 2, containerPos.bz + face.modZ),
            AxlePos(world.name, containerPos.bx + face.modX, containerPos.by + face.modY + 1, containerPos.bz + face.modZ),
        )
        val (belt, slotIndex) = searchPositions.firstNotNullOfOrNull { beltBlockPos[it] } ?: return false
        val beltSlotPos = belt.allPositions[slotIndex]

        val posA = belt.allPositions.first()
        val posB = belt.allPositions.last()
        val stepX = when { posB.bx > posA.bx -> 1; posB.bx < posA.bx -> -1; else -> 0 }
        val stepZ = when { posB.bz > posA.bz -> 1; posB.bz < posA.bz -> -1; else -> 0 }

        val towardX = (containerPos.bx - beltSlotPos.bx).coerceIn(-1, 1)
        val towardZ = (containerPos.bz - beltSlotPos.bz).coerceIn(-1, 1)
        val isAligned = (towardX != 0 || towardZ != 0) &&
            ((towardX == stepX && towardZ == stepZ) || (towardX == -stepX && towardZ == -stepZ))

        val state = if (isAligned) FunelState.ALIGNED else FunelState.OUT

        val loc = org.bukkit.Location(world,
            containerPos.bx + 0.5 + face.modX * 0.501,
            containerPos.by + 0.5 + face.modY * 0.501,
            containerPos.bz + 0.5 + face.modZ * 0.501)
        val display = world.spawn(loc, ItemDisplay::class.java) { e ->
            e.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
            e.interpolationDuration = 0
            e.transformation = org.bukkit.util.Transformation(
                org.joml.Vector3f(), RotationUtil.fromBlockFace(face),
                org.joml.Vector3f(0.75f, 0.75f, 0.75f), Quaternionf())
            e.setItemStack(displayItem(state))
        }
        val posTag = "${containerPos.worldName},${containerPos.bx},${containerPos.by},${containerPos.bz}"
        display.persistentDataContainer.set(pdcContainerPos, PersistentDataType.STRING, posTag)
        display.persistentDataContainer.set(pdcState, PersistentDataType.STRING, state.name)
        display.persistentDataContainer.set(pdcFace, PersistentDataType.STRING, face.name)

        val barrierBlock = world.getBlockAt(containerPos.bx + face.modX, containerPos.by + face.modY, containerPos.bz + face.modZ)
        if (barrierBlock.type.isAir) barrierBlock.type = Material.BARRIER

        val entry = FunelEntry(display.uniqueId, containerPos, beltSlotPos, slotIndex, state, face)
        funelsByUuid[display.uniqueId] = entry
        funelsByContainerPos[containerPos] = display.uniqueId
        addFunelInteractor(belt, slotIndex, containerPos, state, towardX, towardZ)
        return true
    }

    /** Toggles the funel between OUT and IN. Does nothing if state is ALIGNED. */
    fun toggleFunel(displayUuid: UUID) {
        val entry = funelsByUuid[displayUuid] ?: return
        if (entry.state == FunelState.ALIGNED) return

        entry.state = if (entry.state == FunelState.OUT) FunelState.IN else FunelState.OUT

        val display = (plugin.server.getEntity(displayUuid) as? ItemDisplay) ?: return
        display.setItemStack(displayItem(entry.state))
        display.persistentDataContainer.set(pdcState, PersistentDataType.STRING, entry.state.name)

        beltBlockPos[entry.beltSlotPos]?.let { (belt, slotIndex) ->
            belt.interactors[slotIndex]?.removeIf {
                it is BeltInteractor.FunelOut || it is BeltInteractor.FunelIn
            }
            val posA = belt.allPositions.first(); val posB = belt.allPositions.last()
            val towardX = (entry.containerPos.bx - entry.beltSlotPos.bx).coerceIn(-1, 1)
            val towardZ = (entry.containerPos.bz - entry.beltSlotPos.bz).coerceIn(-1, 1)
            addFunelInteractor(belt, slotIndex, entry.containerPos, entry.state, towardX, towardZ)
        }
    }

    /** Removes the funel entity and drops a funel item. */
    fun removeFunel(displayUuid: UUID) {
        val entry = funelsByUuid.remove(displayUuid)
        if (entry != null) {
            funelsByContainerPos.remove(entry.containerPos)
            beltBlockPos[entry.beltSlotPos]?.let { (belt, slotIndex) ->
                belt.interactors[slotIndex]?.removeIf { interactor ->
                    (interactor is BeltInteractor.FunelOut  && interactor.containerPos == entry.containerPos) ||
                    (interactor is BeltInteractor.FunelIn   && interactor.containerPos == entry.containerPos) ||
                    (interactor is BeltInteractor.FunelAuto && interactor.containerPos == entry.containerPos)
                }
                if (belt.interactors[slotIndex]?.isEmpty() == true) belt.interactors.remove(slotIndex)
            }
        }
        val entity = plugin.server.getEntity(displayUuid)
        if (entity != null) {
            val barrierPos = AxlePos(entity.world.name, entity.location.blockX, entity.location.blockY, entity.location.blockZ)
            if (!beltBlockPos.containsKey(barrierPos)) {
                val block = entity.world.getBlockAt(barrierPos.bx, barrierPos.by, barrierPos.bz)
                if (block.type == Material.BARRIER) block.type = Material.AIR
            }
            entity.world.dropItemNaturally(entity.location, giveItem())
            entity.remove()
        }
    }

    fun isFunelDisplayEntity(entity: ItemDisplay): Boolean =
        entity.persistentDataContainer.has(pdcContainerPos, PersistentDataType.STRING)

    /** Finds a funel on a specific face of a container. Returns the display UUID or null. */
    fun findFunelOnContainer(containerPos: AxlePos, face: BlockFace): UUID? =
        findFunelAtBarrier(AxlePos(containerPos.worldName,
            containerPos.bx + face.modX,
            containerPos.by + face.modY,
            containerPos.bz + face.modZ))

    /** Searches for a funel entity at [barrierPos]. The funel entity spawns at containerPos + face*0.501,
     *  so a radius of 0.6 is sufficient. */
    fun findFunelAtBarrier(barrierPos: AxlePos): UUID? {
        val world = plugin.server.getWorld(barrierPos.worldName) ?: return null
        val center = org.bukkit.Location(world, barrierPos.bx + 0.5, barrierPos.by + 0.5, barrierPos.bz + 0.5)
        return world.getNearbyEntities(center, 0.6, 0.6, 0.6)
            .filterIsInstance<ItemDisplay>()
            .firstOrNull { isFunelDisplayEntity(it) }
            ?.uniqueId
    }

    /**
     * Drops all funel entities attached to [belt] as items and removes them from tracking.
     * Called by [GearManager] before clearing a belt's interactors and beltBlockPos entries,
     * so beltBlockPos still contains the belt's slots at the time of this call.
     */
    fun dropFunelsForBelt(belt: BeltEntry) {
        for ((_, interactors) in belt.interactors) {
            for (interactor in interactors) {
                val cPos = when (interactor) {
                    is BeltInteractor.FunelOut  -> interactor.containerPos
                    is BeltInteractor.FunelIn   -> interactor.containerPos
                    is BeltInteractor.FunelAuto -> interactor.containerPos
                    else -> null
                } ?: continue
                val funelUuid = funelsByContainerPos.remove(cPos) ?: continue
                val funel = funelsByUuid.remove(funelUuid) ?: continue
                val entity = plugin.server.getEntity(funel.displayUuid)
                if (entity != null) {
                    val barrierPos = AxlePos(entity.world.name, entity.location.blockX, entity.location.blockY, entity.location.blockZ)
                    if (!beltBlockPos.containsKey(barrierPos)) {
                        val block = entity.world.getBlockAt(barrierPos.bx, barrierPos.by, barrierPos.bz)
                        if (block.type == Material.BARRIER) block.type = Material.AIR
                    }
                    entity.world.dropItemNaturally(entity.location, giveItem())
                    entity.remove()
                }
            }
        }
    }

    /** Scans all loaded worlds and restores funel entities from PDC. Called after belts are restored. */
    fun restoreFunels() {
        var count = 0
        for (world in plugin.server.worlds) {
            for (entity in world.entities) {
                if (entity !is ItemDisplay) continue
                val pdc = entity.persistentDataContainer
                val containerTag = pdc.get(pdcContainerPos, PersistentDataType.STRING) ?: continue
                val containerPos = AxlePos.parse(containerTag) ?: continue
                val stateStr = pdc.get(pdcState, PersistentDataType.STRING) ?: continue
                val state = runCatching { FunelState.valueOf(stateStr) }.getOrNull() ?: continue
                if (funelsByUuid.containsKey(entity.uniqueId)) continue

                val faceName = pdc.get(pdcFace, PersistentDataType.STRING)
                val savedFace = faceName?.let { runCatching { BlockFace.valueOf(it) }.getOrNull() }
                    ?: continue
                entity.transformation = org.bukkit.util.Transformation(
                    entity.transformation.translation, RotationUtil.fromBlockFace(savedFace),
                    entity.transformation.scale, Quaternionf())

                val barrierPos = AxlePos(world.name, entity.location.blockX, entity.location.blockY, entity.location.blockZ)
                val faceModX = (barrierPos.bx - containerPos.bx).coerceIn(-1, 1)
                val faceModY = (barrierPos.by - containerPos.by).coerceIn(-1, 1)
                val faceModZ = (barrierPos.bz - containerPos.bz).coerceIn(-1, 1)
                val searchPositions = listOf(
                    AxlePos(world.name, barrierPos.bx + faceModX, barrierPos.by + faceModY,     barrierPos.bz + faceModZ),
                    AxlePos(world.name, barrierPos.bx,            barrierPos.by + faceModY - 1, barrierPos.bz           ),
                    AxlePos(world.name, barrierPos.bx + faceModX, barrierPos.by + faceModY - 1, barrierPos.bz + faceModZ),
                    AxlePos(world.name, barrierPos.bx + faceModX, barrierPos.by + faceModY + 1, barrierPos.bz + faceModZ),
                    barrierPos,
                )
                val (belt, slotIndex) = searchPositions.firstNotNullOfOrNull { beltBlockPos[it] } ?: continue
                val beltSlotPos = belt.allPositions[slotIndex]

                val towardX = (containerPos.bx - beltSlotPos.bx).coerceIn(-1, 1)
                val towardZ = (containerPos.bz - beltSlotPos.bz).coerceIn(-1, 1)

                val entry = FunelEntry(entity.uniqueId, containerPos, beltSlotPos, slotIndex, state, savedFace)
                funelsByUuid[entity.uniqueId] = entry
                funelsByContainerPos[containerPos] = entity.uniqueId
                addFunelInteractor(belt, slotIndex, containerPos, state, towardX, towardZ)
                count++
            }
        }
        if (count > 0) plugin.logger.info("Restored $count funel(s) from loaded chunks.")
    }

    fun giveItem(): ItemStack {
        val stack = ItemStack(Material.STICK)
        val meta = stack.itemMeta ?: return stack
        meta.setItemModel(NamespacedKey("ssggearmachine", "funel"))
        meta.setDisplayName("§rFunel")
        stack.itemMeta = meta
        return stack
    }

    private fun addFunelInteractor(
        belt: BeltEntry, slotIndex: Int, containerPos: AxlePos,
        state: FunelState, towardX: Int, towardZ: Int
    ) {
        val interactor: BeltInteractor = when (state) {
            FunelState.OUT     -> BeltInteractor.FunelOut(containerPos)
            FunelState.IN      -> BeltInteractor.FunelIn(containerPos)
            FunelState.ALIGNED -> BeltInteractor.FunelAuto(containerPos, towardX, towardZ)
        }
        belt.interactors.getOrPut(slotIndex) { mutableListOf() }.add(interactor)
    }

    private fun displayItem(state: FunelState): ItemStack {
        val modelId = if (state == FunelState.IN) "states/funel_in" else "states/funel_out"
        val stack = ItemStack(Material.STICK)
        val meta = stack.itemMeta ?: return stack
        meta.setItemModel(NamespacedKey("ssggearmachine", modelId))
        stack.itemMeta = meta
        return stack
    }
}
