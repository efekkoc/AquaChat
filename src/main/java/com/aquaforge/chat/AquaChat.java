package com.aquaforge.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AquaChat - Universal RGB Gradient Chat, Item Engine & Enterprise Anti-Spam Shield.
 * Supports Paper 1.20.x through 1.21.11 (Native Paper AsyncChatEvent & Kyori Adventure).
 *
 * @author Turgut Efekan Koç (efekkoc)
 * @version 1.1.0
 */
public final class AquaChat extends JavaPlugin implements Listener, CommandExecutor {

    private static AquaChat instance;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, String> playerChannels = new ConcurrentHashMap<>();
    
    // Anti-Spam Tracking
    private final Map<UUID, Long> chatCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastChatMessages = new ConcurrentHashMap<>();
    private final Map<UUID, Long> commandCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> spamViolations = new ConcurrentHashMap<>();
    private final Map<UUID, Long> mutedUntil = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        getServer().getPluginManager().registerEvents(this, this);

        if (getCommand("channel") != null) {
            getCommand("channel").setExecutor(this);
        }
        if (getCommand("item") != null) {
            getCommand("item").setExecutor(this);
        }

        getLogger().info("=================================================");
        getLogger().info(" AquaChat v" + getDescription().getVersion() + " Initialized");
        getLogger().info(" Engine: Paper 1.20.x – 1.21.11 Universal Adventure");
        getLogger().info(" Feature: Enterprise Anti-Spam & CAPS Limiter Online");
        getLogger().info(" Author: Turgut Efekan Koç (efekkoc)");
        getLogger().info("=================================================");
    }

    @Override
    public void onDisable() {
        playerChannels.clear();
        chatCooldowns.clear();
        lastChatMessages.clear();
        commandCooldowns.clear();
        mutedUntil.clear();
        getLogger().info("AquaChat disabled gracefully.");
    }

    public static AquaChat getInstance() {
        return instance;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        // 1. Check if temporarily auto-muted for spam
        Long muteEnd = mutedUntil.get(uuid);
        if (muteEnd != null) {
            if (now < muteEnd) {
                event.setCancelled(true);
                long leftSec = (muteEnd - now) / 1000L;
                player.sendMessage(miniMessage.deserialize("<gradient:#ff416c:#ff4b2b>[Susturuldunuz]</gradient> <red>Spam yaptığınız için " + leftSec + " saniye susturuldunuz."));
                return;
            } else {
                mutedUntil.remove(uuid);
                spamViolations.remove(uuid);
            }
        }

        boolean bypass = player.hasPermission("aquachat.bypass.spam") || player.isOp();
        String rawMessage = LegacyComponentSerializer.legacySection().serialize(event.message()).trim();

        if (!bypass && getConfig().getBoolean("anti_spam.enabled", true)) {
            // 2. Chat Cooldown Check
            long lastChat = chatCooldowns.getOrDefault(uuid, 0L);
            long cooldownMillis = (long) (getConfig().getDouble("anti_spam.chat_cooldown_seconds", 2.0) * 1000);
            if (now - lastChat < cooldownMillis) {
                event.setCancelled(true);
                recordViolation(player);
                player.sendMessage(miniMessage.deserialize("<gradient:#ff416c:#ff4b2b>Yavaşlayın!</gradient> <gray>Çok hızlı mesaj gönderiyorsunuz."));
                return;
            }

            // 3. Duplicate Message Spam Check
            if (getConfig().getBoolean("anti_spam.block_duplicate_messages", true)) {
                String lastMsg = lastChatMessages.get(uuid);
                if (lastMsg != null && lastMsg.equalsIgnoreCase(rawMessage)) {
                    event.setCancelled(true);
                    recordViolation(player);
                    String warn = getConfig().getString("anti_spam.duplicate_message_warning", "<red>Aynı mesajı tekrar gönderemezsiniz!");
                    player.sendMessage(miniMessage.deserialize(warn));
                    return;
                }
            }

            // 4. CAPS Lock Filter
            if (getConfig().getBoolean("anti_spam.block_excessive_caps", true)) {
                int minLen = getConfig().getInt("anti_spam.caps_min_length", 6);
                if (rawMessage.length() >= minLen) {
                    int upperCount = 0;
                    for (char c : rawMessage.toCharArray()) {
                        if (Character.isUpperCase(c)) upperCount++;
                    }
                    int percent = (int) (((double) upperCount / rawMessage.length()) * 100);
                    int threshold = getConfig().getInt("anti_spam.caps_percentage_threshold", 65);
                    if (percent >= threshold) {
                        event.setCancelled(true);
                        recordViolation(player);
                        String warn = getConfig().getString("anti_spam.caps_warning", "<red>Lütfen aşırı büyük harf kullanmayınız!");
                        player.sendMessage(miniMessage.deserialize(warn));
                        return;
                    }
                }
            }

            // Update trackers
            chatCooldowns.put(uuid, now);
            lastChatMessages.put(uuid, rawMessage);
        }

        String channel = playerChannels.getOrDefault(uuid, "global");

        // Mention ping check
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (rawMessage.toLowerCase().contains(online.getName().toLowerCase())) {
                online.playSound(online.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.5f);
            }
        }

        if (channel.equalsIgnoreCase("staff")) {
            event.setCancelled(true);
            Component staffFormatted = miniMessage.deserialize(
                    "<gradient:#ff416c:#ff4b2b>[Staff]</gradient> <red>" + player.getName() + " &8» <white>" + rawMessage
            );
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission("aquachat.staffchat")) {
                    staff.sendMessage(staffFormatted);
                }
            }
            return;
        }

        if (channel.equalsIgnoreCase("local")) {
            event.setCancelled(true);
            Component localFormatted = miniMessage.deserialize(
                    "<gradient:#11998e:#38ef7d>[Local]</gradient> <gray>" + player.getName() + " &8» <white>" + rawMessage
            );
            int radius = getConfig().getInt("channels.local.radius", 100);
            int radiusSquared = radius * radius;
            for (Player target : Bukkit.getOnlinePlayers()) {
                if (target.getWorld().equals(player.getWorld()) && target.getLocation().distanceSquared(player.getLocation()) <= radiusSquared) {
                    target.sendMessage(localFormatted);
                }
            }
            return;
        }

        // Global chat with modern gradient
        Component globalPrefix = miniMessage.deserialize("<gradient:#00d2ff:#3a7bd5>[Global]</gradient> <gray>" + player.getName() + " &8» <white>");
        event.renderer((source, displayName, message, viewer) -> globalPrefix.append(message));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (!getConfig().getBoolean("anti_spam.command_rate_limit", true)) return;
        Player player = event.getPlayer();
        if (player.hasPermission("aquachat.bypass.spam") || player.isOp()) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastCmd = commandCooldowns.getOrDefault(uuid, 0L);
        long cmdCooldown = (long) (getConfig().getDouble("anti_spam.command_cooldown_seconds", 1.0) * 1000);

        if (now - lastCmd < cmdCooldown) {
            event.setCancelled(true);
            recordViolation(player);
            String warn = getConfig().getString("anti_spam.command_warning", "<red>Çok hızlı komut giriyorsunuz.");
            player.sendMessage(miniMessage.deserialize(warn));
            return;
        }

        commandCooldowns.put(uuid, now);
    }

    private void recordViolation(Player player) {
        if (!getConfig().getBoolean("anti_spam.auto_mute_enabled", true)) return;
        UUID uuid = player.getUniqueId();
        int violations = spamViolations.getOrDefault(uuid, 0) + 1;
        spamViolations.put(uuid, violations);

        int maxViolations = getConfig().getInt("anti_spam.auto_mute_violations", 4);
        if (violations >= maxViolations) {
            int muteSec = getConfig().getInt("anti_spam.auto_mute_duration_seconds", 60);
            mutedUntil.put(uuid, System.currentTimeMillis() + (muteSec * 1000L));
            player.sendMessage(miniMessage.deserialize("<gradient:#ff416c:#ff4b2b>[Ceza]</gradient> <red>Sürekli spam yaptığınız için " + muteSec + " saniye boyunca susturuldunuz!"));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Console cannot toggle chat channels.");
            return true;
        }

        if (command.getName().equalsIgnoreCase("channel")) {
            if (args.length == 0) {
                player.sendMessage(miniMessage.deserialize("<gradient:#00d2ff:#3a7bd5>AquaChat</gradient> <gray>Kanallar: <aqua>/ch global<gray>, <green>/ch local<gray>, <red>/ch staff"));
                return true;
            }
            String targetChannel = args[0].toLowerCase();
            if (targetChannel.equals("staff") && !player.hasPermission("aquachat.staffchat")) {
                player.sendMessage(miniMessage.deserialize("<red>Yetkiniz yok."));
                return true;
            }
            playerChannels.put(player.getUniqueId(), targetChannel);
            player.sendMessage(miniMessage.deserialize("<green>Aktif kanal: <yellow>" + targetChannel));
            return true;
        }

        return false;
    }
}
