package dev.createonmc.nexo

import com.nexomc.nexo.api.events.NexoItemsLoadedEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener

/**
 * Nexo loads its item registry asynchronously after plugin enable (see the API docs'
 * "Update Callbacks" note). Anything built via [NexoCompat.item] before this event fires
 * would just get the fallback stack, so we only use this to validate the server's config
 * and warn early — actual item builds already happen lazily, on demand, well after startup.
 */
class NexoLoadListener : Listener {
    @EventHandler
    fun onNexoItemsLoaded(event: NexoItemsLoadedEvent) {
        NexoCompat.validateConfigured()
    }
}
