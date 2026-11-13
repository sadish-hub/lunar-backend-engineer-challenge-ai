package com.lunar.rockets.apply.claim;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class ChannelClaimJdbc {

    private final JdbcTemplate jdbc;

    public ChannelClaimJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<UUID> claimBatch(String workerId, int batchSize, int leaseSeconds) {
        return jdbc.query(
                """
                WITH picked AS (
                    SELECT channel
                    FROM channel_work
                    WHERE claimed_at IS NULL
                       OR claimed_at < now() - make_interval(secs => ?)
                    ORDER BY dirty_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE channel_work cw
                SET claimed_at = now(),
                    claimed_by = ?
                FROM picked
                WHERE cw.channel = picked.channel
                RETURNING cw.channel
                """,
                (rs, rowNum) -> rs.getObject(1, UUID.class),
                leaseSeconds,
                batchSize,
                workerId);
    }
}
