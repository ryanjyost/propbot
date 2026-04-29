package com.propbot.propbot;

import com.google.auth.oauth2.GoogleCredentials;
import com.propbot.logging.AppLog;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    /** Column A is the shared message log; per-user metadata columns start at B. */
    private static final int FIRST_USER_COLUMN_INDEX = 1;

    /** Row 1 = user ids, row 2 = names; messages start at row 3. */
    private static final int MESSAGE_LOG_START_ROW = 3;

    private final GoogleSheetsProperties properties;
    private final RestClient restClient;
    private final JsonParser jsonParser = JsonParserFactory.getJsonParser();
    private final AtomicBoolean disabledNoticeLogged = new AtomicBoolean(false);

    /** Serialize sheet read + header updates + message writes (single-server, heavy chat). */
    private final Object sheetLock = new Object();

    private volatile GoogleCredentials credentials;

    public GoogleSheetsService(GoogleSheetsProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder().baseUrl(trimTrailingSlash(properties.apiBaseUrl())).build();
    }

    /**
     * Records an incoming chat message:
     *
     * <ul>
     *   <li>Appends the message text to column A (shared timeline), starting at A2</li>
     *   <li>Maintains per-user columns starting at B: row 1 = user id, row 2 = display name</li>
     * </ul>
     */
    public void appendIncomingMessage(String senderUserId, String senderName, String messageText) {
        if (!isConfigured()) {
            if (disabledNoticeLogged.compareAndSet(false, true)) {
                log.info(
                        "Google Sheets append disabled",
                        "Set GOOGLE_SHEETS_SPREADSHEET_ID and auth: either GOOGLE_SHEETS_CREDENTIALS_PATH "
                                + "(service account JSON) or GOOGLE_SHEETS_USE_ADC=true on GCP.");
            }
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

                appendToMessageLog(token, cellText);
            } catch (Exception e) {
                log.warn("Failed to record incoming message in Google Sheets", e);
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
        // Prefer an explicit service account JSON path when present. This avoids accidentally using
        // a narrowly-scoped Application Default Credentials source (e.g. gcloud user ADC) when
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
     * Reads a wide slice of rows 1-2 starting at column B so sparse layouts still detect existing user
     * columns (row 1 ids). Returned column indices are 0-based sheet column indices (A=0, B=1, ...).
     */
    private List<List<Object>> getUserHeaderRows(String token) {
        String range = properties.sheetName() + "!B1:ZZ2";
        String body = restClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/spreadsheets/{spreadsheetId}/values/{range}")
                        .build(properties.spreadsheetId(), range))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .body(String.class);
        List<List<Object>> partial = parseValuesMatrix(body);
        return padLeftWithBlankColumnA(partial);
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

    /** First column starting at B whose row-1 user id cell is blank (new user gets the next free id slot). */
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

    /**
     * Prefix a synthetic empty column A so indices in this matrix match 0-based sheet column indices.
     * The values response for {@code B1:ZZ2} omits column A entirely.
     */
    private static List<List<Object>> padLeftWithBlankColumnA(List<List<Object>> rows) {
        List<List<Object>> out = new ArrayList<>();
        for (List<Object> row : rows) {
            List<Object> padded = new ArrayList<>();
            padded.add("");
            padded.addAll(new ArrayList<>(row));
            out.add(padded);
        }
        // Ensure two rows exist (row1 row2), even if API returns only one row for sparse sheets.
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

    private void appendToMessageLog(String token, String messageText) {
        String range = properties.sheetName() + "!A" + MESSAGE_LOG_START_ROW + ":A";
        Map<String, Object> body = Map.of("values", List.of(List.of(messageText)));
        restClient
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
                .toBodilessEntity();
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
