package com.lunar.rockets.apply.claim;

import com.lunar.rockets.ingest.persistence.ChannelWorkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * Claim mutations. Batch claim with {@code SKIP LOCKED} lives in {@link ChannelClaimJdbc}.
 */
public interface ChannelWorkClaimRepository extends JpaRepository<ChannelWorkEntity, UUID> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value = """
                    UPDATE channel_work
                    SET claimed_at = NULL,
                        claimed_by = NULL
                    WHERE channel = :channel
                    """,
            nativeQuery = true)
    int clearClaim(@Param("channel") UUID channel);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value = """
                    DELETE FROM channel_work
                    WHERE channel = :channel
                    """,
            nativeQuery = true)
    int deleteWork(@Param("channel") UUID channel);
}
