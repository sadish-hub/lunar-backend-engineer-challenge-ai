package com.lunar.rockets.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private IngestValidator validator;

    @BeforeEach
    void setUp() {
        validator = new IngestValidator();
    }

    @Test
    void acceptsValidRocketLaunched() {
        assertThatCode(() -> validator.validate(envelope(
                "RocketLaunched",
                message -> message
                        .put("type", "Falcon-9")
                        .put("launchSpeed", 500)
                        .put("mission", "ARTEMIS"))))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsValidSpeedIncreased() {
        assertThatCode(() -> validator.validate(envelope(
                "RocketSpeedIncreased",
                message -> message.put("by", 100))))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsValidSpeedDecreased() {
        assertThatCode(() -> validator.validate(envelope(
                "RocketSpeedDecreased",
                message -> message.put("by", 50))))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsByZero() {
        assertThatCode(() -> validator.validate(envelope(
                "RocketSpeedIncreased",
                message -> message.put("by", 0))))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsValidExploded() {
        assertThatCode(() -> validator.validate(envelope(
                "RocketExploded",
                message -> message.put("reason", "PRESSURE_VESSEL_FAILURE"))))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsValidMissionChanged() {
        assertThatCode(() -> validator.validate(envelope(
                "RocketMissionChanged",
                message -> message.put("newMission", "LUNAR"))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingMetadata() {
        MessageEnvelope envelope = new MessageEnvelope(null, mapper.createObjectNode().put("by", 1));
        assertThatThrownBy(() -> validator.validate(envelope))
                .isInstanceOf(IngestValidationException.class);
    }

    @Test
    void rejectsMissingMessage() {
        MessageEnvelope envelope = new MessageEnvelope(
                new MessageMetadata(UUID.randomUUID().toString(), 1L, "2026-01-01T00:00:00Z", "RocketSpeedIncreased"),
                null);
        assertThatThrownBy(() -> validator.validate(envelope))
                .isInstanceOf(IngestValidationException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void rejectsMissingChannel(String channel) {
        MessageEnvelope envelope = new MessageEnvelope(
                new MessageMetadata(channel, 1L, "2026-01-01T00:00:00Z", "RocketExploded"),
                mapper.createObjectNode().put("reason", "x"));
        assertThatThrownBy(() -> validator.validate(envelope))
                .isInstanceOf(IngestValidationException.class);
    }

    @Test
    void rejectsInvalidChannelUuid() {
        MessageEnvelope envelope = new MessageEnvelope(
                new MessageMetadata("not-a-uuid", 1L, "2026-01-01T00:00:00Z", "RocketExploded"),
                mapper.createObjectNode().put("reason", "x"));
        assertThatThrownBy(() -> validator.validate(envelope))
                .isInstanceOf(IngestValidationException.class);
    }

    @Test
    void rejectsMissingMessageNumber() {
        MessageEnvelope envelope = new MessageEnvelope(
                new MessageMetadata(UUID.randomUUID().toString(), null, "2026-01-01T00:00:00Z", "RocketExploded"),
                mapper.createObjectNode().put("reason", "x"));
        assertThatThrownBy(() -> validator.validate(envelope))
                .isInstanceOf(IngestValidationException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void rejectsMissingMessageTime(String messageTime) {
        MessageEnvelope envelope = new MessageEnvelope(
                new MessageMetadata(UUID.randomUUID().toString(), 1L, messageTime, "RocketExploded"),
                mapper.createObjectNode().put("reason", "x"));
        assertThatThrownBy(() -> validator.validate(envelope))
                .isInstanceOf(IngestValidationException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"UnknownType", "rocketLaunched"})
    void rejectsUnknownOrMissingMessageType(String messageType) {
        MessageEnvelope envelope = new MessageEnvelope(
                new MessageMetadata(UUID.randomUUID().toString(), 1L, "2026-01-01T00:00:00Z", messageType),
                mapper.createObjectNode().put("type", "Falcon-9").put("launchSpeed", 1).put("mission", "M"));
        assertThatThrownBy(() -> validator.validate(envelope))
                .isInstanceOf(IngestValidationException.class);
    }

    @Test
    void rejectsMissingLaunchFields() {
        assertThatThrownBy(() -> validator.validate(envelope(
                "RocketLaunched",
                message -> message.put("type", "Falcon-9"))))
                .isInstanceOf(IngestValidationException.class);
    }

    @Test
    void rejectsMissingBy() {
        assertThatThrownBy(() -> validator.validate(envelope(
                "RocketSpeedIncreased",
                message -> message.put("unused", 1))))
                .isInstanceOf(IngestValidationException.class);
    }

    @Test
    void rejectsNegativeByOnIncrease() {
        assertThatThrownBy(() -> validator.validate(envelope(
                "RocketSpeedIncreased",
                message -> message.put("by", -1))))
                .isInstanceOf(IngestValidationException.class);
    }

    @Test
    void rejectsNegativeByOnDecrease() {
        assertThatThrownBy(() -> validator.validate(envelope(
                "RocketSpeedDecreased",
                message -> message.put("by", -5))))
                .isInstanceOf(IngestValidationException.class);
    }

    @Test
    void rejectsMissingExplosionReason() {
        assertThatThrownBy(() -> validator.validate(envelope(
                "RocketExploded",
                message -> message.put("other", "x"))))
                .isInstanceOf(IngestValidationException.class);
    }

    @Test
    void rejectsMissingNewMission() {
        assertThatThrownBy(() -> validator.validate(envelope(
                "RocketMissionChanged",
                message -> message.put("mission", "x"))))
                .isInstanceOf(IngestValidationException.class);
    }

    private MessageEnvelope envelope(String messageType, java.util.function.Consumer<ObjectNode> messageBuilder) {
        ObjectNode message = mapper.createObjectNode();
        messageBuilder.accept(message);
        return new MessageEnvelope(
                new MessageMetadata(UUID.randomUUID().toString(), 1L, "2026-01-01T00:00:00Z", messageType),
                message);
    }
}
