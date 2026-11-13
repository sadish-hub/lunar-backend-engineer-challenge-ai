package com.lunar.rockets.apply.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lunar.rockets.ingest.api.MessageType;
import com.lunar.rockets.ingest.persistence.MessageEntity;
import org.springframework.stereotype.Component;

@Component
public class ApplyCommandParser {

    private final ObjectMapper objectMapper;

    public ApplyCommandParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ApplyCommand parse(MessageEntity message) {
        long messageNumber = message.getId().getMessageNumber();
        MessageType type = MessageType.from(message.getMessageType())
                .orElseThrow(() -> new IllegalStateException(
                        "unknown message type at apply: " + message.getMessageType()));

        JsonNode root;
        try {
            root = objectMapper.readTree(message.getPayload());
        } catch (Exception ex) {
            throw new IllegalStateException("invalid stored payload for apply", ex);
        }
        JsonNode body = root.path("message");
        if (body.isMissingNode() || body.isNull()) {
            throw new IllegalStateException("stored payload missing message object");
        }

        return switch (type) {
            case RocketLaunched -> new ApplyCommand.Launch(
                    messageNumber,
                    text(body, "type"),
                    body.path("launchSpeed").asLong(),
                    text(body, "mission"));
            case RocketSpeedIncreased -> new ApplyCommand.SpeedIncreased(
                    messageNumber, body.path("by").asLong());
            case RocketSpeedDecreased -> new ApplyCommand.SpeedDecreased(
                    messageNumber, body.path("by").asLong());
            case RocketExploded -> new ApplyCommand.Exploded(messageNumber, text(body, "reason"));
            case RocketMissionChanged -> new ApplyCommand.MissionChanged(
                    messageNumber, text(body, "newMission"));
        };
    }

    private static String text(JsonNode body, String field) {
        JsonNode node = body.path(field);
        if (node.isMissingNode() || node.isNull()) {
            throw new IllegalStateException("stored payload missing field: " + field);
        }
        return node.asText();
    }
}
