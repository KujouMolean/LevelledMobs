package io.github.lokka30.levelledmobs.listeners;

import io.github.lokka30.levelledmobs.LevelledMobs;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

public class EntityDeathListener implements Listener {

    private final LevelledMobs instance;

    public EntityDeathListener(final LevelledMobs instance) {
        this.instance = instance;
    }

    @EventHandler
    public void onDeath(final EntityDeathEvent e) {
        instance.levelManager.setLevelledDrops(e.getEntity(), e.getDrops());
        e.setDroppedExp(instance.levelManager.setLevelledXP(e.getEntity(), e.getDroppedExp()));
    }
}
