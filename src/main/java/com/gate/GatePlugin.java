package com.gate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.MemorySection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.StringUtil;
import org.bukkit.util.Vector;

public class GatePlugin extends JavaPlugin implements Listener, TabExecutor, TabCompleter {
    private final Map<String, Location> gates = new HashMap<>();
    private final Map<String, Map<Integer, Location>> destinations = new HashMap<>();
    private final Map<String, Map<Integer, Integer>> chances = new HashMap<>();
    private final Set<String> disabledGates = new HashSet<>();
    private final Map<String, String> gateModes = new HashMap<>();
    private final Map<String, String> destinationNames = new HashMap<>();
    private final Map<String, Material> destinationItems = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<String, TimerTask> gateTimers = new HashMap<>();
    private final Random random = new Random();
    private FileConfiguration config;
    private World world;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = getConfig();
        String worldName = config.getString("world", "world");
        world = Bukkit.getWorld(worldName);
        if (world == null) {
            getLogger().severe("World " + worldName + " is not loaded. Plugin cannot function without it.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        loadConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("gate").setExecutor(this);
        getCommand("gate").setTabCompleter(this);
        getLogger().info(ChatColor.GREEN + "TeleportGate plugin has been enabled.");
        startTimers();
    }

    @Override
    public void onDisable() {
        getLogger().info(ChatColor.RED + "TeleportGate plugin has been disabled.");
        stopTimers();
    }

    private void loadConfig() {
        if (config.contains("gates")) {
            config.getConfigurationSection("gates").getKeys(false).forEach(gateName -> {
                gates.put(gateName, loadLocation(config, "gates." + gateName + ".location"));

                String mode = config.getString("gates." + gateName + ".mode", "random");
                gateModes.put(gateName, mode);

                MemorySection destSection = (MemorySection) config.get("gates." + gateName + ".destinations");
                Map<Integer, Location> locs = new HashMap<>();
                if (destSection != null) {
                    destSection.getKeys(false).forEach(key -> {
                        locs.put(Integer.parseInt(key), loadLocation(config, "gates." + gateName + ".destinations." + key));
                    });
                }
                destinations.put(gateName, locs);

                MemorySection chanceSection = (MemorySection) config.get("gates." + gateName + ".chances");
                Map<Integer, Integer> chncs = new HashMap<>();
                if (chanceSection != null) {
                    chanceSection.getKeys(false).forEach(key -> {
                        chncs.put(Integer.parseInt(key), chanceSection.getInt(key));
                    });
                }
                chances.put(gateName, chncs);

                MemorySection nameSection = (MemorySection) config.get("gates." + gateName + ".destination_names");
                if (nameSection != null) {
                    nameSection.getKeys(false).forEach(key -> {
                        destinationNames.put(gateName + ":" + key, nameSection.getString(key));
                    });
                }

                MemorySection itemSection = (MemorySection) config.get("gates." + gateName + ".destination_items");
                if (itemSection != null) {
                    itemSection.getKeys(false).forEach(key -> {
                        destinationItems.put(gateName + ":" + key, Material.getMaterial(itemSection.getString(key)));
                    });
                }

                boolean isDisabled = config.getBoolean("gates." + gateName + ".disabled", false);
                if (isDisabled) {
                    disabledGates.add(gateName);
                }

                String timerMode = config.getString("gates." + gateName + ".timer_mode", "none");
                if (!"none".equals(timerMode)) {
                    long countdownTime = config.getLong("gates." + gateName + ".countdown_time");
                    long openTime = config.getLong("gates." + gateName + ".open_time");
                    gateTimers.put(gateName, new TimerTask(gateName, timerMode, countdownTime, openTime));
                }
            });
        }
    }

    private Location loadLocation(FileConfiguration config, String path) {
        if (config.contains(path)) {
            String worldName = config.getString(path + ".world");
            if (worldName == null) {
                getLogger().severe("World name is null at path: " + path);
                return null;
            }
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                getLogger().severe("World " + worldName + " specified in config is not loaded.");
                return null;
            }
            double x = config.getDouble(path + ".x");
            double y = config.getDouble(path + ".y");
            double z = config.getDouble(path + ".z");
            float pitch = (float) config.getDouble(path + ".pitch");
            float yaw = (float) config.getDouble(path + ".yaw");
            return new Location(world, x, y, z, yaw, pitch);
        }
        return null;
    }

    private Map<String, Object> serializeLocation(Location loc) {
        Map<String, Object> locMap = new HashMap<>();
        locMap.put("world", loc.getWorld().getName());
        locMap.put("x", loc.getX());
        locMap.put("y", loc.getY());
        locMap.put("z", loc.getZ());
        locMap.put("pitch", loc.getPitch());
        locMap.put("yaw", loc.getYaw());
        return locMap;
    }

    private Location deserializeLocation(Map<String, Object> locMap) {
        String worldName = (String) locMap.get("world");
        if (worldName == null) {
            getLogger().severe("World name is null in deserialized location.");
            return null;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            getLogger().severe("World " + worldName + " is not loaded for deserialized location.");
            return null;
        }
        double x = (double) locMap.get("x");
        double y = (double) locMap.get("y");
        double z = (double) locMap.get("z");
        float pitch = ((Number) locMap.get("pitch")).floatValue();
        float yaw = ((Number) locMap.get("yaw")).floatValue();
        return new Location(world, x, y, z, yaw, pitch);
    }

    private void startTimers() {
        for (TimerTask timerTask : gateTimers.values()) {
            timerTask.start();
        }
    }

    private void stopTimers() {
        for (TimerTask timerTask : gateTimers.values()) {
            timerTask.cancel();
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        if (cooldowns.containsKey(playerId) && cooldowns.get(playerId) > System.currentTimeMillis()) {
            return; // Skip if player is on cooldown
        }
        Location playerLocation = player.getLocation();
        for (Map.Entry<String, Location> gate : gates.entrySet()) {
            if (disabledGates.contains(gate.getKey()) || (gateTimers.containsKey(gate.getKey()) && !gateTimers.get(gate.getKey()).isGateOpen())) {
                continue;
            }
            if (isPlayerInGate(playerLocation, gate.getValue())) {
                if ("random".equals(gateModes.get(gate.getKey()))) {
                    handleRandomMode(player, gate.getKey());
                } else if ("manual".equals(gateModes.get(gate.getKey()))) {
                    handleManualMode(player, gate.getKey());
                }
                cooldowns.put(playerId, System.currentTimeMillis() + 2000); // 5 seconds cooldown
                break;
            }
        }
    }

    private void handleRandomMode(Player player, String gateName) {
        Map<Integer, Location> locs = destinations.get(gateName);
        if (locs != null && !locs.isEmpty()) {
            Location randomDestination = getRandomDestination(gateName);
            if (randomDestination != null && randomDestination.getWorld() != null) {
                player.teleport(randomDestination);
                player.sendMessage(ChatColor.YELLOW + "You have been teleported to a random location!");
            } else {
                player.sendMessage(ChatColor.RED + "No valid destination set for this gate.");
            }
        } else {
            player.sendMessage(ChatColor.RED + "No destinations are set for this gate.");
        }
    }

    private void handleManualMode(Player player, String gateName) {
        Inventory inv = Bukkit.createInventory(null, InventoryType.CHEST, ChatColor.DARK_PURPLE + "Select Destination");
        Map<Integer, Location> locs = destinations.get(gateName);
        if (locs != null) {
            for (Map.Entry<Integer, Location> entry : locs.entrySet()) {
                Material material = destinationItems.getOrDefault(gateName + ":" + entry.getKey(), Material.ENDER_PEARL);
                ItemStack item = new ItemStack(material);
                ItemMeta meta = item.getItemMeta();
                String name = destinationNames.getOrDefault(gateName + ":" + entry.getKey(), "Destination " + entry.getKey());
                meta.setDisplayName(ChatColor.GREEN + name);
                item.setItemMeta(meta);
                inv.addItem(item);
            }
        }
        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().getTitle().equals(ChatColor.DARK_PURPLE + "Select Destination")) {
            event.setCancelled(true);
            Player player = (Player) event.getWhoClicked();
            ItemStack item = event.getCurrentItem();
            if (item != null && item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
                String displayName = item.getItemMeta().getDisplayName();
                String gateName = getGateNameFromLocation(player.getLocation());
                if (gateName != null) {
                    for (Map.Entry<Integer, Location> entry : destinations.get(gateName).entrySet()) {
                        String destName = destinationNames.getOrDefault(gateName + ":" + entry.getKey(), "Destination " + entry.getKey());
                        if ((ChatColor.GREEN + destName).equals(displayName)) {
                            Location targetLocation = entry.getValue();
                            if (targetLocation != null) {
                                player.teleport(targetLocation);
                                player.sendMessage(ChatColor.YELLOW + "Teleported to: " + displayName);
                            } else {
                                player.sendMessage(ChatColor.RED + "Invalid destination.");
                            }
                            break;
                        }
                    }
                }
            }
            player.closeInventory();
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getView().getTitle().equals(ChatColor.DARK_PURPLE + "Select Destination")) {
            Player player = (Player) event.getPlayer();
            UUID playerId = player.getUniqueId();
            cooldowns.put(playerId, System.currentTimeMillis() + 2000); // 5 seconds cooldown
            player.sendMessage(ChatColor.RED + "Teleportation canceled.");
        }
    }

    private String getGateNameFromLocation(Location location) {
        for (Map.Entry<String, Location> gate : gates.entrySet()) {
            if (isPlayerInGate(location, gate.getValue())) {
                return gate.getKey();
            }
        }
        return null;
    }

    private Location getRandomDestination(String gateName) {
        Map<Integer, Location> locs = destinations.get(gateName);
        Map<Integer, Integer> chncs = chances.get(gateName);
        if (chncs == null || chncs.isEmpty()) {
            // Equal chance
            List<Location> locList = new ArrayList<>(locs.values());
            return locList.get(random.nextInt(locList.size()));
        } else {
            int totalWeight = chncs.values().stream().mapToInt(Integer::intValue).sum();
            int chosenWeight = random.nextInt(totalWeight);
            int cumulativeWeight = 0;
            for (Map.Entry<Integer, Integer> entry : chncs.entrySet()) {
                cumulativeWeight += entry.getValue();
                if (chosenWeight < cumulativeWeight) {
                    return locs.get(entry.getKey());
                }
            }
        }
        return null; // Should never reach here
    }

    private boolean isPlayerInGate(Location playerLocation, Location gateLocation) {
        Vector min = gateLocation.toVector().subtract(new Vector(1, 1, 1));
        Vector max = gateLocation.toVector().add(new Vector(1, 1, 1));
        return playerLocation.toVector().isInAABB(min, max);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (sender instanceof Player && sender.hasPermission("teleportgate.admin")) {
            Player player = (Player) sender;
            if (args.length < 1) {
                sendHelpMessage(player);
                return false;
            }

            String gateName;
            switch (args[0].toLowerCase()) {
                case "new":
                    if (args.length != 5) {
                        player.sendMessage(ChatColor.RED + "Usage: /gate new <gateName> <coordinate_X> <coordinate_Y> <coordinate_Z>");
                        return false;
                    }
                    gateName = args[1];
                    Location newLoc = new Location(world, Double.parseDouble(args[2]), Double.parseDouble(args[3]), Double.parseDouble(args[4]));
                    gates.put(gateName, newLoc);
                    config.set("gates." + gateName + ".location", serializeLocation(newLoc));
                    saveConfig();
                    player.sendMessage(ChatColor.GREEN + "Gate created: " + gateName);
                    break;

                case "set":
                    if (args.length != 7 || !args[2].equalsIgnoreCase("destination")) {
                        player.sendMessage(ChatColor.RED + "Usage: /gate set <gateName> destination <number_of_destination> <coordinate_X> <coordinate_Y> <coordinate_Z>");
                        return false;
                    }
                    gateName = args[1];
                    int setDestNumber = Integer.parseInt(args[3]);
                    Location setDestLoc = new Location(world, Double.parseDouble(args[4]), Double.parseDouble(args[5]), Double.parseDouble(args[6]));
                    setDestLoc.setWorld(world); // Ensure the world is set
                    Map<Integer, Location> setLocs = destinations.computeIfAbsent(gateName, k -> new HashMap<>());
                    if (setLocs.containsKey(setDestNumber)) {
                        player.sendMessage(ChatColor.RED + "Destination number " + setDestNumber + " is already set. Use /gate edit to modify it.");
                        return false;
                    }
                    setLocs.put(setDestNumber, setDestLoc);
                    config.set("gates." + gateName + ".destinations." + setDestNumber, serializeLocation(setDestLoc));
                    saveConfig();
                    player.sendMessage(ChatColor.GREEN + "Destination set for gate: " + gateName);
                    break;

                case "edit":
                    if (args.length == 5 && args[2].equalsIgnoreCase("destinationname")) {
                        gateName = args[1];
                        int destNumber = Integer.parseInt(args[3]);
                        String newName = args[4];
                        destinationNames.put(gateName + ":" + destNumber, newName);
                        config.set("gates." + gateName + ".destination_names." + destNumber, newName);
                        saveConfig();
                        player.sendMessage(ChatColor.GREEN + "Destination name updated for gate: " + gateName);
                    } else if (args.length == 5 && args[2].equalsIgnoreCase("destinationitem")) {
                        gateName = args[1];
                        int destNumber = Integer.parseInt(args[3]);
                        Material newItem = Material.getMaterial(args[4].toUpperCase());
                        if (newItem == null) {
                            player.sendMessage(ChatColor.RED + "Invalid item name.");
                            return false;
                        }
                        destinationItems.put(gateName + ":" + destNumber, newItem);
                        config.set("gates." + gateName + ".destination_items." + destNumber, newItem.name());
                        saveConfig();
                        player.sendMessage(ChatColor.GREEN + "Destination item updated for gate: " + gateName);
                    } else if (args.length != 7 || !args[2].equalsIgnoreCase("destination")) {
                        player.sendMessage(ChatColor.RED + "Usage: /gate edit <gateName> destination <number_of_destination> <coordinate_X> <coordinate_Y> <coordinate_Z>");
                        return false;
                    } else {
                        gateName = args[1];
                        int editDestNumber = Integer.parseInt(args[3]);
                        Location editDestLoc = new Location(world, Double.parseDouble(args[4]), Double.parseDouble(args[5]), Double.parseDouble(args[6]));
                        editDestLoc.setWorld(world); // Ensure the world is set
                        Map<Integer, Location> editLocs = destinations.computeIfAbsent(gateName, k -> new HashMap<>());
                        editLocs.put(editDestNumber, editDestLoc);
                        config.set("gates." + gateName + ".destinations." + editDestNumber, serializeLocation(editDestLoc));
                        saveConfig();
                        player.sendMessage(ChatColor.GREEN + "Destination edited for gate: " + gateName);
                    }
                    break;

                case "delete":
                    if (args.length != 2) {
                        player.sendMessage(ChatColor.RED + "Usage: /gate delete <gateName>");
                        return false;
                    }
                    gateName = args[1];
                    gates.remove(gateName);
                    destinations.remove(gateName);
                    chances.remove(gateName);
                    destinationNames.entrySet().removeIf(entry -> entry.getKey().startsWith(gateName + ":"));
                    destinationItems.entrySet().removeIf(entry -> entry.getKey().startsWith(gateName + ":"));
                    disabledGates.remove(gateName);
                    config.set("gates." + gateName, null);
                    saveConfig();
                    player.sendMessage(ChatColor.GREEN + "Gate deleted: " + gateName);
                    break;

                case "disable":
                    if (args.length != 2) {
                        player.sendMessage(ChatColor.RED + "Usage: /gate disable <gateName>");
                        return false;
                    }
                    gateName = args[1];
                    disabledGates.add(gateName);
                    config.set("gates." + gateName + ".disabled", true);
                    saveConfig();
                    player.sendMessage(ChatColor.GREEN + "Gate disabled: " + gateName);
                    break;

                case "enable":
                    if (args.length != 2) {
                        player.sendMessage(ChatColor.RED + "Usage: /gate enable <gateName>");
                        return false;
                    }
                    gateName = args[1];
                    disabledGates.remove(gateName);
                    config.set("gates." + gateName + ".disabled", false);
                    saveConfig();
                    player.sendMessage(ChatColor.GREEN + "Gate enabled: " + gateName);
                    break;

                case "chance":
                    if (args.length < 4) {
                        player.sendMessage(ChatColor.RED + "Usage: /gate chance <gateName> <destination_number> <chance> [additional pairs...]");
                        return false;
                    }
                    gateName = args[1];
                    Map<Integer, Integer> chncs = chances.computeIfAbsent(gateName, k -> new HashMap<>());
                    int totalChance = 0;
                    for (int i = 2; i < args.length; i += 2) {
                        int destNum = Integer.parseInt(args[i]);
                        int chance = Integer.parseInt(args[i + 1]);
                        chncs.put(destNum, chance);
                        totalChance += chance;
                    }
                    if (totalChance > 100) {
                        player.sendMessage(ChatColor.RED + "Total chance exceeds 100. Please adjust the values.");
                        return false;
                    }
                    config.set("gates." + gateName + ".chances", chncs);
                    saveConfig();
                    player.sendMessage(ChatColor.GREEN + "Chances set for gate: " + gateName);
                    break;

                case "equal":
                    if (args.length != 2) {
                        player.sendMessage(ChatColor.RED + "Usage: /gate equal <gateName>");
                        return false;
                    }
                    gateName = args[1];
                    chncs = chances.computeIfAbsent(gateName, k -> new HashMap<>());
                    Map<Integer, Location> equalLocs = destinations.get(gateName);
                    if (equalLocs == null || equalLocs.isEmpty()) {
                        player.sendMessage(ChatColor.RED + "No destinations to set equal chances.");
                        return false;
                    }
                    int equalChance = 100 / equalLocs.size();
                    for (int key : equalLocs.keySet()) {
                        chncs.put(key, equalChance);
                    }
                    config.set("gates." + gateName + ".chances", chncs);
                    saveConfig();
                    player.sendMessage(ChatColor.GREEN + "Equal chances set for gate: " + gateName);
                    break;

                case "mode":
                    if (args.length != 3 || !(args[2].equalsIgnoreCase("random") || args[2].equalsIgnoreCase("manual"))) {
                        player.sendMessage(ChatColor.RED + "Usage: /gate mode <gateName> <random|manual>");
                        return false;
                    }
                    gateName = args[1];
                    String mode = args[2].toLowerCase();
                    gateModes.put(gateName, mode);
                    config.set("gates." + gateName + ".mode", mode);
                    saveConfig();
                    player.sendMessage(ChatColor.GREEN + "Mode set to " + mode + " for gate: " + gateName);
                    break;

                case "timer":
                    if (args.length < 4 || !(args[2].equalsIgnoreCase("loop") || args[2].equalsIgnoreCase("one-time") || args[2].equalsIgnoreCase("permanent"))) {
                        player.sendMessage(ChatColor.RED + "Usage: /gate timer <gateName> <loop|one-time|permanent> <countdown_time> [<open_time>]");
                        return false;
                    }                   
                    gateName = args[1];
                    String timerMode = args[2].toLowerCase();
                    long countdownTime = parseTime(args[3]);
                    long openTime = timerMode.equals("permanent") ? 0 : parseTime(args[4]);
                    
                    // Remove any existing timer
                    TimerTask existingTimer = gateTimers.get(gateName);
                    if (existingTimer != null) {
                    existingTimer.cancel();
                    gateTimers.remove(gateName);
                    }
                    TimerTask newTimer = new TimerTask(gateName, timerMode, countdownTime, openTime);
                    gateTimers.put(gateName, newTimer);
                    newTimer.start();
                    
                    config.set("gates." + gateName + ".timer_mode", timerMode);
                    config.set("gates." + gateName + ".countdown_time", countdownTime);
                    if (!"permanent".equals(timerMode)) {
                        config.set("gates." + gateName + ".open_time", openTime);
                    }
                    saveConfig();
                    player.sendMessage(ChatColor.GREEN + "Timer set for gate: " + gateName);
                    break;

                case "enabletimer":
                    if (args.length != 2) {
                        player.sendMessage(ChatColor.RED + "Usage: /gate enabletimer <gateName>");
                        return false;
                    }
                    gateName = args[1];
                    TimerTask timerTask = gateTimers.get(gateName);
                    if (timerTask != null) {
                        timerTask.start();
                        player.sendMessage(ChatColor.GREEN + "Timer enabled for gate: " + gateName);
                    } else {
                        player.sendMessage(ChatColor.RED + "No timer set for gate: " + gateName);
                    }
                    break;

                case "edittimer":
                    if (args.length < 4 || !(args[2].equalsIgnoreCase("loop") || args[2].equalsIgnoreCase("one-time") || args[2].equalsIgnoreCase("permanent"))) {
                        player.sendMessage(ChatColor.RED + "Usage: /gate edittimer <gateName> <loop|one-time|permanent> <countdown_time> [<open_time>]");
                        return false;
                    }
                        gateName = args[1];
                        timerMode = args[2].toLowerCase();
                        countdownTime = parseTime(args[3]);
                        openTime = timerMode.equals("permanent") ? 0 : parseTime(args[4]);

                        TimerTask editTimer = gateTimers.get(gateName);
                        if (editTimer != null) {
                            editTimer.cancel();
                            gateTimers.remove(gateName);
                        }

                        editTimer = new TimerTask(gateName, timerMode, countdownTime, openTime);
                        gateTimers.put(gateName, editTimer);
                        editTimer.start();

                        config.set("gates." + gateName + ".timer_mode", timerMode);
                        config.set("gates." + gateName + ".countdown_time", countdownTime);
                        if (!"permanent".equals(timerMode)) {
                            config.set("gates." + gateName + ".open_time", openTime);
                        }
                        saveConfig();
                        player.sendMessage(ChatColor.GREEN + "Timer updated for gate: " + gateName);
                        break;

                case "disabletimer":
                    if (args.length != 2) {
                        player.sendMessage(ChatColor.RED + "Usage: /gate disabletimer <gateName>");
                        return false;
                    }
                    gateName = args[1];
                    timerTask = gateTimers.get(gateName);
                    if (timerTask != null) {
                        timerTask.cancel();
                        gateTimers.remove(gateName);
                        player.sendMessage(ChatColor.GREEN + "Timer disabled for gate: " + gateName);
                    } else {
                        player.sendMessage(ChatColor.RED + "No timer set for gate: " + gateName);
                    }
                    break;


                case "settimertext":
                    if (args.length < 4) {
                        player.sendMessage(ChatColor.RED + "Usage: /gate settimertext <gateName> <type> <text>");
                        return false;
                    }
                    gateName = args[1];
                    String type = args[2].toLowerCase();
                    String timerText = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                    if (!Arrays.asList("open", "close", "closed").contains(type)) {
                        player.sendMessage(ChatColor.RED + "Invalid type. Valid types are: open, close, closed.");
                        return false;
                    }
                    config.set("gates." + gateName + ".timer_text_" + type, timerText);
                    saveConfig();
                    player.sendMessage(ChatColor.GREEN + "Timer text set for gate: " + gateName);
                    break;

                case "reload":
                    reloadConfig();
                    config = getConfig();
                    loadConfig();
                    player.sendMessage(ChatColor.GREEN + "Configuration reloaded.");
                    startTimers();
                    break;

                case "info":
                    if (args.length == 1) {
                        player.sendMessage(ChatColor.YELLOW + "Gates: " + ChatColor.GREEN + gates.keySet());
                    } else if (args.length == 2) {
                        gateName = args[1];
                        if (gates.containsKey(gateName)) {
                            player.sendMessage(ChatColor.YELLOW + "Information for gate: " + ChatColor.GREEN + gateName);
                            player.sendMessage(ChatColor.YELLOW + "Location: " + ChatColor.GREEN + gates.get(gateName));
                            player.sendMessage(ChatColor.YELLOW + "Mode: " + ChatColor.GREEN + gateModes.get(gateName));
                            player.sendMessage(ChatColor.YELLOW + "Destinations: " + ChatColor.GREEN + destinations.get(gateName));
                            player.sendMessage(ChatColor.YELLOW + "Chances: " + ChatColor.GREEN + chances.get(gateName));
                            player.sendMessage(ChatColor.YELLOW + "Disabled: " + ChatColor.GREEN + disabledGates.contains(gateName));
                            TimerTask infoTimerTask = gateTimers.get(gateName);
                            if (infoTimerTask != null) {
                                player.sendMessage(ChatColor.YELLOW + "Timer Mode: " + ChatColor.GREEN + config.getString("gates." + gateName + ".timer_mode"));
                                player.sendMessage(ChatColor.YELLOW + "Countdown Time: " + ChatColor.GREEN + formatTime(config.getLong("gates." + gateName + ".countdown_time") / 5)); // tick per second!
                                player.sendMessage(ChatColor.YELLOW + "Open Time: " + ChatColor.GREEN + formatTime(config.getLong("gates." + gateName + ".open_time") / 5)); // tick per second!
                                player.sendMessage(ChatColor.YELLOW + "Open Timer Text: " + ChatColor.GREEN + config.getString("gates." + gateName + ".timer_text_open", "Gate opens in: "));
                                player.sendMessage(ChatColor.YELLOW + "Close Timer Text: " + ChatColor.GREEN + config.getString("gates." + gateName + ".timer_text_close", "Gate closes in: "));
                                player.sendMessage(ChatColor.YELLOW + "Closed Timer Text: " + ChatColor.GREEN + config.getString("gates." + gateName + ".timer_text_closed", "Gate is closed."));
                              
                            }
                        } else {
                            player.sendMessage(ChatColor.RED + "Gate not found: " + gateName);
                        }
                    } else {
                        player.sendMessage(ChatColor.RED + "Usage: /gate info [gateName]");
                    }
                    break;

                default:
                    sendHelpMessage(player);
                    return false;
            }
            return true;
        }
        return false;
    }

    private long parseTime(String time) {
        String[] parts = time.split(":");
        long totalSeconds = 0;
        try {
            if (parts.length == 3) {
                totalSeconds += Integer.parseInt(parts[0]) * 86400; // days to seconds
                totalSeconds += Integer.parseInt(parts[1]) * 3600;  // hours to seconds
                totalSeconds += Integer.parseInt(parts[2]) * 60;    // minutes to seconds
            } else if (parts.length == 2) {
                totalSeconds += Integer.parseInt(parts[0]) * 3600; // hours to seconds
                totalSeconds += Integer.parseInt(parts[1]) * 60;   // minutes to seconds
            } else if (parts.length == 1) {
                totalSeconds += Integer.parseInt(parts[0]) * 60;   // minutes to seconds
            } else {
                totalSeconds += Long.parseLong(time) * 60; // assume single number is minutes
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid time format: " + time);
        }
        return totalSeconds * 5; // Convert to ticks (5 ticks per second in Minecraft) // tick per second!
    }

    private void sendHelpMessage(Player player) {
        player.sendMessage(ChatColor.YELLOW + "TeleportGate Plugin Commands:");
        player.sendMessage(ChatColor.AQUA + "/gate new <gateName> <coordinate_X> <coordinate_Y> <coordinate_Z>");
        player.sendMessage(ChatColor.AQUA + "/gate set <gateName> destination <number_of_destination> <coordinate_X> <coordinate_Y> <coordinate_Z>");
        player.sendMessage(ChatColor.AQUA + "/gate edit <gateName> destinationname <number_of_destination> <new_name>");
        player.sendMessage(ChatColor.AQUA + "/gate edit <gateName> destinationitem <number_of_destination> <item_name>");
        player.sendMessage(ChatColor.AQUA + "/gate edit <gateName> destination <number_of_destination> <coordinate_X> <coordinate_Y> <coordinate_Z>");
        player.sendMessage(ChatColor.AQUA + "/gate delete <gateName>");
        player.sendMessage(ChatColor.AQUA + "/gate disable <gateName>");
        player.sendMessage(ChatColor.AQUA + "/gate enable <gateName>");
        player.sendMessage(ChatColor.AQUA + "/gate chance <gateName> <destination_number> <chance> [additional pairs...]");
        player.sendMessage(ChatColor.AQUA + "/gate equal <gateName>");
        player.sendMessage(ChatColor.AQUA + "/gate mode <gateName> <random | manual>");
        player.sendMessage(ChatColor.AQUA + "/gate timer <gateName> <loop | one-time | permanent> <countdown_time> <open_time>");
        player.sendMessage(ChatColor.AQUA + "/gate enabletimer <gateName>");
        player.sendMessage(ChatColor.AQUA + "/gate disabletimer <gateName>");
        player.sendMessage(ChatColor.AQUA + "/gate edittimer <gateName> <loop | one-time | permanent> <countdown_time> [<open_time>]");
        player.sendMessage(ChatColor.AQUA + "/gate settimertext <gateName> <type> <text>");
        player.sendMessage(ChatColor.AQUA + "/gate reload");
        player.sendMessage(ChatColor.AQUA + "/gate info [gateName]");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (sender instanceof Player && sender.hasPermission("teleportgate.admin")) {
            List<String> completions = new ArrayList<>();
            if (args.length == 1) {
                List<String> commands = Arrays.asList("new", "set", "edit", "delete", "disable", "enable", "chance", "equal", "mode", "timer", "reload", "info", "settimertext", "enabletimer", "disabletimer", "edittimer");
                StringUtil.copyPartialMatches(args[0], commands, completions);
            } else if (args.length == 2) {
                if (Arrays.asList("set", "edit", "delete", "disable", "enable", "chance", "equal", "mode", "timer", "info", "settimertext", "enabletimer", "disabletimer", "edittimer").contains(args[0].toLowerCase())) {
                    StringUtil.copyPartialMatches(args[1], gates.keySet(), completions);
                }
            } else if (args.length == 3) {
                if (args[0].equalsIgnoreCase("edit")) {
                    List<String> editOptions = Arrays.asList("destination", "destinationname", "destinationitem");
                    StringUtil.copyPartialMatches(args[2], editOptions, completions);
                } else if (args[0].equalsIgnoreCase("mode")) {
                    List<String> modes = Arrays.asList("random", "manual");
                    StringUtil.copyPartialMatches(args[2], modes, completions);
                } else if (args[0].equalsIgnoreCase("timer") || args[0].equalsIgnoreCase("edittimer")) {
                    List<String> timerModes = Arrays.asList("loop", "one-time", "permanent");
                    StringUtil.copyPartialMatches(args[2], timerModes, completions);
                }
            } else if (args.length == 4 && args[2].equalsIgnoreCase("destinationname")) {
                if (gates.containsKey(args[1])) {
                    List<String> destinationNumbers = new ArrayList<>(destinations.get(args[1]).keySet().stream().map(String::valueOf).collect(Collectors.toList()));
                    StringUtil.copyPartialMatches(args[3], destinationNumbers, completions);
                }
            } else if (args.length == 4 && args[2].equalsIgnoreCase("destinationitem")) {
                List<String> materials = Arrays.stream(Material.values()).map(Material::name).map(String::toLowerCase).collect(Collectors.toList());
                StringUtil.copyPartialMatches(args[3], materials, completions);
            }
            Collections.sort(completions);
            return completions;
        }
        return null;
    }

    private String formatTime(long seconds) {
        long mins = seconds / 60;
        long secs = seconds % 60;
        return String.format("%02d:%02d", mins, secs);
    }

    class TimerTask extends BukkitRunnable {
        private final String gateName;
        private final String mode;
        private final long countdownTime;
        private final long openTime;
        private long currentCountdown;
        private boolean gateOpen;
        private ArmorStand timerStand;
    
        TimerTask(String gateName, String mode, long countdownTime, long openTime) {
            this.gateName = gateName;
            this.mode = mode;
            this.countdownTime = countdownTime;
            this.openTime = openTime;
            this.currentCountdown = countdownTime;
            this.gateOpen = false;
            removeExistingHolograms(); // Ensure previous holograms are removed
            createTimerHologram();
        }
    
        @Override
        public void run() {
            if (currentCountdown > 0) {
                currentCountdown--;
                updateTimerHologram();
            } else {
                gateOpen = !gateOpen;
                if ("permanent".equals(mode)) {
                    gateOpen = true;
                    updateTimerHologram();
                    this.cancel(); // Stop the timer once the gate is permanently open
                } else {
                    currentCountdown = gateOpen ? openTime : countdownTime;
                    updateTimerHologram();
                    if (!"loop".equals(mode) && !gateOpen) {
                        this.cancel();
                    }
                }
            }
        }
    
        void start() {
            this.runTaskTimer(GatePlugin.this, 0, 4); // Schedule task to run every 0.2 seconds
        }
    
        @Override
        public void cancel() {
            super.cancel();
            removeTimerHologram();
        }
    
        boolean isGateOpen() {
            return gateOpen;
        }
    
        private void removeExistingHolograms() {
            Location gateLocation = gates.get(gateName);
            if (gateLocation != null) {
                gateLocation.getWorld().getEntitiesByClass(ArmorStand.class).stream()
                    .filter(armorStand -> armorStand.getCustomName() != null && armorStand.getCustomName().contains("Gate"))
                    .forEach(ArmorStand::remove);
            }
        }
    
        private void createTimerHologram() {
            Location gateLocation = gates.get(gateName);
            if (gateLocation != null) {
                timerStand = gateLocation.getWorld().spawn(gateLocation.clone().add(0, 0.2, 0), ArmorStand.class);
                timerStand.setVisible(false);
                timerStand.setCustomNameVisible(true);
                timerStand.setGravity(false);
                updateTimerHologram();
            }
        }
    
        private void updateTimerHologram() {
            if (timerStand != null) {
                String timerText;
                if (gateOpen) {
                    timerText = config.getString("gates." + gateName + ".timer_text_close", "Gate closes in: ");
                } else if ("permanent".equals(mode) && currentCountdown == 0) {
                    timerText = "Gate is open.";
                } else if (currentCountdown == countdownTime) {
                    timerText = config.getString("gates." + gateName + ".timer_text_open", "Gate opens in: ");
                } else {
                    timerText = config.getString("gates." + gateName + ".timer_text_closed", "Gate is closed.");
                }
                String timeLeft = formatTime(currentCountdown / 5); // Convert ticks to seconds
                timerStand.setCustomName(ChatColor.YELLOW + timerText + timeLeft);
            }
        }
    
        private void removeTimerHologram() {
            if (timerStand != null) {
                timerStand.remove();
                timerStand = null;
            }
        }
    
        private String formatTime(long seconds) {
            long mins = seconds / 60;
            long secs = seconds % 60;
            return String.format("%02d:%02d", mins, secs);
        }
    }    
    
}