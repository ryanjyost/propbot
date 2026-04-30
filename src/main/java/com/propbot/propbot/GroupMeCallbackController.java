package com.propbot.propbot;

import com.propbot.logging.AppLog;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class GroupMeCallbackController {

    private static final AppLog log = AppLog.forClass(GroupMeCallbackController.class);

    private final GroupMeFavoriteEventHandler favoriteEventHandler;
    private final GroupMeIncomingMessageHandler incomingMessageHandler;

    public GroupMeCallbackController(
            GroupMeFavoriteEventHandler favoriteEventHandler,
            GroupMeIncomingMessageHandler incomingMessageHandler) {
        this.favoriteEventHandler = favoriteEventHandler;
        this.incomingMessageHandler = incomingMessageHandler;
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
            incomingMessageHandler.handleIncomingMessage(payload, text);
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

}
