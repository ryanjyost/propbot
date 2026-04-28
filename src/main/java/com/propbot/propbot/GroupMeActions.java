package com.propbot.propbot;

import static java.lang.System.lineSeparator;

import com.propbot.logging.AppLog;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * GroupMe API actions (reactions, etc.). Uses {@code POST /messages/{conversation}/{id}/like}
 * under {@link GroupMeProperties#apiBaseUrl()} (default {@code https://api.groupme.com/v3}).
 */
@Service
public class GroupMeActions {

    private static final AppLog log = AppLog.forClass(GroupMeActions.class);

    /** GroupMe's allowed unicode reactions for {@code like_icon}; anything else is rejected by their API. */
    private static final Set<String> ALLOWED_UNICODE_REACTIONS = Set.of(
            "❤️", "👍", "🤣", "🎉", "🔥", "😮", "👀", "😭", "🥺", "🙏", "💀", "🫶", "🤬", "💅", "🫠");

    private final RestClient restClient;
    private final GroupMeProperties properties;

    public GroupMeActions(GroupMeProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(trimTrailingSlash(properties.apiBaseUrl()))
                .build();
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private void requireToken() {
        if (properties.accessToken() == null || properties.accessToken().isBlank()) {
            throw new IllegalStateException("GROUPME_ACCESS_TOKEN is not set");
        }
    }

    /** The unicode reactions this app will send (matches GroupMe client options). */
    public static Set<String> allowedUnicodeReactions() {
        return ALLOWED_UNICODE_REACTIONS;
    }

    public static boolean isAllowedUnicodeReaction(String emoji) {
        return emoji != null && ALLOWED_UNICODE_REACTIONS.contains(emoji.strip());
    }

    private static void requireAllowedUnicode(String unicodeEmoji) {
        if (!isAllowedUnicodeReaction(unicodeEmoji)) {
            throw new IllegalArgumentException(
                    "Reaction must be exactly one of: " + String.join(" ", ALLOWED_UNICODE_REACTIONS));
        }
    }

    /**
     * React to a message with one of the unicode reactions allowed by GroupMe
     * ({@link #allowedUnicodeReactions()}).
     *
     * @param conversationId for a group chat, this is the group id
     */
    public void reactWithUnicode(String conversationId, String messageId, String unicodeEmoji) {
        requireToken();
        requireAllowedUnicode(unicodeEmoji);
        var code = unicodeEmoji.strip();
        var body = Map.<String, Object>of("like_icon", Map.of("type", "unicode", "code", code));
        postLike(conversationId, messageId, body, code + " Reaction sent", "");
    }

    /**
     * React with a GroupMe powerup emoji (pack id + index from emoji docs).
     */
    public void reactWithPowerup(String conversationId, String messageId, int packId, int packIndex) {
        requireToken();
        var body = Map.<String, Object>of(
                "like_icon",
                Map.of("type", "emoji", "pack_id", packId, "pack_index", packIndex));
        postLike(
                conversationId,
                messageId,
                body,
                "✨ Power-up reaction sent",
                "Pack " + packId + ", sticker #" + packIndex);
    }

    /** Convenience: react on the message from a webhook payload (uses group id + message id). */
    public void reactWithUnicode(GroupMeMessage message, String unicodeEmoji) {
        reactWithUnicode(message.groupId(), message.id(), unicodeEmoji);
    }

    /** Convenience: powerup reaction on a webhook message. */
    public void reactWithPowerup(GroupMeMessage message, int packId, int packIndex) {
        reactWithPowerup(message.groupId(), message.id(), packId, packIndex);
    }

    private void postLike(
            String conversationId, String messageId, Map<String, ?> body, String logHeadline, String logDetail) {
        var response = restClient
                .post()
                .uri(uriBuilder -> uriBuilder
                        .path("/messages/{conversationId}/{messageId}/like")
                        .queryParam("token", properties.accessToken())
                        .build(conversationId, messageId))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
        String detail =
                (logDetail == null || logDetail.isBlank()) ? "" : logDetail + lineSeparator();
        log.info(logHeadline, detail + "HTTP " + response.getStatusCode().value() + ".");
    }
}
