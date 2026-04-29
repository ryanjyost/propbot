package com.propbot.propbot;

import static java.lang.System.lineSeparator;

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

    public boolean isFavoriteEvent(Map<String, Object> payload) {
        return "favorite".equals(stringOrEmpty(payload.get("type")));
    }

    public boolean handleIfFavorite(Map<String, Object> payload, String source) {
        if (!isFavoriteEvent(payload)) {
            return false;
        }
        logFavorite(payload, source);
        return true;
    }

    public void logFavorite(Map<String, Object> payload, String source) {
        Map<String, Object> subject = asMap(payload.get("subject"));
        String lineId = stringOrEmpty(asMap(subject.get("line")).get("id"));
        String userId = stringOrEmpty(subject.get("user_id"));
        String emojiCodes = asList(subject.get("reactions")).stream()
                .map(GroupMeFavoriteEventHandler::asMap)
                .map(reaction -> stringOrEmpty(reaction.get("code")))
                .filter(code -> !code.isBlank())
                .collect(Collectors.joining(" "));

        String headline = "⭐ Favorite/reaction event";
        if (source != null && !source.isBlank()) {
            headline += " (" + source + ")";
        }

        log.info(
                headline,
                "line.id: "
                        + (lineId.isBlank() ? "(missing)" : lineId)
                        + lineSeparator()
                        + "subject.user_id: "
                        + (userId.isBlank() ? "(missing)" : userId)
                        + lineSeparator()
                        + "subject.reactions[].code: "
                        + (emojiCodes.isBlank() ? "(none)" : emojiCodes));
                        // + lineSeparator()
                        // + "raw payload: "
                        // + payload);
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
