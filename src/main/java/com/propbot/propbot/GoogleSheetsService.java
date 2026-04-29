package com.propbot.propbot;

import com.google.auth.oauth2.GoogleCredentials;
import com.propbot.logging.AppLog;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.json.JsonParser;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class GoogleSheetsService {

    private static final AppLog log = AppLog.forClass(GoogleSheetsService.class);

    private static final Set<String> SHEETS_SCOPES = Set.of("https://www.googleapis.com/auth/spreadsheets");

    /** Columns A/B are message id + text; per-user metadata columns start at C. */
    private static final int FIRST_USER_COLUMN_INDEX = 2;

    /** Row 1 = user ids, row 2 = names; messages start at row 3. */
    private static final int MESSAGE_LOG_START_ROW = 3;

    private final GoogleSheetsProperties properties;
    private final GroupMeProperties groupMeProperties;
    private final RestClient restClient;
    private final RestClient groupMeRestClient;
    private final JsonParser jsonParser = JsonParserFactory.getJsonParser();
    private final AtomicBoolean disabledNoticeLogged = new AtomicBoolean(false);

    /**
     * Serialize sheet read + header updates + message writes (single-server, heavy
     * chat).
     */
    private final Object sheetLock = new Object();

    /**
     * Best-effort cache of GroupMe user_id -> display name for fallback lookups.
     */
    private volatile Map<String, String> groupMemberNameCache = Map.of();

    private volatile GoogleCredentials credentials;

    public GoogleSheetsService(GoogleSheetsProperties properties, GroupMeProperties groupMeProperties) {
        this.properties = properties;
        this.groupMeProperties = groupMeProperties;
        this.restClient = RestClient.builder().baseUrl(trimTrailingSlash(properties.apiBaseUrl())).build();
        this.groupMeRestClient = RestClient.builder().baseUrl(trimTrailingSlash(groupMeProperties.apiBaseUrl()))
                .build();
    }

    /**
     * Records an incoming chat message:
     *
     * <ul>
     * <li>Appends message id + text to columns A/B (shared timeline), starting at
     * row 3</li>
     * <li>Maintains per-user columns starting at C: row 1 = user id, row 2 =
     * display name</li>
     * </ul>
     */
    public void appendIncomingMessage(
            String messageId, String senderUserId, String senderName, String messageText) {
        if (!isConfigured()) {
            if (disabledNoticeLogged.compareAndSet(false, true)) {
                log.info(
                        "Google Sheets append disabled",
                        "Set GOOGLE_SHEETS_SPREADSHEET_ID and auth: either GOOGLE_SHEETS_CREDENTIALS_PATH "
                                + "(service account JSON) or GOOGLE_SHEETS_USE_ADC=true on GCP.");
            }
            return;
        }

        String incomingMessageId = messageId == null ? "" : messageId.strip();
        if (incomingMessageId.isBlank()) {
            log.warn("Skipping Sheets row: missing message id", "");
            return;
        }

        String uid = senderUserId == null ? "" : senderUserId.strip();
        if (uid.isBlank()) {
            log.warn("Skipping Sheets row: missing sender user id", "");
            return;
        }

        String displayName = (senderName == null || senderName.isBlank()) ? "(unknown)" : senderName.strip();
        String cellText = (messageText == null || messageText.isBlank()) ? "(no text)" : messageText;

        synchronized (sheetLock) {
            try {
                String token = bearerToken();
                List<List<Object>> rows12 = getUserHeaderRows(token);
                int colIndex = findUserColumnIndex(rows12, uid);
                if (colIndex < 0) {
                    colIndex = firstAssignableUserColumnIndex(rows12);
                    String userColLetter = columnIndexToA1Letter(colIndex);
                    String sheet = properties.sheetName();
                    batchUpdate(
                            token,
                            List.of(
                                    updateRange(sheet + "!" + userColLetter + "1", List.of(List.of(uid))),
                                    updateRange(sheet + "!" + userColLetter + "2", List.of(List.of(displayName)))));
                }

                int messageRow = appendToMessageLog(token, incomingMessageId, cellText);
                if (messageRow >= MESSAGE_LOG_START_ROW) {
                    writeSenderMessageIntersection(token, messageRow, colIndex);
                } else {
                    log.warn("Message row unknown; skipped sender intersection mark", incomingMessageId);
                }
            } catch (Exception e) {
                log.warn("Failed to record incoming message in Google Sheets", e);
            }
        }
    }

    /**
     * Applies the latest reaction state for a favorite event by syncing the message
     * row intersections.
     *
     * <p>
     * Behavior:
     *
     * <ul>
     * <li>Find message row by message id in column A</li>
     * <li>Clear existing reaction cells for known user columns (C+)</li>
     * <li>Set sender intersection to "="</li>
     * <li>Set each reacting user's cell to their latest emoji code(s)</li>
     * </ul>
     */
    public void syncFavoriteReactions(Map<String, Object> favoritePayload) {
        if (!isConfigured()) {
            return;
        }
        Map<String, Object> subject = asMap(favoritePayload.get("subject"));
        Map<String, Object> line = asMap(subject.get("line"));

        String messageId = stringOrEmpty(line.get("id")).strip();
        if (messageId.isBlank()) {
            log.warn("Skipping reaction sync: missing message id", "");
            return;
        }

        String senderUserId = stringOrEmpty(line.get("user_id")).strip();
        String senderName = stringOrEmpty(line.get("name")).strip();

        String actorUserId = stringOrEmpty(favoritePayload.get("user_id")).strip();
        String actorName = stringOrEmpty(favoritePayload.get("name")).strip();

        Map<String, String> displayNamesByUserId = new LinkedHashMap<>();
        if (!senderUserId.isBlank() && !senderName.isBlank()) {
            displayNamesByUserId.put(senderUserId, senderName);
        }
        if (!actorUserId.isBlank() && !actorName.isBlank()) {
            displayNamesByUserId.put(actorUserId, actorName);
        }

        Map<String, String> emojiByUserId = collectEmojiByUserId(subject);

        synchronized (sheetLock) {
            try {
                String token = bearerToken();
                List<List<Object>> rows12 = getUserHeaderRows(token);

                int senderColIndex = -1;
                if (!senderUserId.isBlank()) {
                    senderColIndex = ensureUserColumn(
                            token,
                            rows12,
                            senderUserId,
                            resolveDisplayName(senderUserId, displayNamesByUserId, senderName));
                }
                for (String userId : emojiByUserId.keySet()) {
                    ensureUserColumn(
                            token,
                            rows12,
                            userId,
                            resolveDisplayName(userId, displayNamesByUserId, ""));
                }

                int messageRow = findMessageRowById(token, messageId);
                if (messageRow < MESSAGE_LOG_START_ROW) {
                    log.warn("Skipping reaction sync: message id not found in sheet", messageId);
                    return;
                }

                List<Map<String, Object>> updates = new ArrayList<>();
                String sheet = properties.sheetName();

                int userHeaderWidth = userHeaderWidth(rows12);
                for (int c = FIRST_USER_COLUMN_INDEX; c < userHeaderWidth; c++) {
                    if (!isCellEmpty(rows12, 0, c)) {
                        String cellRef = sheet + "!" + columnIndexToA1Letter(c) + messageRow;
                        updates.add(updateRange(cellRef, List.of(List.of(""))));
                    }
                }

                if (senderColIndex >= FIRST_USER_COLUMN_INDEX) {
                    String senderCellRef = sheet + "!" + columnIndexToA1Letter(senderColIndex) + messageRow;
                    updates.add(updateRange(senderCellRef, List.of(List.of("="))));
                }

                for (Map.Entry<String, String> entry : emojiByUserId.entrySet()) {
                    int colIndex = findUserColumnIndex(rows12, entry.getKey());
                    if (colIndex < FIRST_USER_COLUMN_INDEX) {
                        continue;
                    }
                    String cellRef = sheet + "!" + columnIndexToA1Letter(colIndex) + messageRow;
                    updates.add(updateRange(cellRef, List.of(List.of(entry.getValue()))));
                }

                if (!updates.isEmpty()) {
                    batchUpdate(token, updates);
                }
            } catch (Exception e) {
                log.warn("Failed to sync favorite reactions to Google Sheets", e);
            }
        }
    }

    private boolean isConfigured() {
        if (stringOrEmpty(properties.spreadsheetId()).isBlank()) {
            return false;
        }
        if (!stringOrEmpty(properties.accessToken()).isBlank()) {
            return true;
        }
        if (properties.useApplicationDefaultCredentials()) {
            return true;
        }
        return !stringOrEmpty(properties.credentialsPath()).isBlank();
    }

    private String bearerToken() throws IOException {
        if (!stringOrEmpty(properties.accessToken()).isBlank()) {
            return properties.accessToken().strip();
        }

        GoogleCredentials creds = credentials;
        if (creds == null) {
            synchronized (this) {
                creds = credentials;
                if (creds == null) {
                    creds = loadCredentials();
                    credentials = creds;
                }
            }
        }

        synchronized (creds) {
            creds.refreshIfExpired();
            return creds.getAccessToken().getTokenValue();
        }
    }

    private GoogleCredentials loadCredentials() throws IOException {
        // Prefer an explicit service account JSON path when present. This avoids
        // accidentally using
        // a narrowly-scoped Application Default Credentials source (e.g. gcloud user
        // ADC) when
        // GOOGLE_SHEETS_USE_ADC is also enabled.
        if (!stringOrEmpty(properties.credentialsPath()).isBlank()) {
            try (InputStream in = new FileInputStream(properties.credentialsPath().strip())) {
                return GoogleCredentials.fromStream(in).createScoped(SHEETS_SCOPES);
            }
        }
        if (properties.useApplicationDefaultCredentials()) {
            return GoogleCredentials.getApplicationDefault().createScoped(SHEETS_SCOPES);
        }
        throw new IllegalStateException(
                "Google Sheets auth is not configured (set GOOGLE_SHEETS_CREDENTIALS_PATH or GOOGLE_SHEETS_USE_ADC=true)");
    }

    /**
     * Reads a wide slice of rows 1-2 starting at column C so sparse layouts still
     * detect existing user
     * columns (row 1 ids). Returned column indices are 0-based sheet column indices
     * (A=0, B=1, ...).
     */
    private List<List<Object>> getUserHeaderRows(String token) {
        String range = properties.sheetName() + "!C1:ZZ2";
        String body = restClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/spreadsheets/{spreadsheetId}/values/{range}")
                        .build(properties.spreadsheetId(), range))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .body(String.class);
        List<List<Object>> partial = parseValuesMatrix(body);
        return padLeftWithBlankColumnsAB(partial);
    }

    private int findUserColumnIndex(List<List<Object>> rows12, String userId) {
        if (rows12.isEmpty()) {
            return -1;
        }
        List<Object> row1 = rows12.get(0);
        int width = row1.size();
        if (rows12.size() > 1) {
            width = Math.max(width, rows12.get(1).size());
        }
        for (int c = FIRST_USER_COLUMN_INDEX; c < width; c++) {
            String cell = c < row1.size() ? stringOrEmpty(row1.get(c)).strip() : "";
            if (userId.equals(cell)) {
                return c;
            }
        }
        return -1;
    }

    /**
     * First column starting at C whose row-1 user id cell is blank (new user gets
     * the next free id slot).
     */
    private int firstAssignableUserColumnIndex(List<List<Object>> rows12) {
        int width = 0;
        if (!rows12.isEmpty()) {
            width = rows12.get(0).size();
        }
        if (rows12.size() > 1) {
            width = Math.max(width, rows12.get(1).size());
        }
        for (int c = FIRST_USER_COLUMN_INDEX; c < width; c++) {
            if (isCellEmpty(rows12, 0, c)) {
                return c;
            }
        }
        return Math.max(FIRST_USER_COLUMN_INDEX, width);
    }

    private int ensureUserColumn(
            String token, List<List<Object>> rows12, String userId, String displayName) {
        int existing = findUserColumnIndex(rows12, userId);
        if (existing >= FIRST_USER_COLUMN_INDEX) {
            return existing;
        }
        int colIndex = firstAssignableUserColumnIndex(rows12);
        String userColLetter = columnIndexToA1Letter(colIndex);
        String sheet = properties.sheetName();
        batchUpdate(
                token,
                List.of(
                        updateRange(sheet + "!" + userColLetter + "1", List.of(List.of(userId))),
                        updateRange(
                                sheet + "!" + userColLetter + "2",
                                List.of(List.of(
                                        displayName == null || displayName.isBlank() ? "(unknown)" : displayName)))));
        setCell(rows12, 0, colIndex, userId);
        setCell(rows12, 1, colIndex, displayName == null || displayName.isBlank() ? "(unknown)" : displayName);
        return colIndex;
    }

    private int findMessageRowById(String token, String messageId) {
        String range = properties.sheetName() + "!A" + MESSAGE_LOG_START_ROW + ":A";
        String body = restClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/spreadsheets/{spreadsheetId}/values/{range}")
                        .build(properties.spreadsheetId(), range))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .body(String.class);
        List<List<Object>> rows = parseValuesMatrix(body);
        for (int i = 0; i < rows.size(); i++) {
            List<Object> row = rows.get(i);
            String idInSheet = row.isEmpty() ? "" : stringOrEmpty(row.get(0)).strip();
            if (messageId.equals(idInSheet)) {
                return MESSAGE_LOG_START_ROW + i;
            }
        }
        return -1;
    }

    private static void setCell(List<List<Object>> rows, int rowIndex, int colIndex, Object value) {
        while (rows.size() <= rowIndex) {
            rows.add(new ArrayList<>());
        }
        List<Object> row = rows.get(rowIndex);
        while (row.size() <= colIndex) {
            row.add("");
        }
        row.set(colIndex, value);
    }

    private static int userHeaderWidth(List<List<Object>> rows12) {
        int width = 0;
        if (!rows12.isEmpty()) {
            width = rows12.get(0).size();
        }
        if (rows12.size() > 1) {
            width = Math.max(width, rows12.get(1).size());
        }
        return width;
    }

    private static String preferredName(String userId, Map<String, String> names, String fallback) {
        String fromMap = stringOrEmpty(names.get(userId)).strip();
        if (!fromMap.isBlank()) {
            return fromMap;
        }
        String fb = stringOrEmpty(fallback).strip();
        return fb.isBlank() ? "(unknown)" : fb;
    }

    private String resolveDisplayName(String userId, Map<String, String> eventNames, String fallback) {
        String preferred = preferredName(userId, eventNames, fallback);
        if (!"(unknown)".equals(preferred)) {
            return preferred;
        }
        String groupMemberName = lookupGroupMemberName(userId);
        return groupMemberName.isBlank() ? preferred : groupMemberName;
    }

    private String lookupGroupMemberName(String userId) {
        String uid = stringOrEmpty(userId).strip();
        if (uid.isBlank()) {
            return "";
        }
        String cached = groupMemberNameCache.get(uid);
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        Map<String, String> refreshed = fetchGroupMemberNames();
        if (refreshed.isEmpty()) {
            return "";
        }
        groupMemberNameCache = refreshed;
        return stringOrEmpty(refreshed.get(uid)).strip();
    }

    private Map<String, String> fetchGroupMemberNames() {
        String groupId = stringOrEmpty(groupMeProperties.groupId()).strip();
        String token = stringOrEmpty(groupMeProperties.accessToken()).strip();
        if (groupId.isBlank() || token.isBlank()) {
            return Map.of();
        }
        try {
            String body = groupMeRestClient
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/groups/{groupId}")
                            .queryParam("token", token)
                            .build(groupId))
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isBlank()) {
                return Map.of();
            }
            Map<String, Object> root = jsonParser.parseMap(body);
            Map<String, Object> response = asMap(root.get("response"));
            Map<String, String> out = new HashMap<>();
            for (Object memberObj : asList(response.get("members"))) {
                Map<String, Object> member = asMap(memberObj);
                String uid = stringOrEmpty(member.get("user_id")).strip();
                if (uid.isBlank()) {
                    continue;
                }
                String name = stringOrEmpty(member.get("nickname")).strip();
                if (name.isBlank()) {
                    name = stringOrEmpty(member.get("name")).strip();
                }
                if (!name.isBlank()) {
                    out.put(uid, name);
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("Failed to fetch GroupMe group members for reaction name lookup", e);
            return Map.of();
        }
    }

    private static Map<String, String> collectEmojiByUserId(Map<String, Object> subject) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Object reactionObj : asList(subject.get("reactions"))) {
            Map<String, Object> reaction = asMap(reactionObj);
            String code = stringOrEmpty(reaction.get("code")).strip();
            if (code.isBlank()) {
                continue;
            }
            for (Object userIdObj : asList(reaction.get("user_ids"))) {
                String userId = stringOrEmpty(userIdObj).strip();
                if (userId.isBlank()) {
                    continue;
                }
                out.merge(
                        userId,
                        code,
                        (existing, next) -> existing.contains(next) ? existing : existing + " " + next);
            }
        }
        return out;
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

    /**
     * Prefix synthetic empty columns A/B so indices in this matrix match 0-based
     * sheet column indices.
     * The values response for {@code C1:ZZ2} omits columns A/B entirely.
     */
    private static List<List<Object>> padLeftWithBlankColumnsAB(List<List<Object>> rows) {
        List<List<Object>> out = new ArrayList<>();
        for (List<Object> row : rows) {
            List<Object> padded = new ArrayList<>();
            padded.add("");
            padded.add("");
            padded.addAll(new ArrayList<>(row));
            out.add(padded);
        }
        // Ensure two rows exist (row1 row2), even if API returns only one row for
        // sparse sheets.
        while (out.size() < 2) {
            out.add(new ArrayList<>(List.of("")));
        }
        return out;
    }

    private static boolean isCellEmpty(List<List<Object>> rows, int rowIndex, int colIndex) {
        if (rowIndex >= rows.size()) {
            return true;
        }
        List<Object> row = rows.get(rowIndex);
        if (colIndex >= row.size()) {
            return true;
        }
        Object v = row.get(colIndex);
        return v == null || stringOrEmpty(v).isBlank();
    }

    private int appendToMessageLog(String token, String messageId, String messageText) {
        String range = properties.sheetName() + "!A" + MESSAGE_LOG_START_ROW + ":B";
        Map<String, Object> body = Map.of("values", List.of(List.of(messageId, messageText)));
        String response = restClient
                .post()
                .uri(uriBuilder -> uriBuilder
                        .path("/spreadsheets/{spreadsheetId}/values/{range}:append")
                        .queryParam("valueInputOption", "RAW")
                        .queryParam("insertDataOption", "INSERT_ROWS")
                        .build(properties.spreadsheetId(), range))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        return parseAppendedMessageRow(response);
    }

    private void writeSenderMessageIntersection(String token, int messageRow, int userColumnIndex) {
        String userColLetter = columnIndexToA1Letter(userColumnIndex);
        String range = properties.sheetName() + "!" + userColLetter + messageRow;
        batchUpdate(token, List.of(updateRange(range, List.of(List.of("=")))));
    }

    private int parseAppendedMessageRow(String appendResponseJson) {
        if (appendResponseJson == null || appendResponseJson.isBlank()) {
            return -1;
        }
        try {
            Map<String, Object> root = jsonParser.parseMap(appendResponseJson);
            Object updatesObj = root.get("updates");
            if (!(updatesObj instanceof Map<?, ?> updates)) {
                return -1;
            }
            Object updatedRangeObj = updates.get("updatedRange");
            String updatedRange = stringOrEmpty(updatedRangeObj).strip();
            if (updatedRange.isBlank()) {
                return -1;
            }
            int bang = updatedRange.lastIndexOf('!');
            String a1Range = bang >= 0 ? updatedRange.substring(bang + 1) : updatedRange;
            int colon = a1Range.indexOf(':');
            String startCell = colon >= 0 ? a1Range.substring(0, colon) : a1Range;
            int digitStart = -1;
            for (int i = 0; i < startCell.length(); i++) {
                if (Character.isDigit(startCell.charAt(i))) {
                    digitStart = i;
                    break;
                }
            }
            if (digitStart < 0) {
                return -1;
            }
            return Integer.parseInt(startCell.substring(digitStart));
        } catch (Exception e) {
            log.warn("Failed to parse Sheets append response", e);
            return -1;
        }
    }

    private void batchUpdate(String token, List<Map<String, Object>> updates) {
        Map<String, Object> body = Map.of("valueInputOption", "RAW", "data", updates);
        restClient
                .post()
                .uri("/spreadsheets/{spreadsheetId}/values:batchUpdate", properties.spreadsheetId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    private static Map<String, Object> updateRange(String range, List<List<Object>> values) {
        return Map.of("range", range, "values", values);
    }

    private List<List<Object>> parseValuesMatrix(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> root = jsonParser.parseMap(json);
            Object values = root.get("values");
            if (!(values instanceof List<?> list)) {
                return List.of();
            }
            List<List<Object>> out = new ArrayList<>();
            for (Object rowObj : list) {
                if (rowObj instanceof List<?> row) {
                    List<Object> copy = new ArrayList<>();
                    for (Object cell : row) {
                        copy.add(cell);
                    }
                    out.add(copy);
                } else {
                    out.add(new ArrayList<>());
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("Failed to parse Sheets values response", e);
            return List.of();
        }
    }

    /** 0-based column index → A1 column letters (supports beyond Z). */
    private static String columnIndexToA1Letter(int colIndex) {
        if (colIndex < 0) {
            throw new IllegalArgumentException("colIndex must be >= 0");
        }
        StringBuilder sb = new StringBuilder();
        int n = colIndex + 1;
        while (n > 0) {
            n--;
            sb.append((char) ('A' + (n % 26)));
            n /= 26;
        }
        return sb.reverse().toString();
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String stringOrEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String stringOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
