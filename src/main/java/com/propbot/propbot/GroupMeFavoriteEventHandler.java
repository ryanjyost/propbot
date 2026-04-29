package com.propbot.propbot;

import com.propbot.logging.AppLog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class GroupMeFavoriteEventHandler {

    private static final AppLog log = AppLog.forClass(GroupMeFavoriteEventHandler.class);
    private final GoogleSheetsService googleSheetsService;

    public GroupMeFavoriteEventHandler(GoogleSheetsService googleSheetsService) {
        this.googleSheetsService = googleSheetsService;
    }

    public boolean isFavoriteEvent(Map<String, Object> payload) {
        return "favorite".equals(stringOrEmpty(payload.get("type")));
    }

    public boolean handleIfFavorite(Map<String, Object> payload, String source) {
        if (!isFavoriteEvent(payload)) {
            return false;
        }
        logFavorite(payload, source);
        googleSheetsService.syncFavoriteReactions(payload);
        return true;
    }

    public void logFavorite(Map<String, Object> payload, String source) {
        Map<String, Object> subject = asMap(payload.get("subject"));
        String userId = stringOrEmpty(subject.get("user_id"));
        String userName = stringOrEmpty(asMap(subject.get("line")).get("name"));
        String emojiCodes = asList(subject.get("reactions")).stream()
                .map(GroupMeFavoriteEventHandler::asMap)
                .map(reaction -> stringOrEmpty(reaction.get("code")))
                .filter(code -> !code.isBlank())
                .collect(Collectors.joining(" "));

        String headline = "⭐ Favorite/reaction event";
        if (source != null && !source.isBlank()) {
            headline += " (" + source + ")";
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("user_id", userId);
        fields.put("name", userName);

        fields.put("reaction", emojiCodes);
        // fields.put("subject", subject);
        // fields.put("payload", payload);

        log.info(headline, fields);
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

    private static List<Object> asList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(item -> (Object) item).toList();
        }
        return List.of();
    }

    private static String stringOrEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
