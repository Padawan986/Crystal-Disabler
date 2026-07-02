package com.starmaster.crystalpvpdisabler;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class CrystalPvPDisabler extends JavaPlugin implements Listener {


    private final Map<UUID, Long> recentAnchorInteraction = new HashMap<>();
    private final long INTERACTION_COOLDOWN = 5000; // 5 Sekunden

  
    private boolean crystalDamageBlocked = true;
    private boolean anchorDamageBlocked = true;
    private boolean crystalPlacementBlocked = true;
    private boolean anchorPlacementBlocked = true;

    @Override
    public void onEnable() {
        
        saveDefaultConfig();
        loadConfigValues();

        getServer().getPluginManager().registerEvents(this, this);
        
       
        new BukkitRunnable() {
            @Override
            public void run() {
                long currentTime = System.currentTimeMillis();
                recentAnchorInteraction.entrySet().removeIf(entry -> 
                    currentTime - entry.getValue() > INTERACTION_COOLDOWN);
            }
        }.runTaskTimer(this, 600L, 600L);
    }

    private void loadConfigValues() {
        crystalDamageBlocked = getConfig().getBoolean("crystal-damage-blocked", true);
        anchorDamageBlocked = getConfig().getBoolean("anchor-damage-blocked", true);
        crystalPlacementBlocked = getConfig().getBoolean("crystal-placement-blocked", true);
        anchorPlacementBlocked = getConfig().getBoolean("anchor-placement-blocked", true);
    }

  
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && 
            event.getClickedBlock() != null && 
            event.getClickedBlock().getType() == Material.RESPAWN_ANCHOR) {
            
            recentAnchorInteraction.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        }
    }

    
    @EventHandler(priority = EventPriority.HIGH)
    public void onCrystalPlace(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (event.getItem() != null && event.getItem().getType() == Material.END_CRYSTAL) {
                if (crystalPlacementBlocked) {
                    event.setCancelled(true);
                    event.getPlayer().sendMessage("§c[CrystalPvPDisabler] Das Platzieren von Endkristallen ist deaktiviert!");
                }
            }
        }
    }

    /**
     * Blockiert das Setzen von Seelenankern
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() == Material.RESPAWN_ANCHOR) {
            if (anchorPlacementBlocked) {
                event.setCancelled(true);
                event.getPlayer().sendMessage("§c[CrystalPvPDisabler] Das Platzieren von Seelenankern ist deaktiviert!");
            }
        }
    }

  
    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (event.getBlock().getType() == Material.RESPAWN_ANCHOR) {
            event.getBlock().getLocation().getNearbyPlayers(10.0).forEach(player -> {
                recentAnchorInteraction.put(player.getUniqueId(), System.currentTimeMillis());
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player) {
            if (event.getDamager().getType() == EntityType.END_CRYSTAL) {
                if (crystalDamageBlocked) {
                    event.setCancelled(true);
                }
            }
        }
    }

  
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        
        Player player = (Player) event.getEntity();
        EntityDamageEvent.DamageCause cause = event.getCause();
        
        if (anchorDamageBlocked) {
            UUID playerId = player.getUniqueId();
            Long lastInteraction = recentAnchorInteraction.get(playerId);
            
            if (lastInteraction != null && 
                System.currentTimeMillis() - lastInteraction < INTERACTION_COOLDOWN) {
                
                if (cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION ||
                    cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
                    
                    event.setCancelled(true);
                    return;
                }
            }
            
            if (cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION ||
                cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
                
                if (isNearbyRespawnAnchor(player)) {
                    event.setCancelled(true);
                }
            }
        }
    }

    private boolean isNearbyRespawnAnchor(Player player) {
        int radius = 10;
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.sqrt(x*x + y*y + z*z) > radius) continue;
                    try {
                        Material blockType = player.getLocation().clone().add(x, y, z).getBlock().getType();
                        if (blockType == Material.RESPAWN_ANCHOR) {
                            return true;
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        return false;
    }

    // Das einzigste was noch schiefgehen kann ist ein Error auf Layer 8 -Zitat Ende
    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("crystaldisabler")) {
            if (!sender.hasPermission("crystalpvpdisabler.admin")) {
                sender.sendMessage("§cDazu hast du keine Rechte.");
                return true;
            }

            if (args.length == 0) {
                sender.sendMessage("§8=== §bCrystalPvPDisabler Einstellungen §8===");
                sender.sendMessage("§7Kristall-Schaden blockiert: " + (crystalDamageBlocked ? "§aJA" : "§cNEIN"));
                sender.sendMessage("§7Anker-Schaden blockiert: " + (anchorDamageBlocked ? "§aJA" : "§cNEIN"));
                sender.sendMessage("§7Kristall-Platzieren blockiert: " + (crystalPlacementBlocked ? "§aJA" : "§cNEIN"));
                sender.sendMessage("§7Anker-Platzieren blockiert: " + (anchorPlacementBlocked ? "§aJA" : "§cNEIN"));
                return true;
            }
            return true;
        }
        return false;
    }
}
