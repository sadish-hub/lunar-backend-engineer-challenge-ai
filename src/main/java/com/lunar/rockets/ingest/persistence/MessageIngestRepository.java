package com.lunar.rockets.ingest.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.UUID;

@Repository
public class MessageIngestRepository {

    private final JdbcTemplate jdbc;

    public MessageIngestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return 1 if inserted, 0 if conflict (already accepted)
     */
    public int insertMessage(
            UUID channel,
            long messageNumber,
            String messageType,
            Timestamp messageTime,
            String payloadJson) {
        return jdbc.update(
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
    }

    public void markChannelDirty(UUID channel) {
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
