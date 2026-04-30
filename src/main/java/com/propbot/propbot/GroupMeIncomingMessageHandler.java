package com.propbot.propbot;

import com.propbot.logging.AppLog;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class GroupMeIncomingMessageHandler {

    private static final AppLog log = AppLog.forClass(GroupMeIncomingMessageHandler.class);
    private static final String TEST_REACTION = "🔥";

    private final GroupMeActions groupMeActions;
    private final GoogleSheetsService googleSheetsService;
    private final GeminiService geminiService;

    public GroupMeIncomingMessageHandler(
            GroupMeActions groupMeActions,
            GoogleSheetsService googleSheetsService,
            GeminiService geminiService) {
        this.groupMeActions = groupMeActions;
        this.googleSheetsService = googleSheetsService;
        this.geminiService = geminiService;
    }

    public void handleIncomingMessage(Map<String, Object> payload, String text) {
        String senderUserId = stringOrEmpty(payload.get("user_id"));
        String groupId = stringOrEmpty(payload.get("group_id"));
        String senderType = stringOrEmpty(payload.get("sender_type"));

        if (senderUserId.isBlank()) {
            senderUserId = stringOrEmpty(payload.get("sender_id"));
        }
        String messageId = stringOrEmpty(payload.get("id"));

        if ("bot".equalsIgnoreCase(senderType.strip())) {
            log.info("Skipping bot-authored message to prevent loops", Map.of("messageId", messageId));
            return;
        }

        Map<String, Object> incoming = new LinkedHashMap<>();
        incoming.put("from", stringOrEmpty(payload.get("name")));
        incoming.put("userId", senderUserId.isBlank() ? null : senderUserId);
        incoming.put("content", humanMessageBody(text));
        incoming.put("messageId", messageId.isBlank() ? null : messageId);
        log.info("Incoming message", incoming);

        MessageCategoryResult categoryResult = categorizeMessage(text);
        Map<String, Object> categoryLog = new LinkedHashMap<>();
        categoryLog.put("category", categoryResult.category().id());
        categoryLog.put("reason", categoryResult.reason());
        log.info("Message category", categoryLog);

        if (categoryResult.category() == MessageCategory.BINARY_BET) {
            googleSheetsService.appendIncomingMessage(
                    messageId, senderUserId, stringOrEmpty(payload.get("name")), text);

            try {
                if (!groupId.isBlank() && !messageId.isBlank()) {
                    groupMeActions.reactWithUnicode(groupId, messageId, TEST_REACTION);
                }
            } catch (Exception e) {
                log.warn("Failed to react (message_id=" + stringOrEmpty(payload.get("id")) + ")", e);
            }
        } else if (categoryResult.category() == MessageCategory.BET_STATUS_REQUEST) {
            sendPerPersonSummaryToGroup(senderUserId, messageId);
        }
    }

    /**
     * Categorizes a message with Gemini and returns a typed result that can grow as
     * categories are added.
     */
    public MessageCategoryResult categorizeMessage(String text) {
        String body = humanMessageBody(text);
        Map<String, String> categories = Arrays.stream(MessageCategory.values())
                .collect(Collectors.toMap(
                        MessageCategory::id,
                        MessageCategory::description,
                        (left, right) -> left,
                        LinkedHashMap::new));

        GeminiService.CategoryDecision decision = geminiService.categorizeMessage(
                body,
                categories,
                MessageCategory.IGNORE.id());

        MessageCategory category = MessageCategory.fromId(decision.categoryId());
        if (category == null) {
            log.warn("Gemini returned unknown category; defaulting to IGNORE", decision.categoryId());
            category = MessageCategory.IGNORE;
        }
        return new MessageCategoryResult(category, decision.reason(), decision.rawModelOutput());
    }

    public enum MessageCategory {
        BINARY_BET("BINARY_BET", "A message expressing a yes/no or one-of-two prediction/wager."),
        BET_STATUS_REQUEST(
                "BET_STATUS_REQUEST",
                "A message asking to see the sender's betting summary, bets, status, or standings."),
        IGNORE("IGNORE", "Not clearly attributable to another category; should be ignored by the system.");

        private final String id;
        private final String description;

        MessageCategory(String id, String description) {
            this.id = id;
            this.description = description;
        }

        public String id() {
            return id;
        }

        public String description() {
            return description;
        }

        public static MessageCategory fromId(String id) {
            String normalized = stringOrEmpty(id).strip();
            for (MessageCategory category : values()) {
                if (category.id.equalsIgnoreCase(normalized)) {
                    return category;
                }
            }
            return null;
        }
    }

    public record MessageCategoryResult(MessageCategory category, String reason, String rawModelOutput) {
    }

    private static String stringOrEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String humanMessageBody(String text) {
        if (text == null || text.isBlank()) {
            return "(no text)";
        }
        return text;
    }

    private static String perBetSummaryMessage(GoogleSheetsService.PerBetSummary summary) {
        String name = fallback(summary.name(), "User");
        String totalBets = fallback(summary.totalBets(), "-");
        String atRisk = fallback(summary.atRisk(), "-");
        String potentialWinnings = fallback(summary.potentialWinnings(), "-");
        String payout = fallback(summary.potentialPayout(), "-");
        String settledPnl = fallback(summary.pnl(), "-");
        String netPosition = computeNetPosition(summary.atRisk(), summary.pnl());
        return "@" + name + " \uD83C\uDFB0\n"
                + "Open bets: " + totalBets + " (" + atRisk + " at risk)\n"
                + "Potential winnings: " + potentialWinnings + " (gross payout: " + payout + ")\n"
                + "Settled P&L: " + settledPnl + "\n"
                + "Net position: " + netPosition;
    }

    private static String fallback(String value, String defaultValue) {
        String s = stringOrEmpty(value).strip();
        return s.isBlank() ? defaultValue : s;
    }

    private static String computeNetPosition(String atRisk, String settledPnl) {
        Double atRiskAmount = parseCurrency(atRisk);
        Double settledAmount = parseCurrency(settledPnl);
        if (atRiskAmount == null || settledAmount == null) {
            return "-";
        }
        return formatSignedCurrency(settledAmount - atRiskAmount);
    }

    private static Double parseCurrency(String raw) {
        String value = stringOrEmpty(raw).strip();
        if (value.isBlank() || "-".equals(value)) {
            return null;
        }
        String cleaned = value.replace("$", "").replace(",", "").replace(" ", "");
        boolean negative = cleaned.startsWith("-") || cleaned.contains("(-") || value.contains(" -");
        cleaned = cleaned.replace("(", "").replace(")", "").replace("+", "").replace("-", "");
        if (cleaned.isBlank()) {
            return null;
        }
        try {
            double number = Double.parseDouble(cleaned);
            return negative ? -number : number;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String formatSignedCurrency(double amount) {
        String sign = amount > 0 ? "+" : (amount < 0 ? "-" : "");
        return sign + "$" + String.format("%.2f", Math.abs(amount));
    }

    private void sendPerPersonSummaryToGroup(String senderUserId, String messageId) {
        GoogleSheetsService.PerBetSummary perBetSummary = googleSheetsService.findPerBetSummaryByUserId(senderUserId);
        String replyToMessageId = stringOrEmpty(messageId).strip();
        try {
            if (perBetSummary != null) {
                groupMeActions.sendBotMessage(perBetSummaryMessage(perBetSummary), replyToMessageId);
                return;
            }
            log.info("No per-person row found for sender", Map.of("userId", senderUserId));
        } catch (Exception e) {
            log.warn("Failed to send per-person summary message", e);
        }
    }
}
