package org.telegrambridgeplugin.telegramBridgePlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Logger;

public class TelegramClient {
    private final String token;
    private final String chatId;
    private final int timeoutSeconds;
    private final Logger logger;
    private final HttpClient httpClient;

    public TelegramClient(String token, String chatId, int timeoutSeconds, Logger logger) {
        this.token = token;
        this.chatId = chatId;
        this.timeoutSeconds = timeoutSeconds;
        this.logger = logger;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    public boolean isConfigured() {
        return token == null || token.isBlank() || chatId == null || chatId.isBlank();
    }

    public String getToken() {
        return token;
    }

    public String getChatId() {
        return chatId;
    }

    public void sendMessage(String message) {
        sendMessage(message, null);
    }

    public void sendMessage(String message, String threadId) {
        if (isConfigured()) {
            return;
        }
        try {
            StringBuilder payload = new StringBuilder();
            payload.append("{\"chat_id\":\"").append(escapeJson(chatId)).append("\",")
                    .append("\"text\":\"").append(escapeJson(message)).append("\"");
            if (threadId != null && !threadId.isBlank()) {
                payload.append(",\"message_thread_id\":\"").append(escapeJson(threadId)).append("\"");
            }
            payload.append("}");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.telegram.org/bot" + token + "/sendMessage"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .exceptionally(ex -> {
                        logger.warning("Failed to send Telegram message: " + ex.getMessage());
                        return null;
                    });
        } catch (Exception ex) {
            logger.warning("Failed to build Telegram request: " + ex.getMessage());
        }
    }

    private String escapeJson(String input) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 32) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
