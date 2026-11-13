package com.lunar.rockets.apply.persistence;

import com.lunar.rockets.ingest.persistence.MessageEntity;
import com.lunar.rockets.ingest.persistence.MessageId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ApplyMessageRepository extends JpaRepository<MessageEntity, MessageId> {

    @Query("""
            SELECT m FROM MessageEntity m
            WHERE m.id.channel = :channel
              AND m.id.messageNumber = :messageNumber
              AND m.applied = false
            """)
    Optional<MessageEntity> findUnapplied(
            @Param("channel") UUID channel,
            @Param("messageNumber") long messageNumber);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value = """
                    UPDATE messages
                    SET applied = TRUE
                    WHERE channel = :channel
                      AND message_number = :messageNumber
                      AND applied = FALSE
                    """,
            nativeQuery = true)
    int markApplied(
            @Param("channel") UUID channel,
            @Param("messageNumber") long messageNumber);

    @Query("""
            SELECT COUNT(m) > 0 FROM MessageEntity m
            WHERE m.id.channel = :channel
              AND m.applied = false
            """)
    boolean hasUnapplied(@Param("channel") UUID channel);
}
