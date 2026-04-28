package com.propbot.propbot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GroupMeMessage(
    String id,
    @JsonProperty("source_guid") String sourceGuid,
    @JsonProperty("created_at") long createdAt,
    @JsonProperty("user_id") String userId,
    @JsonProperty("group_id") String groupId,
    String name,
    @JsonProperty("avatar_url") String avatarUrl,
    String text,
    boolean system,
    @JsonProperty("sender_id") String senderId,
    @JsonProperty("sender_type") String senderType,
    List<Object> attachments
) {}
