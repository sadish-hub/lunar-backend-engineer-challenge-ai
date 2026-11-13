package com.lunar.rockets.ingest.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<MessageEntity, MessageId> {

    /**
     * First-wins insert. Returns 1 if inserted, 0 if {@code (channel, message_number)} already exists.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value = """
                    INSERT INTO messages (channel, message_number, message_type, message_time, payload)
                    VALUES (:channel, :messageNumber, :messageType, :messageTime, CAST(:payload AS jsonb))
                    ON CONFLICT (channel, message_number) DO NOTHING
                    """,
            nativeQuery = true)
    int insertIgnoreConflict(
            @Param("channel") UUID channel,
            @Param("messageNumber") long messageNumber,
            @Param("messageType") String messageType,
            @Param("messageTime") Instant messageTime,
            @Param("payload") String payload);
}
