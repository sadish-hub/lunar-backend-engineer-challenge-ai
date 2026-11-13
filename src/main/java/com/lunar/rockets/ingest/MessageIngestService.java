package com.lunar.rockets.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;

@Service
class MessageIngestService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    MessageIngestService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    void ingest(MessageEnvelope envelope) {
        UUID channel = UUID.fromString(envelope.metadata().channel());
        long messageNumber = envelope.metadata().messageNumber();
        String messageType = envelope.metadata().messageType();
        Timestamp messageTime = parseMessageTime(envelope.metadata().messageTime());
        String payloadJson = toJson(envelope);

        int inserted = jdbc.update(
                """
                INSERT INTO messages (channel, message_number, message_type, message_time, payload)
                VALUES (?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (channel, message_number) DO NOTHING
                """,
                channel,
                messageNumber,
                messageType,
                messageTime,
                payloadJson);

        if (inserted == 1) {
            jdbc.update(
                    """
                    INSERT INTO channel_work (channel, dirty_at)
                    VALUES (?, now())
                    ON CONFLICT (channel) DO UPDATE
                    SET dirty_at = excluded.dirty_at
                    """,
                    channel);
        }
    }

    private String toJson(MessageEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize accepted envelope", ex);
        }
    }

    private static Timestamp parseMessageTime(String raw) {
        try {
            return Timestamp.from(Instant.parse(raw));
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return Timestamp.from(OffsetDateTime.parse(raw).toInstant());
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
