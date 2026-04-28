package com.propbot.propbot;

import static java.lang.System.lineSeparator;

import com.propbot.logging.AppLog;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class GroupMeStartupLogger {

    private static final AppLog log = AppLog.forClass(GroupMeStartupLogger.class);

    private final GroupMeProperties groupMe;

    public GroupMeStartupLogger(GroupMeProperties groupMe) {
        this.groupMe = groupMe;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logGroupMeEnv() {
        log.info(
                "GroupMe env (from GROUPME_* / groupme.*)",
                "apiBaseUrl="
                        + groupMe.apiBaseUrl()
                        + lineSeparator()
                        + "pushGatewayUrl="
                        + groupMe.pushGatewayUrl()
                        + lineSeparator()
                        + "pushUserId="
                        + emptyToDash(groupMe.pushUserId())
                        + lineSeparator()
                        + "botId="
                        + emptyToDash(groupMe.botId())
                        + lineSeparator()
                        + "groupId="
                        + emptyToDash(groupMe.groupId())
                        + lineSeparator()
                        + "accessToken="
                        + maskSecret(groupMe.accessToken()));
    }

    private static String emptyToDash(String s) {
        return (s == null || s.isBlank()) ? "(empty)" : s;
    }

    /** Avoid printing full tokens in logs. */
    private static String maskSecret(String token) {
        if (token == null || token.isBlank()) {
            return "(empty)";
        }
        int n = token.length();
        if (n <= 8) {
            return "*" + n + " chars*";
        }
        return token.substring(0, 4) + "…" + token.substring(n - 4) + " (" + n + " chars)";
    }
}
