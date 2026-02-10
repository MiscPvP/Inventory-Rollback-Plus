package com.nuclyon.technicallycoded.inventoryrollback.util;

import com.nuclyon.technicallycoded.inventoryrollback.InventoryRollbackPlus;
import me.danjono.inventoryrollback.InventoryRollback;
import me.danjono.inventoryrollback.config.ConfigData;
import me.danjono.inventoryrollback.data.LogType;
import me.danjono.inventoryrollback.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

public final class DiscordWebhookLogger {

    private DiscordWebhookLogger() {
    }

    private static final int EMBED_COLOR = 15158332; // Red
    private static final int MAX_ITEM_LINES = 20;
    private static final int MAX_FIELD_CHARS = 900;

    public static void logRollback(Player staff, OfflinePlayer target, LogType logType, long timestamp, String action) {
        logRollback(staff, target, logType, timestamp, action, new ItemStack[0]);
    }

    public static void logRollback(Player staff, OfflinePlayer target, LogType logType, long timestamp, String action, ItemStack[] items) {
        if (!ConfigData.isWebhookEnabled()) return;

        String webhookUrl = ConfigData.getWebhookUrl();
        if (webhookUrl == null || webhookUrl.trim().isEmpty()) return;

        String staffName = staff != null ? staff.getName() : "Console";
        String staffUuid = staff != null ? staff.getUniqueId().toString() : "N/A";
        String targetName = target != null ? target.getName() : "Unknown";
        String targetUuid = target != null ? target.getUniqueId().toString() : "N/A";
        String backupTime = timestamp > 0 ? PlayerData.getTime(timestamp) : "Unknown";
        String itemsSummary = formatItems(items);

        sendWebhookAsync(webhookUrl, buildJsonPayload(staffName, staffUuid, targetName, targetUuid, logType, backupTime, action, itemsSummary));
    }

    private static void sendWebhookAsync(String webhookUrl, String payloadJson) {
        if (InventoryRollbackPlus.getInstance() == null || InventoryRollbackPlus.getInstance().isShuttingDown()) return;

        Bukkit.getScheduler().runTaskAsynchronously(InventoryRollback.getInstance(), () -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(webhookUrl).openConnection();
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setConnectTimeout(ConfigData.getWebhookTimeoutMs());
                connection.setReadTimeout(ConfigData.getWebhookTimeoutMs());
                connection.setRequestProperty("Content-Type", "application/json");

                byte[] payload = payloadJson.getBytes(StandardCharsets.UTF_8);

                connection.setFixedLengthStreamingMode(payload.length);
                connection.connect();

                try (OutputStream stream = connection.getOutputStream()) {
                    stream.write(payload);
                }

                int responseCode = connection.getResponseCode();
                if (responseCode < 200 || responseCode >= 300) {
                    InventoryRollback.getPluginLogger().log(Level.WARNING,
                            "Discord webhook returned non-2xx response: " + responseCode);
                }
            } catch (Exception ex) {
                InventoryRollback.getPluginLogger().log(Level.WARNING, "Failed to send Discord webhook", ex);
            }
        });
    }

    private static String buildJsonPayload(String staffName,
                                           String staffUuid,
                                           String targetName,
                                           String targetUuid,
                                           LogType logType,
                                           String backupTime,
                                           String action,
                                           String itemsSummary) {
        StringBuilder json = new StringBuilder();
        json.append("{");

        String username = ConfigData.getWebhookUsername();
        if (username != null && !username.trim().isEmpty()) {
            json.append("\"username\":\"").append(escapeJson(username)).append("\",");
        }

        String avatarUrl = ConfigData.getWebhookAvatarUrl();
        if (avatarUrl != null && !avatarUrl.trim().isEmpty()) {
            json.append("\"avatar_url\":\"").append(escapeJson(avatarUrl)).append("\",");
        }

        json.append("\"embeds\":[{");
        json.append("\"title\":\"Rollback Issued\",");
        json.append("\"color\":").append(EMBED_COLOR).append(",");
        json.append("\"fields\":[");
        json.append(field("Player", targetName + " (" + targetUuid + ")", false)).append(",");
        json.append(field("Staff", staffName + " (" + staffUuid + ")", false)).append(",");
        json.append(field("Action", action, false)).append(",");
        if (itemsSummary != null && !itemsSummary.isEmpty()) {
            json.append(field("Items", itemsSummary, false)).append(",");
        }
        json.append(field("Log Type", logType != null ? logType.name() : "Unknown", true)).append(",");
        json.append(field("Backup Time", backupTime, true));
        json.append("],");

        if (targetUuid != null && !targetUuid.equals("N/A")) {
            String thumbUrl = "https://minotar.net/avatar/" + targetUuid + "/64";
            json.append("\"thumbnail\":{\"url\":\"").append(escapeJson(thumbUrl)).append("\"},");
        }

        json.append("\"timestamp\":\"").append(escapeJson(isoNowUtc())).append("\"");
        json.append("}]");
        json.append("}");
        return json.toString();
    }

    private static String field(String name, String value, boolean inline) {
        StringBuilder field = new StringBuilder();
        field.append("{");
        field.append("\"name\":\"").append(escapeJson(name)).append("\",");
        field.append("\"value\":\"").append(escapeJson(value)).append("\",");
        field.append("\"inline\":").append(inline ? "true" : "false");
        field.append("}");
        return field.toString();
    }

    private static String isoNowUtc() {
        return java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString();
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (c < 32) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                    break;
            }
        }
        return escaped.toString();
    }

    private static String formatItems(ItemStack[] items) {
        if (items == null || items.length == 0) return "";

        List<String> lines = new ArrayList<>();
        for (ItemStack item : items) {
            if (item == null || item.getType() == Material.AIR) continue;

            String name = formatItemName(item);
            String enchants = formatEnchantments(item.getItemMeta());
            String line = name + " x" + item.getAmount();
            if (!enchants.isEmpty()) {
                line += " (" + enchants + ")";
            }
            lines.add(line);
        }

        if (lines.isEmpty()) return "";

        StringBuilder summary = new StringBuilder();
        int total = lines.size();
        int used = 0;
        for (String line : lines) {
            if (used >= MAX_ITEM_LINES) break;
            if (summary.length() + line.length() + 2 > MAX_FIELD_CHARS) break;
            if (summary.length() > 0) summary.append("\n");
            summary.append("- ").append(line);
            used++;
        }

        int remaining = total - used;
        if (remaining > 0) {
            String suffix = "\n...and " + remaining + " more";
            if (summary.length() + suffix.length() <= MAX_FIELD_CHARS) {
                summary.append(suffix);
            }
        }

        return summary.toString();
    }

    private static String formatItemName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return ChatColor.stripColor(meta.getDisplayName());
        }
        return titleCase(item.getType().name());
    }

    private static String formatEnchantments(ItemMeta meta) {
        if (meta == null || meta.getEnchants().isEmpty()) return "";

        List<String> parts = new ArrayList<>();
        for (Map.Entry<Enchantment, Integer> entry : meta.getEnchants().entrySet()) {
            String name = entry.getKey().getKey().getKey();
            parts.add(titleCase(name) + " " + entry.getValue());
        }
        return String.join(", ", parts);
    }

    private static String titleCase(String value) {
        if (value == null || value.isEmpty()) return "";
        String[] parts = value.toLowerCase(Locale.ENGLISH).split("[_\\s]+");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (result.length() > 0) result.append(" ");
            result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return result.toString();
    }
}
