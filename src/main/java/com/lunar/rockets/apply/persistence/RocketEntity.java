package com.lunar.rockets.apply.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rockets")
public class RocketEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "type")
    private String type;

    @Column(name = "speed", nullable = false)
    private long speed;

    @Column(name = "mission")
    private String mission;

    @Column(name = "exploded", nullable = false)
    private boolean exploded;

    @Column(name = "explosion_reason")
    private String explosionReason;

    @Column(name = "last_applied_number", nullable = false)
    private long lastAppliedNumber;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected RocketEntity() {
    }

    public RocketEntity(
            UUID id,
            String type,
            long speed,
            String mission,
            boolean exploded,
            String explosionReason,
            long lastAppliedNumber,
            long version) {
        this.id = id;
        this.type = type;
        this.speed = speed;
        this.mission = mission;
        this.exploded = exploded;
        this.explosionReason = explosionReason;
        this.lastAppliedNumber = lastAppliedNumber;
        this.version = version;
    }

    public UUID getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public long getSpeed() {
        return speed;
    }

    public String getMission() {
        return mission;
    }

    public boolean isExploded() {
        return exploded;
    }

    public String getExplosionReason() {
        return explosionReason;
    }

    public long getLastAppliedNumber() {
        return lastAppliedNumber;
    }

    public long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
