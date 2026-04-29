package com.propbot.propbot;

import com.propbot.logging.AppLog;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.json.JsonParser;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Minimal Bayeux/WebSocket listener for GroupMe push events.
 *
 * <p>Subscribes to configured push channels and logs "favorite" reaction events.
 */
@Component
public class GroupMePushListener {

    private static final AppLog log = AppLog.forClass(GroupMePushListener.class);
    private static final Duration RECONNECT_DELAY = Duration.ofSeconds(5);

    private final GroupMeProperties groupMe;
    private final GroupMeFavoriteEventHandler favoriteEventHandler;
    private final JsonParser jsonParser = JsonParserFactory.getJsonParser();
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "groupme-push-listener"));
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong nextId = new AtomicLong(1);

    private volatile String clientId;
    private volatile WebSocket webSocket;

    public GroupMePushListener(GroupMeProperties groupMe, GroupMeFavoriteEventHandler favoriteEventHandler) {
        this.groupMe = groupMe;
        this.favoriteEventHandler = favoriteEventHandler;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!isConfigured()) {
            log.info(
                    "GroupMe push listener disabled",
                    "Set GROUPME_ACCESS_TOKEN and at least one of GROUPME_PUSH_USER_ID or GROUPME_GROUP_ID.");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        worker.submit(this::runLoop);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        WebSocket socket = webSocket;
        if (socket != null) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
        }
        worker.shutdownNow();
    }

    private void runLoop() {
        while (running.get()) {
            CountDownLatch closed = new CountDownLatch(1);
            clientId = null;
            nextId.set(1);
            try {
                URI gateway = URI.create(groupMe.pushGatewayUrl());
                HttpClient.newHttpClient()
                        .newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(20))
                        .buildAsync(gateway, new PushWebSocketListener(closed))
                        .join();
                closed.await();
            } catch (Exception e) {
                log.warn("GroupMe push listener error", e);
            }
            if (!running.get()) {
                break;
            }
            sleep(RECONNECT_DELAY);
        }
    }

    private boolean isConfigured() {
        return !stringOrEmpty(groupMe.accessToken()).isBlank()
                && (!stringOrEmpty(groupMe.pushUserId()).isBlank() || !stringOrEmpty(groupMe.groupId()).isBlank());
    }

    private void sendHandshake() {
        Map<String, Object> msg = baseMetaMessage("/meta/handshake");
        msg.put("version", "1.0");
        msg.put("minimumVersion", "1.0");
        msg.put("supportedConnectionTypes", List.of("websocket"));
        send(msg);
    }

    private void sendSubscribe() {
        if (stringOrEmpty(clientId).isBlank()) {
            return;
        }
        for (String subscriptionChannel : channelsForSubscription()) {
            Map<String, Object> msg = baseMetaMessage("/meta/subscribe");
            msg.put("clientId", clientId);
            msg.put("subscription", subscriptionChannel);
            send(msg);
        }
    }

    private void sendConnect() {
        if (stringOrEmpty(clientId).isBlank()) {
            return;
        }
        Map<String, Object> msg = baseMetaMessage("/meta/connect");
        msg.put("clientId", clientId);
        msg.put("connectionType", "websocket");
        send(msg);
    }

    private Map<String, Object> baseMetaMessage(String channel) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("channel", channel);
        msg.put("id", String.valueOf(nextId.getAndIncrement()));
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("access_token", groupMe.accessToken());
        ext.put("timestamp", Instant.now().getEpochSecond());
        msg.put("ext", ext);
        return msg;
    }

    private synchronized void send(Map<String, Object> message) {
        WebSocket socket = webSocket;
        if (socket == null) {
            return;
        }
        String envelope = "[" + toJson(message) + "]";
        socket.sendText(envelope, true);
    }

    private void onBayeuxText(String text) {
        for (Map<String, Object> message : parseEnvelope(text)) {
            String channel = stringOrEmpty(message.get("channel"));
            if ("/meta/handshake".equals(channel)) {
                if (booleanOrFalse(message.get("successful"))) {
                    clientId = stringOrEmpty(message.get("clientId"));
                    log.info("GroupMe push handshake successful", "clientId=" + clientId);
                    sendSubscribe();
                    sendConnect();
                } else {
                    log.warn("GroupMe push handshake failed", String.valueOf(message));
                }
                continue;
            }
            if ("/meta/connect".equals(channel)) {
                if (booleanOrFalse(message.get("successful"))) {
                    sendConnect();
                }
                continue;
            }
            if ("/meta/subscribe".equals(channel)) {
                if (booleanOrFalse(message.get("successful"))) {
                    String subscribedChannel = stringOrEmpty(message.get("subscription"));
                    if (subscribedChannel.isBlank()) {
                        subscribedChannel = "(unknown)";
                    }
                    log.info("GroupMe push subscribed", "channel=" + subscribedChannel);
                } else {
                    log.warn("GroupMe push subscribe failed", String.valueOf(message));
                }
                continue;
            }
            if (channelsForSubscription().contains(channel)) {
                Map<String, Object> data = asMap(message.get("data"));
                if (data.isEmpty()) {
                    data = message;
                }
                favoriteEventHandler.handleIfFavorite(data, "websocket");
            }
        }
    }

    private List<Map<String, Object>> parseEnvelope(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isBlank()) {
            return List.of();
        }
        try {
            Object parsed = trimmed.startsWith("[") ? jsonParser.parseList(trimmed) : jsonParser.parseMap(trimmed);
            if (parsed instanceof List<?> list) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Object item : list) {
                    Map<String, Object> asMap = asMap(item);
                    if (!asMap.isEmpty()) {
                        out.add(asMap);
                    }
                }
                return out;
            }
            return List.of(asMap(parsed));
        } catch (Exception e) {
            log.warn("Failed to parse GroupMe push payload", e);
            return List.of();
        }
    }

    private String userChannel() {
        return "/user/" + groupMe.pushUserId();
    }

    private String groupChannel() {
        return "/group/" + groupMe.groupId();
    }

    private List<String> channelsForSubscription() {
        LinkedHashSet<String> channels = new LinkedHashSet<>();
        if (!stringOrEmpty(groupMe.pushUserId()).isBlank()) {
            channels.add(userChannel());
        }
        if (!stringOrEmpty(groupMe.groupId()).isBlank()) {
            channels.add(groupChannel());
        }
        return List.copyOf(channels);
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }

    private static String stringOrEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean booleanOrFalse(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return "true".equalsIgnoreCase(stringOrEmpty(value));
    }

    private static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return "\"" + escapeJson(s) + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .filter(entry -> entry.getKey() != null)
                    .map(entry -> "\"" + escapeJson(String.valueOf(entry.getKey())) + "\":" + toJson(entry.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
        }
        if (value instanceof List<?> list) {
            return list.stream().map(GroupMePushListener::toJson).collect(Collectors.joining(",", "[", "]"));
        }
        return "\"" + escapeJson(String.valueOf(value)) + "\"";
    }

    private static String escapeJson(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private final class PushWebSocketListener implements WebSocket.Listener {
        private final CountDownLatch closed;
        private final StringBuilder textBuffer = new StringBuilder();

        private PushWebSocketListener(CountDownLatch closed) {
            this.closed = closed;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            GroupMePushListener.this.webSocket = webSocket;
            webSocket.request(1);
            log.info("GroupMe push websocket connected", "gateway=" + groupMe.pushGatewayUrl());
            sendHandshake();
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String payload = textBuffer.toString();
                textBuffer.setLength(0);
                onBayeuxText(payload);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("GroupMe push websocket error", error);
            closed.countDown();
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            GroupMePushListener.this.webSocket = null;
            log.warn("GroupMe push websocket closed", "status=" + statusCode + ", reason=" + reason);
            closed.countDown();
            return CompletableFuture.completedFuture(null);
        }
    }
}
