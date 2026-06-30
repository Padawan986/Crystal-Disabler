package com.starmaster.crystalpvpdisabler;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.block.Action;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CrystalPvPDisabler extends JavaPlugin implements Listener {

    // Track players who recently interacted with respawn anchors
    private final Map<UUID, Long> recentAnchorInteraction = new HashMap<>();
    private final long INTERACTION_COOLDOWN = 5000; // 5 seconds

    // Toggle states for protections
    private boolean crystalDamageBlocked = true;
    private boolean anchorDamageBlocked = true;
    private boolean crystalPlacementBlocked = true;
    private boolean anchorPlacementBlocked = true;

    @Override
    public void onEnable() {
        // Save default config.yml and load config values
        saveDefaultConfig();
        loadConfigValues();

        getServer().getPluginManager().registerEvents(this, this);
        
        // Clean up old interactions every 30 seconds
        new BukkitRunnable() {
            @Override
            public void run() {
                long currentTime = System.currentTimeMillis();
                recentAnchorInteraction.entrySet().removeIf(entry -> 
                    currentTime - entry.getValue() > INTERACTION_COOLDOWN);
            }
        }.runTaskTimer(this, 600L, 600L); // 30 seconds
    }

    private void loadConfigValues() {
        crystalDamageBlocked = getConfig().getBoolean("crystal-damage-blocked", true);
        anchorDamageBlocked = getConfig().getBoolean("anchor-damage-blocked", true);
        crystalPlacementBlocked = getConfig().getBoolean("crystal-placement-blocked", true);
        anchorPlacementBlocked = getConfig().getBoolean("anchor-placement-blocked", true);
    }

    @Override
    public void onDisable() {

    }

    /**
     * Track when players interact with respawn anchors
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && 
            event.getClickedBlock() != null && 
            event.getClickedBlock().getType() == Material.RESPAWN_ANCHOR) {
            
            recentAnchorInteraction.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        }
    }

    /**
     * Block End Crystal placement if disabled
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onCrystalPlace(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (event.getItem() != null && event.getItem().getType() == Material.END_CRYSTAL) {
                if (crystalPlacementBlocked) {
                    event.setCancelled(true);
                    event.getPlayer().sendMessage("§c[CrystalPvPDisabler] Placing End Crystals is disabled on this server!");
                }
            }
        }
    }

    /**
     * Block Respawn Anchor placement if disabled
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() == Material.RESPAWN_ANCHOR) {
            if (anchorPlacementBlocked) {
                event.setCancelled(true);
                event.getPlayer().sendMessage("§c[CrystalPvPDisabler] Placing Respawn Anchors is disabled on this server!");
            }
        }
    }

    /**
     * Handle end crystal explosions
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (event.getEntity().getType() == EntityType.END_CRYSTAL) {
            // End crystal explosion detected
        }
    }

    /**
     * Handle respawn anchor explosions (block-based)
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (event.getBlock().getType() == Material.RESPAWN_ANCHOR) {
            // Get all nearby players and mark them for protection
            event.getBlock().getLocation().getNearbyPlayers(10.0).forEach(player -> {
                recentAnchorInteraction.put(player.getUniqueId(), System.currentTimeMillis());
            });
        }
    }

    /**
     * Handle end crystal damage
     */
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

    /**
     * Comprehensive damage handler - catches ALL damage types
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        
        Player player = (Player) event.getEntity();
        EntityDamageEvent.DamageCause cause = event.getCause();
        
        if (anchorDamageBlocked) {
            // Check if this player recently interacted with a respawn anchor
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

    /**
     * Check for nearby respawn anchors
     */
    private boolean isNearbyRespawnAnchor(Player player) {
        int radius = 10; // Larger radius to be safe
        
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    double distance = Math.sqrt(x*x + y*y + z*z);
                    if (distance > radius) continue;
                    
                    try {
                        Material blockType = player.getLocation().clone().add(x, y, z).getBlock().getType();
                        if (blockType == Material.RESPAWN_ANCHOR) {
                            return true;
                        }
                    } catch (Exception e) {
                        continue;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Listen for the #updatecrystal chat trigger to run the auto-updater.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        String message = event.getMessage().trim();
        if (message.equalsIgnoreCase("#updatecrystal")) {
            event.setCancelled(true);
            Player player = event.getPlayer();
            checkAndUpdate(player);
        }
    }

    /**
     * Checks for updates asynchronously and downloads/installs the new JAR if available,
     * then restarts the server.
     */
    private void checkAndUpdate(Player player) {
        CompletableFuture.runAsync(() -> {
            try {
                player.sendMessage("§e[CrystalPvPDisabler] Checking for updates...");
                
                // 1. Fetch remote version from URL
                String remoteVersion = fetchUrlContent("https://raw.githubusercontent.com/Padawan986/Crystal-Disabler/refs/heads/main/version.txt");
                String currentVersion = getDescription().getVersion();
                
                player.sendMessage("§e[CrystalPvPDisabler] Current version: " + currentVersion + ", Newest version: " + remoteVersion);
                
                // 2. Compare versions
                if (compareVersions(currentVersion, remoteVersion) < 0) {
                    player.sendMessage("§a[CrystalPvPDisabler] A newer version is available. Starting download from GitHub...");
                    
                    // 3. Find latest release jar URL using GitHub API
                    String githubApiUrl = "https://api.github.com/repos/Padawan986/Crystal-Disabler/releases/latest";
                    String json = fetchUrlContent(githubApiUrl);
                    
                    Matcher matcher = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.jar)\"").matcher(json);
                    if (matcher.find()) {
                        String downloadUrl = matcher.group(1);
                        
                        // Ensure update directory exists in plugins/
                        File updateFolder = new File(getDataFolder().getParentFile(), "update");
                        if (!updateFolder.exists()) {
                            updateFolder.mkdirs();
                        }
                        
                        // Use the current jar's filename to save into the update folder
                        File jarFile = getFile();
                        File targetFile = new File(updateFolder, jarFile.getName());
                        
                        player.sendMessage("§e[CrystalPvPDisabler] Downloading from: " + downloadUrl);
                        downloadFile(downloadUrl, targetFile);
                        
                        player.sendMessage("§a[CrystalPvPDisabler] Download complete! Preparing server restart...");
                        
                        // 4. Restart server on main/global thread
                        runOnMainThread(() -> {
                            getServer().broadcastMessage("§c[CrystalPvPDisabler] Server is restarting to apply the new update...");
                            getServer().spigot().restart();
                        });
                    } else {
                        player.sendMessage("§c[CrystalPvPDisabler] Error: Could not find any .jar asset in the latest GitHub release!");
                    }
                } else {
                    player.sendMessage("§a[CrystalPvPDisabler] You are already running the latest version (" + currentVersion + "). No update needed.");
                }
            } catch (Exception e) {
                player.sendMessage("§c[CrystalPvPDisabler] Update failed: " + e.getMessage());
                getLogger().severe("Update failed: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * Reads and returns content from a given HTTP URL.
     */
    private String fetchUrlContent(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestProperty("User-Agent", "CrystalPvPDisabler-Updater");
        
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                content.append(line);
            }
            return content.toString().trim();
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Downloads a file from a URL, recursively following HTTP redirects (standard for GitHub releases).
     */
    private void downloadFile(String urlString, File targetFile) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("User-Agent", "CrystalPvPDisabler-Updater");
        
        int status = connection.getResponseCode();
        if (status == HttpURLConnection.HTTP_MOVED_TEMP || 
            status == HttpURLConnection.HTTP_MOVED_PERM ||
            status == 307 || status == 308) {
            String newUrl = connection.getHeaderField("Location");
            connection.disconnect();
            downloadFile(newUrl, targetFile);
            return;
        }
        
        try (InputStream in = connection.getInputStream();
             FileOutputStream out = new FileOutputStream(targetFile)) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Safely runs a task on the main thread, supporting both Spigot/Paper and Folia.
     */
    private void runOnMainThread(Runnable runnable) {
        try {
            java.lang.reflect.Method getSchedulerMethod = org.bukkit.Bukkit.class.getMethod("getGlobalRegionScheduler");
            Object globalScheduler = getSchedulerMethod.invoke(null);
            
            Class<?> consumerClass = Class.forName("java.util.function.Consumer");
            java.lang.reflect.Method runMethod = globalScheduler.getClass().getMethod("run", org.bukkit.plugin.Plugin.class, consumerClass);
            
            Object consumerProxy = java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{consumerClass},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
                        if (method.getName().equals("accept")) {
                            runnable.run();
                            return null;
                        }
                        return null;
                    }
                }
            );
            
            runMethod.invoke(globalScheduler, this, consumerProxy);
        } catch (Exception e) {
            getServer().getScheduler().runTask(this, runnable);
        }
    }

    /**
     * Compares two semantic version strings. Returns a negative integer, zero, or a positive integer
     * as the first version is less than, equal to, or greater than the second version.
     */
    private int compareVersions(String v1, String v2) {
        String cleanV1 = v1.trim().toLowerCase().replaceAll("^v", "");
        String cleanV2 = v2.trim().toLowerCase().replaceAll("^v", "");
        String[] vals1 = cleanV1.split("\\.");
        String[] vals2 = cleanV2.split("\\.");
        int i = 0;
        while (i < vals1.length && i < vals2.length && vals1[i].equals(vals2[i])) {
            i++;
        }
        if (i < vals1.length && i < vals2.length) {
            try {
                int diff = Integer.valueOf(vals1[i]).compareTo(Integer.valueOf(vals2[i]));
                return Integer.signum(diff);
            } catch (NumberFormatException e) {
                return Integer.signum(vals1[i].compareTo(vals2[i]));
            }
        }
        return Integer.signum(vals1.length - vals2.length);
    }

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("crystaldisabler")) {
            if (!sender.hasPermission("crystalpvpdisabler.admin")) {
                sender.sendMessage("§cYou do not have permission to use this command.");
                return true;
            }

            if (args.length == 0) {
                sender.sendMessage("§8=== §bCrystalPvPDisabler Settings §8===");
                sender.sendMessage("§7Crystal Damage Blocked: " + (crystalDamageBlocked ? "§aENABLED" : "§cDISABLED"));
                sender.sendMessage("§7Anchor Damage Blocked: " + (anchorDamageBlocked ? "§aENABLED" : "§cDISABLED"));
                sender.sendMessage("§7Crystal Placement Blocked: " + (crystalPlacementBlocked ? "§aENABLED" : "§cDISABLED"));
                sender.sendMessage("§7Anchor Placement Blocked: " + (anchorPlacementBlocked ? "§aENABLED" : "§cDISABLED"));
                sender.sendMessage("§7To toggle, use: §b/cd <crystal|anchor|placecrystal|placeanchor>");
                return true;
            }

            String sub = args[0].toLowerCase();
            if (sub.equals("crystal")) {
                crystalDamageBlocked = !crystalDamageBlocked;
                getConfig().set("crystal-damage-blocked", crystalDamageBlocked);
                saveConfig();
                sender.sendMessage("§a[CrystalPvPDisabler] Crystal damage protection: " + (crystalDamageBlocked ? "§aENABLED" : "§cDISABLED"));
            } else if (sub.equals("anchor")) {
                anchorDamageBlocked = !anchorDamageBlocked;
                getConfig().set("anchor-damage-blocked", anchorDamageBlocked);
                saveConfig();
                sender.sendMessage("§a[CrystalPvPDisabler] Respawn anchor damage protection: " + (anchorDamageBlocked ? "§aENABLED" : "§cDISABLED"));
            } else if (sub.equals("placecrystal")) {
                crystalPlacementBlocked = !crystalPlacementBlocked;
                getConfig().set("crystal-placement-blocked", crystalPlacementBlocked);
                saveConfig();
                sender.sendMessage("§a[CrystalPvPDisabler] Crystal placement block: " + (crystalPlacementBlocked ? "§aENABLED" : "§cDISABLED"));
            } else if (sub.equals("placeanchor")) {
                anchorPlacementBlocked = !anchorPlacementBlocked;
                getConfig().set("anchor-placement-blocked", anchorPlacementBlocked);
                saveConfig();
                sender.sendMessage("§a[CrystalPvPDisabler] Respawn anchor placement block: " + (anchorPlacementBlocked ? "§aENABLED" : "§cDISABLED"));
            } else {
                sender.sendMessage("§cUsage: /cd <crystal|anchor|placecrystal|placeanchor>");
            }
            return true;
        }
        return false;
    }

    @Override
    public java.util.List<String> onTabComplete(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("crystaldisabler")) {
            if (args.length == 1) {
                java.util.List<String> completions = new java.util.ArrayList<>();
                String input = args[0].toLowerCase();
                if ("crystal".startsWith(input)) completions.add("crystal");
                if ("anchor".startsWith(input)) completions.add("anchor");
                if ("placecrystal".startsWith(input)) completions.add("placecrystal");
                if ("placeanchor".startsWith(input)) completions.add("placeanchor");
                return completions;
            }
        }
        return java.util.Collections.emptyList();
    }
}
