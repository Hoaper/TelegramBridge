package org.telegrambridgeplugin.telegramBridgePlugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

public final class TelegramBridgePlugin extends JavaPlugin implements Listener {

    private TelegramClient telegramClient;
    private HttpClient httpClient;
    private Thread pollingThread;
    private volatile boolean pollingActive = false;
    private volatile long lastUpdateId = 0;
    private Set<String> adminUserIds = new HashSet<>();
    private boolean enableExecute;
    private String allowedThreadId;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadBridgeConfig();
        getServer().getPluginManager().registerEvents(this, this);
        startPollingIfEnabled();
        sendToTelegram("Server is starting up.");
    }

    @Override
    public void onDisable() {
        sendToTelegram("Server is shutting down.");
        stopPolling();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("telegrambridge")) {
            return false;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            reloadBridgeConfig();
            stopPolling();
            startPollingIfEnabled();
            sender.sendMessage("TelegramBridgePlugin config reloaded.");
            return true;
        }
        sender.sendMessage("Usage: /telegrambridge reload");
        return true;
    }

    private void reloadBridgeConfig() {
        FileConfiguration config = getConfig();
        boolean enabled = config.getBoolean("telegram.enabled", true);
        String token = config.getString("telegram.bot_token", "").trim();
        String chatId = config.getString("telegram.chat_id", "").trim();
        int timeoutSeconds = config.getInt("telegram.timeout_seconds", 10);
        enableExecute = config.getBoolean("telegram.enable_execute", true);
        allowedThreadId = config.getString("telegram.allowed_thread_id", "").trim();
        List<String> adminIds = config.getStringList("telegram.admin_user_ids");
        adminUserIds = new HashSet<>(adminIds);

        if (!enabled) {
            getLogger().info("Telegram bridge disabled via config.");
        }
        if (token.isEmpty() || chatId.isEmpty()) {
            getLogger().warning("Telegram bridge token or chat_id is not set.");
        }

        telegramClient = new TelegramClient(token, chatId, timeoutSeconds, getLogger());
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    private void sendToTelegram(String message) {
        FileConfiguration config = getConfig();
        if (!config.getBoolean("telegram.enabled", true)) {
            return;
        }
        if (telegramClient == null || telegramClient.isConfigured()) {
            return;
        }
        String prefix = config.getString("format.prefix", "[MC] ");
        String finalMessage = prefix + message;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> telegramClient.sendMessage(finalMessage));
    }

    private void startPollingIfEnabled() {
        if (!getConfig().getBoolean("telegram.enable_polling", true)) {
            return;
        }
        if (telegramClient == null || telegramClient.isConfigured()) {
            return;
        }
        if (pollingThread != null && pollingThread.isAlive()) {
            return;
        }
        pollingActive = true;
        pollingThread = new Thread(() -> {
            while (pollingActive && !Thread.currentThread().isInterrupted()) {
                if (!getConfig().getBoolean("telegram.enable_polling", true)) {
                    sleepQuietly(1000);
                    continue;
                }
                if (telegramClient == null || telegramClient.isConfigured()) {
                    sleepQuietly(1000);
                    continue;
                }
                pollTelegramUpdates();
            }
        }, "TelegramBridgePlugin-Poller");
        pollingThread.setDaemon(true);
        pollingThread.start();
    }

    private void stopPolling() {
        pollingActive = false;
        if (pollingThread != null) {
            pollingThread.interrupt();
            pollingThread = null;
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void pollTelegramUpdates() {
        try {
            if (httpClient == null) {
                sleepQuietly(1000);
                return;
            }
            String token = telegramClient.getToken();
            if (token == null || token.isBlank()) {
                sleepQuietly(1000);
                return;
            }
            String url = "https://api.telegram.org/bot" + token + "/getUpdates?offset=" + (lastUpdateId + 1) + "&timeout=25";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                sleepQuietly(1000);
                return;
            }
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!json.has("ok") || !json.get("ok").getAsBoolean()) {
                sleepQuietly(1000);
                return;
            }
            if (!json.has("result")) {
                return;
            }
            JsonArray updates = json.getAsJsonArray("result");
            for (JsonElement elem : updates) {
                JsonObject update = elem.getAsJsonObject();
                if (update.has("update_id")) {
                    lastUpdateId = update.get("update_id").getAsLong();
                }
                JsonObject message = null;
                if (update.has("message")) {
                    message = update.getAsJsonObject("message");
                } else if (update.has("channel_post")) {
                    message = update.getAsJsonObject("channel_post");
                }
                if (message != null) {
                    handleIncomingMessage(message);
                }
            }
        } catch (Exception e) {
            if (!pollingActive || Thread.currentThread().isInterrupted() || e instanceof InterruptedException) {
                return;
            }
            getLogger().log(Level.WARNING, "Telegram polling error", e);
            sleepQuietly(1000);
        }
    }

    private void handleIncomingMessage(JsonObject message) {
        if (!message.has("text") || !message.has("chat")) {
            return;
        }
        JsonObject chat = message.getAsJsonObject("chat");
        if (!isAllowedChat(chat)) {
            return;
        }
        if (!isAllowedThread(message)) {
            return;
        }
        if (!message.has("from")) {
            return;
        }
        JsonObject from = message.getAsJsonObject("from");
        if (from.has("is_bot") && from.get("is_bot").getAsBoolean()) {
            return;
        }
        String text = message.get("text").getAsString();
        String firstToken = text.split("\\s+", 2)[0];
        if (!(firstToken.equalsIgnoreCase("/execute") || firstToken.toLowerCase().startsWith("/execute@"))) {
            return;
        }
        handleExecuteCommand(from, message, text);
    }

    private boolean isAllowedChat(JsonObject chat) {
        String configured = telegramClient.getChatId();
        if (configured == null || configured.isBlank()) {
            return false;
        }
        String chatId = chat.get("id").getAsString();
        if (configured.startsWith("@")) {
            if (!chat.has("username")) {
                return false;
            }
            String username = chat.get("username").getAsString();
            return configured.substring(1).equalsIgnoreCase(username);
        }
        return configured.equals(chatId);
    }

    private boolean isAllowedThread(JsonObject message) {
        if (allowedThreadId == null || allowedThreadId.isBlank()) {
            return true;
        }
        if (!message.has("message_thread_id")) {
            return false;
        }
        return message.get("message_thread_id").getAsString().equals(allowedThreadId);
    }

    private void handleExecuteCommand(JsonObject from, JsonObject message, String text) {
        if (!enableExecute) {
            return;
        }
        if (!from.has("id")) {
            return;
        }
        String userId = from.get("id").getAsString();
        if (!adminUserIds.contains(userId)) {
            telegramClient.sendMessage("Not authorized for /execute", getMessageThreadId(message));
            return;
        }
        String[] parts = text.split("\\s+", 2);
        String commandText = parts.length > 1 ? parts[1].trim() : "";
        if (commandText.isEmpty()) {
            telegramClient.sendMessage("Usage: /execute <command>", getMessageThreadId(message));
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> {
            boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), commandText);
            String reply = ok ? "Executed: " + commandText : "Failed to execute: " + commandText;
            telegramClient.sendMessage(reply, getMessageThreadId(message));
        });
    }

    private String getMessageThreadId(JsonObject message) {
        if (message.has("message_thread_id")) {
            return message.get("message_thread_id").getAsString();
        }
        return null;
    }

    private String formatMessage(String template, String player, String message, String block, String world, int x, int y, int z, String advancement) {
        return template
                .replace("{player}", player == null ? "" : player)
                .replace("{message}", message == null ? "" : message)
                .replace("{block}", block == null ? "" : block)
                .replace("{world}", world == null ? "" : world)
                .replace("{x}", Integer.toString(x))
                .replace("{y}", Integer.toString(y))
                .replace("{z}", Integer.toString(z))
                .replace("{advancement}", advancement == null ? "" : advancement);
    }

    private boolean isEventEnabled(String path) {
        return getConfig().getBoolean("events." + path, true);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!isEventEnabled("player_join")) return;
        String template = getConfig().getString("format.join", "{player} joined the server");
        sendToTelegram(formatMessage(template, event.getPlayer().getName(), null, null, null, 0, 0, 0, null));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (!isEventEnabled("player_quit")) return;
        String template = getConfig().getString("format.quit", "{player} left the server");
        sendToTelegram(formatMessage(template, event.getPlayer().getName(), null, null, null, 0, 0, 0, null));
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!isEventEnabled("player_death")) return;
        String template = getConfig().getString("format.death", "{message}");
        String deathMessage = event.getDeathMessage();
        sendToTelegram(formatMessage(template, event.getEntity().getName(), deathMessage, null, null, 0, 0, 0, null));
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (!isEventEnabled("player_chat")) return;
        String template = getConfig().getString("format.chat", "<{player}> {message}");
        sendToTelegram(formatMessage(template, event.getPlayer().getName(), event.getMessage(), null, null, 0, 0, 0, null));
    }

    @EventHandler
    public void onPlayerAdvancement(PlayerAdvancementDoneEvent event) {
        if (!isEventEnabled("player_advancement")) return;
        String key = event.getAdvancement().getKey().getKey();
        String template = getConfig().getString("format.advancement", "{player} advanced: {advancement}");
        sendToTelegram(formatMessage(template, event.getPlayer().getName(), null, null, null, 0, 0, 0, key));
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (!isEventEnabled("block_break")) return;
        Player player = event.getPlayer();
        String template = getConfig().getString("format.block_break", "{player} broke {block} at {x},{y},{z} in {world}");
        sendToTelegram(formatMessage(
                template,
                player.getName(),
                null,
                event.getBlock().getType().name(),
                player.getWorld().getName(),
                event.getBlock().getX(),
                event.getBlock().getY(),
                event.getBlock().getZ(),
                null
        ));
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!isEventEnabled("block_place")) return;
        Player player = event.getPlayer();
        String template = getConfig().getString("format.block_place", "{player} placed {block} at {x},{y},{z} in {world}");
        sendToTelegram(formatMessage(
                template,
                player.getName(),
                null,
                event.getBlock().getType().name(),
                player.getWorld().getName(),
                event.getBlock().getX(),
                event.getBlock().getY(),
                event.getBlock().getZ(),
                null
        ));
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!isEventEnabled("player_command")) return;
        String template = getConfig().getString("format.player_command", "{player} ran: {message}");
        sendToTelegram(formatMessage(template, event.getPlayer().getName(), event.getMessage(), null, null, 0, 0, 0, null));
    }

}
