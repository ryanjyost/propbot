package com.propbot.propbot;

import static java.lang.System.lineSeparator;

import com.propbot.logging.AppLog;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class GroupMeCallbackController {

    private static final AppLog log = AppLog.forClass(GroupMeCallbackController.class);

    private static final String TEST_REACTION = "👀";

    private final GroupMeActions groupMeActions;

    public GroupMeCallbackController(GroupMeActions groupMeActions) {
        this.groupMeActions = groupMeActions;
    }

    @PostMapping("/groupme-bot-callback")
    public ResponseEntity<Void> onMessage(@RequestBody Map<String, Object> payload) {
        if (isFavoriteEvent(payload)) {
            onFavorite(payload);
            return ResponseEntity.ok().build();
        }

        boolean system = booleanOrFalse(payload.get("system"));
        String text = stringOrEmpty(payload.get("text"));
        if (system) {
            log.info("⚙️ System message", nullToEmpty(text));
        } else {
            log.info(
                    "📩 Incoming message",
                    "From: "
                            + stringOrEmpty(payload.get("name"))
                            + lineSeparator()
                            + "Content: "
                            + humanMessageBody(text));
            try {
                String groupId = stringOrEmpty(payload.get("group_id"));
                String messageId = stringOrEmpty(payload.get("id"));
                if (!groupId.isBlank() && !messageId.isBlank()) {
                    groupMeActions.reactWithUnicode(groupId, messageId, TEST_REACTION);
                }
            } catch (Exception e) {
                log.warn("Failed to react (message_id=" + stringOrEmpty(payload.get("id")) + ")", e);
            }
        }
        return ResponseEntity.ok().build();
    }

    private void onFavorite(Map<String, Object> payload) {
        Map<String, Object> subject = asMap(payload.get("subject"));
        String lineId = stringOrEmpty(asMap(subject.get("line")).get("id"));
        String userId = stringOrEmpty(subject.get("user_id"));
        String emojiCodes = asList(subject.get("reactions")).stream()
                .map(GroupMeCallbackController::asMap)
                .map(reaction -> stringOrEmpty(reaction.get("code")))
                .filter(code -> !code.isBlank())
                .collect(Collectors.joining(" "));

        log.info(
                "⭐ Favorite/reaction event",
                "line.id: "
                        + (lineId.isBlank() ? "(missing)" : lineId)
                        + lineSeparator()
                        + "subject.user_id: "
                        + (userId.isBlank() ? "(missing)" : userId)
                        + lineSeparator()
                        + "subject.reactions[].code: "
                        + (emojiCodes.isBlank() ? "(none)" : emojiCodes)
                        + lineSeparator()
                        + "raw payload: "
                        + payload);
    }

    private static boolean isFavoriteEvent(Map<String, Object> payload) {
        return "favorite".equals(stringOrEmpty(payload.get("type")));
    }

    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().collect(Collectors.toMap(
                    entry -> String.valueOf(entry.getKey()),
                    Map.Entry::getValue));
        }
        return Collections.emptyMap();
    }

    private static List<Object> asList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(item -> (Object) item).toList();
        }
        return List.of();
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

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String humanMessageBody(String text) {
        if (text == null || text.isBlank()) {
            return "(no text)";
        }
        return text;
    }
}
