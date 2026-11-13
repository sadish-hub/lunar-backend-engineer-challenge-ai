package com.lunar.rockets.apply.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lunar.rockets.ingest.persistence.MessageEntity;
import com.lunar.rockets.ingest.persistence.MessageId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplyCommandParserTest {

    private final ApplyCommandParser parser = new ApplyCommandParser(new ObjectMapper());
    private final UUID channel = UUID.randomUUID();

    @Test
    void parsesAllMessageTypes() {
        assertThat(parser.parse(message(1, "RocketLaunched", """
                {"message":{"type":"Falcon-9","launchSpeed":100,"mission":"M"}}
                """))).isEqualTo(new ApplyCommand.Launch(1L, "Falcon-9", 100L, "M"));

        assertThat(parser.parse(message(2, "RocketSpeedIncreased", """
                {"message":{"by":5}}
                """))).isEqualTo(new ApplyCommand.SpeedIncreased(2L, 5L));

        assertThat(parser.parse(message(3, "RocketSpeedDecreased", """
                {"message":{"by":2}}
                """))).isEqualTo(new ApplyCommand.SpeedDecreased(3L, 2L));

        assertThat(parser.parse(message(4, "RocketExploded", """
                {"message":{"reason":"boom"}}
                """))).isEqualTo(new ApplyCommand.Exploded(4L, "boom"));

        assertThat(parser.parse(message(5, "RocketMissionChanged", """
                {"message":{"newMission":"N"}}
                """))).isEqualTo(new ApplyCommand.MissionChanged(5L, "N"));
    }

    @Test
    void unknownTypeFailsClosed() {
        assertThatThrownBy(() -> parser.parse(message(1, "RocketTeleported", "{\"message\":{}}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown message type");
    }

    @Test
    void invalidJsonFailsClosed() {
        assertThatThrownBy(() -> parser.parse(message(1, "RocketLaunched", "{not-json")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid stored payload");
    }

    @Test
    void missingMessageObjectFailsClosed() {
        assertThatThrownBy(() -> parser.parse(message(1, "RocketLaunched", "{\"metadata\":{}}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing message object");
    }

    @Test
    void missingRequiredFieldFailsClosed() {
        assertThatThrownBy(() -> parser.parse(message(1, "RocketExploded", "{\"message\":{}}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing field: reason");
    }

    private MessageEntity message(long mn, String type, String payload) {
        return new MessageEntity(new MessageId(channel, mn), type, Instant.parse("2026-01-01T00:00:00Z"), payload);
    }
}
