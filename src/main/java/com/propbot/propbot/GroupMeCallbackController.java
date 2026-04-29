package com.propbot.propbot;

import static java.lang.System.lineSeparator;

import com.propbot.logging.AppLog;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class GroupMeCallbackController {

    private static final AppLog log = AppLog.forClass(GroupMeCallbackController.class);

    private static final String TEST_REACTION = "🙏";

    private final GroupMeActions groupMeActions;
    private final GroupMeFavoriteEventHandler favoriteEventHandler;
    private final GoogleSheetsService googleSheetsService;

    public GroupMeCallbackController(
            GroupMeActions groupMeActions,
            GroupMeFavoriteEventHandler favoriteEventHandler,
            GoogleSheetsService googleSheetsService) {
        this.groupMeActions = groupMeActions;
        this.favoriteEventHandler = favoriteEventHandler;
        this.googleSheetsService = googleSheetsService;
    }

    @PostMapping("/groupme-bot-callback")
    public ResponseEntity<Void> onMessage(@RequestBody Map<String, Object> payload) {
        if (favoriteEventHandler.handleIfFavorite(payload, "callback")) {
            return ResponseEntity.ok().build();
        }

        boolean system = booleanOrFalse(payload.get("system"));
        String text = stringOrEmpty(payload.get("text"));
        if (system) {
            log.info("⚙️ System message", nullToEmpty(text));
        } else {
            String senderUserId = stringOrEmpty(payload.get("user_id"));
            if (senderUserId.isBlank()) {
                senderUserId = stringOrEmpty(payload.get("sender_id"));
            }
            log.info(
                    "📩 Incoming message",
                    "From: "
                            + stringOrEmpty(payload.get("name"))
                            + lineSeparator()
                            + "User ID: "
                            + (senderUserId.isBlank() ? "(missing)" : senderUserId)
                            + lineSeparator()
                            + "Content: "
                            + humanMessageBody(text));
            googleSheetsService.appendIncomingMessage(
                    senderUserId, stringOrEmpty(payload.get("name")), text);
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
