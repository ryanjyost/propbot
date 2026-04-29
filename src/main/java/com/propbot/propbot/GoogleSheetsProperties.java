package com.propbot.propbot;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "google.sheets")
public record GoogleSheetsProperties(
        String spreadsheetId,
        String sheetName,
        /** Optional explicit bearer token (short-lived). Prefer credentials below for production. */
        String accessToken,
        /** Path to a service account JSON key file (local/dev). */
        String credentialsPath,
        /**
         * When true, uses {@link com.google.auth.oauth2.GoogleCredentials#getApplicationDefault()} to mint
         * access tokens (recommended on Cloud Run with a service account attached).
         */
        boolean useApplicationDefaultCredentials,
        String apiBaseUrl) {

    public GoogleSheetsProperties {
        if (sheetName == null || sheetName.isBlank()) {
            sheetName = "Sheet1";
        }
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            apiBaseUrl = "https://sheets.googleapis.com/v4";
        }
    }
}
