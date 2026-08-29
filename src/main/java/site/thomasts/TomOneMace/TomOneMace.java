package site.thomasts.TomOneMace;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class TomOneMace extends JavaPlugin implements Listener, TabCompleter {

    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private BossBar activeBossBar = null;
    private BukkitTask activeHuntTask = null;
    private UUID currentHuntedPlayer = null;
    private long huntSecondsLeft = 0;
    private long totalHuntSeconds = 0;
    private boolean huntHideOwner = false;
    private String huntedPlayerName = "";
    private final java.util.Set<UUID> pendingResets = new java.util.HashSet<>();

    @Override
    public void onEnable() {
        int pluginId = 33717;
        Metrics metrics = new Metrics(this, pluginId);

        metrics.addCustomChart(new SimplePie("mace_owner_status", () -> {
            String owner = getConfig().getString("mace_owner");

            if (owner != null && !owner.isEmpty() && !owner.equalsIgnoreCase("none")) {
                return "Has an Owner";
            } else {
                return "No Owner Yet";
            }
        }));

        getServer().getPluginManager().registerEvents(this, this);

        saveDefaultConfig();

        if (getCommand("tomonemace") != null) {
            Objects.requireNonNull(getCommand("tomonemace")).setExecutor(this);
            Objects.requireNonNull(getCommand("tomonemace")).setTabCompleter(this);
        }
    }

    @EventHandler
    public void onMaceCraft(CraftItemEvent event) {
        ItemStack result = event.getCurrentItem();

        if (result != null && result.getType() == Material.MACE) {
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }

            if (!player.hasPermission("tomonemace.craft")) {
                event.setCancelled(true);
                player.sendMessage(miniMessage.deserialize("<red><i>You do not have permission to craft the One Mace!</i></red>"));
                return;
            }

            String playerUUID = player.getUniqueId().toString();
            String existingOwnerUUID = getConfig().getString("mace_owner");

            if (existingOwnerUUID != null && !existingOwnerUUID.isEmpty() && !existingOwnerUUID.equalsIgnoreCase("none")) {
                event.setCancelled(true);
                player.sendMessage(miniMessage.deserialize("<red><bold>Error:</bold> <i>Someone else already owns the One Mace!</i></red>"));
            } else {
                getConfig().set("mace_owner", playerUUID);
                saveConfig();

                boolean hideOwner = getConfig().getBoolean("hide_owner");
                String playerName = player.getName();

                Component announcement;
                if (hideOwner) {
                    announcement = miniMessage.deserialize("<dark_red><bold>The One Mace has been claimed!</bold></dark_red> <gray><i>The recipe has now been disabled.</i></gray>");
                } else {
                    announcement = miniMessage.deserialize("<red><bold>" + playerName + "</bold></red> <yellow>has successfully claimed the One Mace!</yellow>");
                }
                getServer().sendMessage(announcement);

                boolean maceHunting = getConfig().getBoolean("mace_hunting");
                if (maceHunting) {
                    Location loc = player.getLocation();
                    String coordsStr = loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();

                    Component coordsAnnouncement = miniMessage.deserialize("<gold><bold>⚔ HUNT THEM DOWN ⚔</bold></gold> <gray>Location: <yellow>" + coordsStr + "</yellow></gray>");
                    getServer().sendMessage(coordsAnnouncement);

                    long minutes = getConfig().getLong("hunt_time");
                    player.sendMessage(miniMessage.deserialize("<red><bold><underlined>WARNING:</underlined></bold> You must stay online for <yellow>" + minutes + "</yellow> minutes!</red><newline><gray><i>If you disconnect, you will lose the Mace and the owner will be reset.</i></gray>"));

                    boolean glowEffect = getConfig().getBoolean("glow_effect");
                    if (glowEffect) {
                        player.setGlowing(true);

                        long ticks = minutes * 1200L;
                        Bukkit.getScheduler().runTaskLater(this, () -> {
                            if (player.isOnline()) {
                                player.setGlowing(false);
                            }
                        }, ticks);
                    }

                    if (activeHuntTask != null) {
                        activeHuntTask.cancel();
                    }

                    currentHuntedPlayer = player.getUniqueId();
                    huntedPlayerName = player.getName();
                    huntHideOwner = hideOwner;
                    totalHuntSeconds = minutes * 60;
                    huntSecondsLeft = totalHuntSeconds;

                    boolean bossBarEnabled = getConfig().getBoolean("boss_bar", true);
                    if (bossBarEnabled) {
                        Component initialTitle = getHuntTitle(huntSecondsLeft);
                        activeBossBar = BossBar.bossBar(
                                initialTitle,
                                1.0f,
                                BossBar.Color.PURPLE,
                                BossBar.Overlay.PROGRESS
                        );

                        for (Player p : Bukkit.getOnlinePlayers()) {
                            p.showBossBar(activeBossBar);
                        }
                    }

                    activeHuntTask = new BukkitRunnable() {
                        @Override
                        public void run() {
                            huntSecondsLeft--;

                            if (huntSecondsLeft <= 0) {
                                clearHunt();
                                this.cancel();
                                return;
                            }

                            if (activeBossBar != null) {
                                activeBossBar.name(getHuntTitle(huntSecondsLeft));
                                activeBossBar.progress((float) huntSecondsLeft / (float) totalHuntSeconds);
                            }
                        }
                    }.runTaskTimer(TomOneMace.this, 20L, 20L);
                }

                getLogger().info(playerName + " is now the owner of the mace (UUID: " + playerUUID + ")");
            }
        }
    }

    private Component getHuntTitle(long secondsRemaining) {
        long mins = secondsRemaining / 60;
        long secs = secondsRemaining % 60;

        if (huntHideOwner) {
            return miniMessage.deserialize("<light_purple><bold>Target Unknown</bold> <gray>| Time Left: </gray><white>" + mins + "m " + secs + "s</white></light_purple>");
        } else {
            return miniMessage.deserialize("<light_purple><bold>Target:</bold> " + huntedPlayerName + " <gray>| Time Left: </gray><white>" + mins + "m " + secs + "s</white></light_purple>");
        }
    }

    private void clearHunt() {
        if (currentHuntedPlayer != null) {
            Player hunted = Bukkit.getPlayer(currentHuntedPlayer);
            if (hunted != null) {
                hunted.setGlowing(false);
            }
        }

        if (activeBossBar != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.hideBossBar(activeBossBar);
            }
        }
        activeBossBar = null;
        currentHuntedPlayer = null;
        huntSecondsLeft = 0;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (currentHuntedPlayer != null && event.getPlayer().getUniqueId().equals(currentHuntedPlayer)) {
            event.getPlayer().getInventory().remove(Material.MACE);
            event.getPlayer().setGlowing(false);

            getConfig().set("mace_owner", "none");
            saveConfig();

            getServer().sendMessage(miniMessage.deserialize("<dark_red><bold>COWARD DETECTED!</bold></dark_red> <gray><i>The Mace owner logged out during the hunt. The Mace has been reset!</i></gray>"));

            if (activeHuntTask != null) {
                activeHuntTask.cancel();
            }
            clearHunt();
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();

        if (activeBossBar != null && huntSecondsLeft > 0) {
            p.showBossBar(activeBossBar);

            if (!p.getUniqueId().equals(currentHuntedPlayer)) {
                long mins = huntSecondsLeft / 60;
                long secs = huntSecondsLeft % 60;

                if (huntHideOwner) {
                    p.sendMessage(miniMessage.deserialize("<gold><bold>⚔ MACE HUNT ACTIVE ⚔</bold></gold><newline><yellow>Someone has the One Mace! You have <green><bold>" + mins + "m " + secs + "s</bold></green> to find them!</yellow>"));
                } else {
                    p.sendMessage(miniMessage.deserialize("<gold><bold>⚔ MACE HUNT ACTIVE ⚔</bold></gold><newline><red><bold>" + huntedPlayerName + "</bold></red> <yellow>has the One Mace! Hunt them down!</yellow>"));
                }
            }
        }
    }

    @EventHandler
    public void onMacePickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        boolean stealable = getConfig().getBoolean("stealable");
        if (stealable) {
            ItemStack pickedUpItem = event.getItem().getItemStack();
            if (pickedUpItem.getType() == Material.MACE) {

                String currentOwnerUUID = getConfig().getString("mace_owner");
                String playerUUID = player.getUniqueId().toString();

                if (currentOwnerUUID != null && currentOwnerUUID.equals(playerUUID)) {
                    return;
                }

                if (currentHuntedPlayer != null) {
                    Player oldHunted = Bukkit.getPlayer(currentHuntedPlayer);
                    if (oldHunted != null) {
                        oldHunted.setGlowing(false);
                    }
                }

                getConfig().set("mace_owner", playerUUID);
                saveConfig();

                boolean stealAnnouncement = getConfig().getBoolean("steal_announcement");
                if (stealAnnouncement) {
                    getServer().sendMessage(miniMessage.deserialize("<dark_red><bold>⚔ THE MACE HAS BEEN STOLEN! ⚔</bold></dark_red><newline><red><bold>" + player.getName() + "</bold></red> <yellow>picked up the One Mace and is the new owner!</yellow>"));
                }

                if (activeHuntTask != null) {
                    activeHuntTask.cancel();
                }
                clearHunt();
            }
        }
    }

    @EventHandler
    public void onItemBurn(EntityDamageEvent event) {
        if (event.getEntity() instanceof org.bukkit.entity.Item itemEntity) {
            ItemStack item = itemEntity.getItemStack();
            if (item.getType() == Material.MACE) {
                if (event.getCause() == EntityDamageEvent.DamageCause.FIRE ||
                        event.getCause() == EntityDamageEvent.DamageCause.FIRE_TICK ||
                        event.getCause() == EntityDamageEvent.DamageCause.LAVA) {

                    String currentOwnerUUID = getConfig().getString("mace_owner");
                    if (currentOwnerUUID != null && !currentOwnerUUID.isEmpty() && !currentOwnerUUID.equalsIgnoreCase("none")) {
                        getConfig().set("mace_owner", "none");
                        saveConfig();

                        if (activeHuntTask != null) {
                            activeHuntTask.cancel();
                        }
                        clearHunt();

                        getServer().sendMessage(miniMessage.deserialize("<dark_red><bold>MACE DESTROYED:</bold></dark_red> <yellow>The One Mace was burned in fire/lava! The owner has been reset.</yellow>"));
                    }
                    itemEntity.remove();
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler
    public void onBlockBurn(BlockBurnEvent event) {
        if (isShulkerBox(event.getBlock().getType())) {
            BlockState state = event.getBlock().getState();
            if (state instanceof ShulkerBox shulkerBox) {
                if (shulkerBox.getInventory().contains(Material.MACE)) {
                    resetMaceFromDestruction("burned in a shulker box");
                }
            }
        }
    }

    private void resetMaceFromDestruction(String reason) {
        String currentOwnerUUID = getConfig().getString("mace_owner");
        if (currentOwnerUUID != null && !currentOwnerUUID.isEmpty() && !currentOwnerUUID.equalsIgnoreCase("none")) {
            getConfig().set("mace_owner", "none");
            saveConfig();

            if (activeHuntTask != null) {
                activeHuntTask.cancel();
            }
            clearHunt();

            getServer().sendMessage(miniMessage.deserialize("<dark_red><bold>MACE DESTROYED:</bold></dark_red> <yellow>The One Mace was " + reason + "! The owner has been reset.</yellow>"));
        }
    }

    private boolean isShulkerBox(Material material) {
        if (material == null) return false;
        return material.name().endsWith("SHULKER_BOX");
    }

    private boolean containsMace(ItemStack itemStack) {
        if (itemStack == null) return false;

        if (itemStack.getType() == Material.MACE) {
            return true;
        }
        if (isShulkerBox(itemStack.getType())) {
            if (itemStack.getItemMeta() instanceof BlockStateMeta bsMeta) {
                BlockState blockState = bsMeta.getBlockState();
                if (blockState instanceof ShulkerBox shulkerBox) {
                    return shulkerBox.getInventory().contains(Material.MACE);
                }
            }
        }
        return false;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!getConfig().getBoolean("enderchest_store", false)) {
            if (event.getView().getTopInventory().getType() == InventoryType.ENDER_CHEST) {
                ItemStack clickedItem = event.getCurrentItem();
                ItemStack cursorItem = event.getCursor();

                if (containsMace(clickedItem) || containsMace(cursorItem)) {
                    if (event.getAction() == org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY ||
                            (event.getClickedInventory() != null && event.getClickedInventory().getType() == InventoryType.ENDER_CHEST)) {

                        event.setCancelled(true);
                        event.getWhoClicked().sendMessage(miniMessage.deserialize("<red><bold>Error:</bold> <i>You cannot hide the One Mace in an Ender Chest!</i></red>"));
                    }
                }

                if (event.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY) {
                    ItemStack hotbarItem = event.getWhoClicked().getInventory().getItem(event.getHotbarButton());
                    if (containsMace(hotbarItem)) {
                        event.setCancelled(true);
                        event.getWhoClicked().sendMessage(miniMessage.deserialize("<red><bold>Error:</bold> <i>You cannot hide the One Mace in an Ender Chest!</i></red>"));
                    }
                }
            }
        }

        // Shulker Box Protection (Fixed type check)
        Inventory topInv = event.getView().getTopInventory();
        if (topInv.getType() == InventoryType.SHULKER_BOX ||
                (event.getClickedInventory() != null && event.getClickedInventory().getType() == InventoryType.SHULKER_BOX)) {

            ItemStack clickedItem = event.getCurrentItem();
            ItemStack cursorItem = event.getCursor();

            if (containsMace(clickedItem) || containsMace(cursorItem)) {
                event.setCancelled(true);
                event.getWhoClicked().sendMessage(miniMessage.deserialize("<red><bold>Error:</bold> <i>You cannot put the One Mace inside a Shulker Box!</i></red>"));
                return;
            }

            if (event.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY) {
                ItemStack hotbarItem = event.getWhoClicked().getInventory().getItem(event.getHotbarButton());
                if (containsMace(hotbarItem)) {
                    event.setCancelled(true);
                    event.getWhoClicked().sendMessage(miniMessage.deserialize("<red><bold>Error:</bold> <i>You cannot put the One Mace inside a Shulker Box!</i></red>"));
                }
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!getConfig().getBoolean("enderchest_store", false)) {
            if (event.getView().getTopInventory().getType() == InventoryType.ENDER_CHEST) {
                if (containsMace(event.getOldCursor())) {
                    for (int slot : event.getRawSlots()) {
                        if (slot < event.getView().getTopInventory().getSize()) {
                            event.setCancelled(true);
                            event.getWhoClicked().sendMessage(miniMessage.deserialize("<red><bold>Error:</bold> <i>You cannot hide the One Mace in an Ender Chest!</i></red>"));
                            return;
                        }
                    }
                }
            }
        }

        Inventory topInv = event.getView().getTopInventory();
        if (topInv.getType() == InventoryType.SHULKER_BOX) {
            if (containsMace(event.getOldCursor())) {
                for (int slot : event.getRawSlots()) {
                    if (slot < topInv.getSize()) {
                        event.setCancelled(true);
                        event.getWhoClicked().sendMessage(miniMessage.deserialize("<red><bold>Error:</bold> <i>You cannot put the One Mace inside a Shulker Box!</i></red>"));
                        return;
                    }
                }
            }
        }
    }

    @Override
    public boolean onCommand(@NonNull CommandSender sender, Command command, @NonNull String label, String @NonNull [] args) {
        if (command.getName().equalsIgnoreCase("tomonemace")) {
            if (args.length > 0) {
                if (args[0].equalsIgnoreCase("reload")) {
                    if (!sender.hasPermission("tomonemace.admin")) {
                        sender.sendMessage(miniMessage.deserialize("<red><i>You do not have permission to use this command.</i></red>"));
                        return true;
                    }

                    reloadConfig();
                    sender.sendMessage(miniMessage.deserialize("<green><bold>SUCCESS:</bold></green> <gray>TomOneMace configuration reloaded!</gray>"));
                    return true;

                } else if (args[0].equalsIgnoreCase("setowner")) {
                    if (!sender.hasPermission("tomonemace.admin")) {
                        sender.sendMessage(miniMessage.deserialize("<red><i>You do not have permission to use this command.</i></red>"));
                        return true;
                    }

                    if (args.length < 2) {
                        sender.sendMessage(miniMessage.deserialize("<yellow>Usage: <white>/tom setowner <player></white></yellow>"));
                        return true;
                    }

                    String targetName = args[1];
                    OfflinePlayer targetPlayer = Bukkit.getOfflinePlayer(targetName);

                    if (!targetPlayer.hasPlayedBefore() && !targetPlayer.isOnline()) {
                        sender.sendMessage(miniMessage.deserialize("<red><bold>Error:</bold> <i>Player not found or has never joined the server.</i></red>"));
                        return true;
                    }

                    getConfig().set("mace_owner", targetPlayer.getUniqueId().toString());
                    saveConfig();

                    sender.sendMessage(miniMessage.deserialize("<green>Successfully set the mace owner to </green><gold><bold>" + targetName + "</bold></gold>"));
                    return true;

                } else if (args[0].equalsIgnoreCase("whoisowner")) {
                    if (!sender.hasPermission("tomonemace.admin")) {
                        sender.sendMessage(miniMessage.deserialize("<red><i>You do not have permission to use this command.</i></red>"));
                        return true;
                    }

                    String uuidString = getConfig().getString("mace_owner");

                    if (uuidString == null || uuidString.isEmpty() || uuidString.equalsIgnoreCase("none")) {
                        sender.sendMessage(miniMessage.deserialize("<yellow><i>There is currently no owner set for the One Mace.</i></yellow>"));
                        return true;
                    }

                    try {
                        UUID ownerUUID = UUID.fromString(uuidString);
                        OfflinePlayer owner = Bukkit.getOfflinePlayer(ownerUUID);
                        String ownerName = owner.getName();

                        if (ownerName != null) {
                            sender.sendMessage(miniMessage.deserialize("<green>The current owner of the One Mace is: </green><gold><bold>" + ownerName + "</bold></gold>"));
                        } else {
                            sender.sendMessage(miniMessage.deserialize("<red><bold>Error:</bold> <i>Owner found in config, but their name could not be retrieved from the server.</i></red>"));
                        }

                    } catch (IllegalArgumentException e) {
                        sender.sendMessage(miniMessage.deserialize("<red><bold>Error:</bold> <i>The UUID saved in the config is invalid!</i></red>"));
                    }
                    return true;

                } else if (args[0].equalsIgnoreCase("reset")) {
                    if (!sender.hasPermission("tomonemace.admin")) {
                        sender.sendMessage(miniMessage.deserialize("<red><i>You do not have permission to use this command.</i></red>"));
                        return true;
                    }

                    UUID senderUUID = (sender instanceof Player p) ? p.getUniqueId() : new UUID(0L, 0L);

                    if (args.length > 1 && args[1].equalsIgnoreCase("confirm")) {
                        if (pendingResets.contains(senderUUID)) {
                            pendingResets.remove(senderUUID);

                            getConfig().set("mace_owner", "none");
                            saveConfig();
                            clearHunt();

                            getServer().sendMessage(miniMessage.deserialize("<dark_red><bold>MACE RESET:</bold></dark_red> <yellow>An admin has manually reset the One Mace owner!</yellow>"));
                            return true;
                        } else {
                            sender.sendMessage(miniMessage.deserialize("<red><i>You do not have a pending reset request. Type /tom reset first.</i></red>"));
                            return true;
                        }
                    }

                    pendingResets.add(senderUUID);
                    Bukkit.getScheduler().runTaskLater(this, () -> pendingResets.remove(senderUUID), 200L); // 10 seconds expiry

                    sender.sendMessage(miniMessage.deserialize("<yellow><bold>WARNING:</bold> <i>This will reset the current Mace owner and clear active hunts. Type <white>/tom reset confirm</white> within 10 seconds to proceed.</i></yellow>"));
                    return true;

                } else {
                    sender.sendMessage(miniMessage.deserialize("<yellow>Usage: <white>/tom reload</white> | <white>/tom setowner <player></white> | <white>/tom whoisowner</white> | <white>/tom reset</white></yellow>"));
                    return true;
                }
            } else {
                sender.sendMessage(miniMessage.deserialize("<yellow>Usage: <white>/tom reload</white> | <white>/tom setowner <player></white> | <white>/tom whoisowner</white> | <white>/tom reset</white></yellow>"));
                return true;
            }
        }
        return false;
    }

    @Override
    public @NonNull List<String> onTabComplete(@NonNull CommandSender sender, Command command, @NonNull String alias, String @NonNull [] args) {
        List<String> completions = new ArrayList<>();

        if (command.getName().equalsIgnoreCase("tomonemace")) {
            if (!sender.hasPermission("tomonemace.admin")) {
                return completions;
            }

            if (args.length == 1) {
                String input = args[0].toLowerCase();
                List<String> options = List.of("reload", "setowner", "whoisowner", "reset");
                for (String option : options) {
                    if (option.startsWith(input)) {
                        completions.add(option);
                    }
                }
            } else if (args.length == 2 && args[0].equalsIgnoreCase("setowner")) {
                String input = args[1].toLowerCase();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getName().toLowerCase().startsWith(input)) {
                        completions.add(player.getName());
                    }
                }
            } else if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
                String input = args[1].toLowerCase();
                if ("confirm".startsWith(input)) {
                    completions.add("confirm");
                }
            }
        }
        return completions;
    }

    @Override
    public void onDisable() {
        clearHunt();
    }
}