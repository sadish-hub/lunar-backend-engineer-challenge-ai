package com.lunar.rockets.ingest.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "messages")
public class MessageEntity {

    @EmbeddedId
    private MessageId id;

    @Column(name = "message_type", nullable = false)
    private String messageType;

    @Column(name = "message_time")
    private Instant messageTime;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "accepted_at", nullable = false, insertable = false, updatable = false)
    private Instant acceptedAt;

    @Column(name = "applied", nullable = false)
    private boolean applied;

    protected MessageEntity() {
    }

    public MessageEntity(MessageId id, String messageType, Instant messageTime, String payload) {
        this.id = id;
        this.messageType = messageType;
        this.messageTime = messageTime;
        this.payload = payload;
        this.applied = false;
    }

    public MessageId getId() {
        return id;
    }

    public String getMessageType() {
        return messageType;
    }

    public Instant getMessageTime() {
        return messageTime;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public boolean isApplied() {
        return applied;
    }
}
