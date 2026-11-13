package com.lunar.rockets.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
class IngestValidator {

    void validate(MessageEnvelope envelope) {
        if (envelope == null) {
            throw new IngestValidationException("envelope required");
        }
        if (envelope.metadata() == null) {
            throw new IngestValidationException("metadata required");
        }
        if (envelope.message() == null || envelope.message().isNull() || !envelope.message().isObject()) {
            throw new IngestValidationException("message object required");
        }

        MessageMetadata metadata = envelope.metadata();
        requirePresent(metadata.channel(), "channel");
        parseUuid(metadata.channel());
        if (metadata.messageNumber() == null) {
            throw new IngestValidationException("messageNumber required");
        }
        requirePresent(metadata.messageTime(), "messageTime");

        MessageType type = MessageType.from(metadata.messageType())
                .orElseThrow(() -> new IngestValidationException("unknown or missing messageType"));

        validatePayload(type, envelope.message());
    }

    private void validatePayload(MessageType type, JsonNode message) {
        switch (type) {
            case RocketLaunched -> {
                requireText(message, "type");
                requireIntegralNumber(message, "launchSpeed");
                requireText(message, "mission");
            }
            case RocketSpeedIncreased, RocketSpeedDecreased -> {
                long by = requireIntegralNumber(message, "by");
                if (by < 0) {
                    throw new IngestValidationException("by must be non-negative");
                }
            }
            case RocketExploded -> requireText(message, "reason");
            case RocketMissionChanged -> requireText(message, "newMission");
        }
    }

    private static void requirePresent(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IngestValidationException(field + " required");
        }
    }

    private static void parseUuid(String channel) {
        try {
            UUID.fromString(channel);
        } catch (IllegalArgumentException ex) {
            throw new IngestValidationException("channel must be a UUID");
        }
    }

    private static void requireText(JsonNode message, String field) {
        JsonNode node = message.get(field);
        if (node == null || node.isNull() || !node.isTextual()) {
            throw new IngestValidationException(field + " required");
        }
    }

    private static long requireIntegralNumber(JsonNode message, String field) {
        JsonNode node = message.get(field);
        if (node == null || node.isNull() || !node.isIntegralNumber()) {
            throw new IngestValidationException(field + " required as integer");
        }
        return node.asLong();
    }
}
