package com.lunar.rockets.ingest.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "channel_work")
public class ChannelWorkEntity {

    @Id
    @Column(name = "channel", nullable = false)
    private UUID channel;

    @Column(name = "dirty_at", nullable = false)
    private Instant dirtyAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "claimed_by")
    private String claimedBy;

    protected ChannelWorkEntity() {
    }

    public ChannelWorkEntity(UUID channel, Instant dirtyAt) {
        this.channel = channel;
        this.dirtyAt = dirtyAt;
    }

    public UUID getChannel() {
        return channel;
    }

    public Instant getDirtyAt() {
        return dirtyAt;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public String getClaimedBy() {
        return claimedBy;
    }
}
