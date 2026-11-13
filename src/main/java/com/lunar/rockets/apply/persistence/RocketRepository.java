package com.lunar.rockets.apply.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface RocketRepository extends JpaRepository<RocketEntity, UUID> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value = """
                    INSERT INTO rockets (
                        id, type, speed, mission, exploded, explosion_reason,
                        last_applied_number, version, updated_at
                    ) VALUES (
                        :id, :type, :speed, :mission, :exploded, :explosionReason,
                        :lastAppliedNumber, 1, now()
                    )
                    ON CONFLICT (id) DO NOTHING
                    """,
            nativeQuery = true)
    int insertNew(
            @Param("id") UUID id,
            @Param("type") String type,
            @Param("speed") long speed,
            @Param("mission") String mission,
            @Param("exploded") boolean exploded,
            @Param("explosionReason") String explosionReason,
            @Param("lastAppliedNumber") long lastAppliedNumber);

    /**
     * Optimistic CAS update. Returns 1 on success, 0 on version mismatch.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value = """
                    UPDATE rockets
                    SET type = :type,
                        speed = :speed,
                        mission = :mission,
                        exploded = :exploded,
                        explosion_reason = :explosionReason,
                        last_applied_number = :lastAppliedNumber,
                        version = version + 1,
                        updated_at = now()
                    WHERE id = :id
                      AND version = :expectedVersion
                    """,
            nativeQuery = true)
    int updateCas(
            @Param("id") UUID id,
            @Param("type") String type,
            @Param("speed") long speed,
            @Param("mission") String mission,
            @Param("exploded") boolean exploded,
            @Param("explosionReason") String explosionReason,
            @Param("lastAppliedNumber") long lastAppliedNumber,
            @Param("expectedVersion") long expectedVersion);
}
