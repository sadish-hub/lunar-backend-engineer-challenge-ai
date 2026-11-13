package com.lunar.rockets.ingest.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ChannelWorkRepository extends JpaRepository<ChannelWorkEntity, UUID> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value = """
                    INSERT INTO channel_work (channel, dirty_at)
                    VALUES (:channel, now())
                    ON CONFLICT (channel) DO UPDATE
                    SET dirty_at = excluded.dirty_at
                    """,
            nativeQuery = true)
    int upsertDirty(@Param("channel") UUID channel);
}
