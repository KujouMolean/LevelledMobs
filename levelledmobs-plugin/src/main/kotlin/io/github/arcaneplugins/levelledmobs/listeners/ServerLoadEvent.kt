package io.github.arcaneplugins.levelledmobs.listeners

import com.molean.folia.adapter.SchedulerContext
import io.github.arcaneplugins.levelledmobs.LevelledMobs
import io.github.arcaneplugins.levelledmobs.MainCompanion
import io.github.arcaneplugins.levelledmobs.managers.ExternalCompatibilityManager
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.server.ServerLoadEvent

class ServerLoadEvent : Listener {
    @EventHandler(ignoreCancelled = true, priority = EventPriority.NORMAL)
    fun onServerLoad(event: ServerLoadEvent) {
        if (event.type != ServerLoadEvent.LoadType.STARTUP) return

        for (mobPlugin in ExternalCompatibilityManager.instance.externalPluginDefinitions.values){
            mobPlugin.clearDetectionCache()
        }

        MainCompanion.instance.hasFinishedLoading = true
        val lmItemsParser = LevelledMobs.instance.customDropsHandler.lmItemsParser
        if (lmItemsParser != null){
            SchedulerContext.ofGlobal().runTaskLater(LevelledMobs.instance, Runnable {
                lmItemsParser.processPendingItems()
                if (MainCompanion.instance.showCustomDrops) {
                    LevelledMobs.instance.customDropsHandler.customDropsParser.showCustomDropsDebugInfo(null)
                }
            }, 10L)

        }
        else if (MainCompanion.instance.showCustomDrops){
            LevelledMobs.instance.customDropsHandler.customDropsParser.showCustomDropsDebugInfo(null)
        }
    }
}