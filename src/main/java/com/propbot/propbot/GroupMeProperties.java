package com.propbot.propbot;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "groupme")
public record GroupMeProperties(
    String accessToken,
    String botId,
    String groupId,
    String apiBaseUrl,
    String pushUserId,
    String pushGatewayUrl
) {

    public GroupMeProperties {
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            apiBaseUrl = "https://api.groupme.com/v3";
        }
        if (pushGatewayUrl == null || pushGatewayUrl.isBlank()) {
            pushGatewayUrl = "wss://push.groupme.com/faye";
        }
    }
}
