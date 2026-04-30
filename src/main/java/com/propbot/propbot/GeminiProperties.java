package com.propbot.propbot;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gemini")
public record GeminiProperties(
        /**
         * Model name without API prefix.
         *
         * <p>
         * Examples: gemini-2.5-flash, gemini-2.5-pro
         */
        String model,
        /** AI Studio API key auth. */
        String apiKey) {

    public GeminiProperties {
        if (model == null || model.isBlank()) {
            model = "gemini-2.5-flash-lite";
        }
    }
}
