package com.propbot.propbot;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponse;
import com.propbot.logging.AppLog;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;

@Service
public class GeminiService {

    private static final AppLog log = AppLog.forClass(GeminiService.class);

    private final GeminiProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicBoolean missingConfigLogged = new AtomicBoolean(false);
    private volatile Client client;

    public GeminiService(GeminiProperties properties) {
        this.properties = properties;
    }

    /**
     * Simple text generation wrapper around Gemini SDK generateContent.
     */
    public String generateText(String prompt) {
        log.info("Gemini generateText called", "prompt=" + prompt);
        String cleanPrompt = prompt == null ? "" : prompt.strip();
        if (cleanPrompt.isBlank()) {
            log.warn("Gemini generateText rejected blank prompt", "");
            throw new IllegalArgumentException("Prompt must not be blank");
        }
        if (!isConfigured()) {
            log.warn("Gemini generateText skipped: not configured", "Set GEMINI_API_KEY");
            throw new IllegalStateException("Gemini is not configured (set GEMINI_API_KEY)");
        }

        try {
            GenerateContentResponse response = getClient().models
                    .generateContent(properties.model().strip(), cleanPrompt, null);
            String text = response.text();
            if (text == null) {
                log.warn("Gemini response had no text", response.toString());
                return "";
            }
            return text;
        } catch (Exception e) {
            log.warn(
                    "Gemini SDK generateContent failed (model=%s, promptLength=%d)"
                            .formatted(stringOrEmpty(properties.model()), cleanPrompt.length()),
                    e);
            return "";
        }
    }

    /**
     * Classifies a message into one of the allowed categories and captures Gemini's
     * reason.
     */
    public CategoryDecision categorizeMessage(
            String messageText,
            Map<String, String> categoriesById,
            String fallbackCategoryId) {
        String safeFallback = stringOrEmpty(fallbackCategoryId).strip();
        if (safeFallback.isBlank()) {
            safeFallback = categoriesById.keySet().stream().findFirst().orElse("IGNORE");
        }

        if (categoriesById.isEmpty()) {
            log.warn("Gemini categorizeMessage called with no categories", "");
            return new CategoryDecision(
                    safeFallback,
                    "NO_CATEGORIES_CONFIGURED",
                    "");
        }

        if (!isConfigured()) {
            log.warn("Gemini categorizeMessage skipped: not configured", "Set GEMINI_API_KEY");
            CategoryDecision decision = new CategoryDecision(
                    safeFallback,
                    "GEMINI_NOT_CONFIGURED",
                    "");
            logCategoryDecision(decision, true);
            return decision;
        }

        String message = stringOrEmpty(messageText).strip();
        String categoryList = categoriesById.entrySet().stream()
                .map(entry -> entry.getKey() + " - " + stringOrEmpty(entry.getValue()))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");

        String prompt = """
                You are classifying a GroupMe message into exactly one category.
                Allowed categories:
                %s

                Return JSON only with this exact schema:
                {"category":"<CATEGORY_ID>","reason":"<short reason>"}

                Rules:
                - category must be exactly one of the allowed IDs.
                - If uncertain, choose %s.
                - reason should be brief and specific.

                Message:
                %s
                """.formatted(categoryList, safeFallback, message);

        String raw = stringOrEmpty(generateText(prompt)).strip();
        CategoryDecision parsed = parseCategoryDecision(raw);

        String chosenCategory = stringOrEmpty(parsed.categoryId()).strip();
        if (!categoriesById.containsKey(chosenCategory)) {
            chosenCategory = safeFallback;
        }
        String reason = stringOrEmpty(parsed.reason()).strip();
        if (reason.isBlank()) {
            reason = "NO_REASON_RETURNED";
        }

        CategoryDecision decision = new CategoryDecision(chosenCategory, reason, raw);
        logCategoryDecision(decision, false);
        return decision;
    }

    public boolean isConfigured() {
        boolean configured = !stringOrEmpty(properties.apiKey()).isBlank();
        if (!configured && missingConfigLogged.compareAndSet(false, true)) {
            log.warn("Gemini is not configured", "Missing GEMINI_API_KEY");
        }
        return configured;
    }

    private Client getClient() {
        Client current = client;
        if (current == null) {
            synchronized (this) {
                current = client;
                if (current == null) {
                    try {
                        String apiKey = stringOrEmpty(properties.apiKey());
                        if (apiKey.isBlank()) {
                            throw new IllegalStateException("Gemini API key is not configured");
                        }
                        current = Client.builder().apiKey(properties.apiKey().strip()).build();
                        client = current;
                        log.info("Gemini SDK client initialized", "model=" + stringOrEmpty(properties.model()));
                    } catch (Exception e) {
                        log.warn("Failed to initialize Gemini SDK client", e);
                        throw e;
                    }
                }
            }
        }
        return current;
    }

    @PreDestroy
    void closeClient() {
        Client current = client;
        if (current != null) {
            current.close();
        }
    }

    private CategoryDecision parseCategoryDecision(String raw) {
        String body = stripCodeFence(stringOrEmpty(raw).strip());
        if (body.isBlank()) {
            return new CategoryDecision("", "", raw);
        }
        try {
            Map<String, Object> map = objectMapper.readValue(body, new TypeReference<>() {
            });
            String category = stringOrEmpty(map.get("category")).strip();
            String reason = stringOrEmpty(map.get("reason")).strip();
            return new CategoryDecision(category, reason, raw);
        } catch (Exception e) {
            return new CategoryDecision("", "", raw);
        }
    }

    private void logCategoryDecision(CategoryDecision decision, boolean fallback) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("category", decision.categoryId());
        fields.put("reason", decision.reason());
        fields.put("fallback", fallback);
        fields.put("raw", truncate(decision.rawModelOutput(), 400));
        log.info("Gemini category decision", fields);
    }

    private static String stripCodeFence(String text) {
        String s = stringOrEmpty(text).strip();
        if (s.startsWith("```") && s.endsWith("```")) {
            List<String> lines = s.lines().toList();
            if (lines.size() >= 3) {
                return String.join("\n", lines.subList(1, lines.size() - 1)).strip();
            }
        }
        return s;
    }

    private static String truncate(String text, int maxLen) {
        String value = stringOrEmpty(text);
        if (value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLen)) + "...";
    }

    public record CategoryDecision(String categoryId, String reason, String rawModelOutput) {
    }

    private static String stringOrEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
